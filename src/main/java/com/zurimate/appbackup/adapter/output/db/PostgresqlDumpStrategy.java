package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.DumpSession;
import com.zurimate.appbackup.ports.output.DumpSettings;
import com.zurimate.appbackup.ports.output.TableDumper;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.DbConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.zurimate.appbackup.adapter.output.db.SqlText.pgIdent;
import static com.zurimate.appbackup.adapter.output.db.SqlText.pgString;

/**
 * Logical dump of the {@code public} schema of a PostgreSQL database.
 * <p>
 * The coordinator connection opens a REPEATABLE READ transaction and exports its snapshot;
 * every worker imports that snapshot, so all tables are read from the same instant.
 * Foreign keys are written after the data so table files can be loaded in any order.
 */
@Slf4j
@Component
class PostgresqlDumpStrategy extends SqlDumpStrategy {
    private static final String SCHEMA = "public";

    private static final String NOT_EXTENSION_MEMBER = """
            NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')""";

    @Override
    public Set<DBType> dbTypes() {
        return Set.of(DBType.POSTGRESQL);
    }

    @Override
    protected String restoreInstructions() {
        return """
                psql -v ON_ERROR_STOP=1 -d <db> -f 00-pre-data.sql
                for f in tables/*.sql; do psql -v ON_ERROR_STOP=1 -d <db> -f "$f"; done
                psql -v ON_ERROR_STOP=1 -d <db> -f 90-post-data.sql
                """;
    }

    @Override
    protected DumpSession openSession(DBConfig config, DumpSettings settings) throws SQLException {
        Connection connection = openReadOnly(config, settings);
        try {
            String snapshot = null;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT pg_export_snapshot()")) {
                if (rs.next()) snapshot = rs.getString(1);
            } catch (SQLException e) {
                // exporting is not possible on every server (e.g. some hot standbys); fall back to per-worker snapshots
                log.warn("Could not export snapshot for {}, workers will use their own snapshot: {}", config.dbName(), e.getMessage());
                connection.rollback();
            }
            return new Session(config, settings, connection, snapshot);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static Connection openReadOnly(DBConfig config, DumpSettings settings) throws SQLException {
        Connection connection = DbConnectionFactory.open(config, settings.connectTimeoutSeconds());
        try {
            try (Statement st = connection.createStatement()) {
                st.execute("SET statement_timeout = 0");
                st.execute("SET idle_in_transaction_session_timeout = 0");
            }
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) connection.rollback();
        } catch (SQLException ignored) {
            // connection may already be broken; closing below is what matters
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close connection cleanly", e);
        }
    }

    private static final class Session implements DumpSession {
        private final DBConfig config;
        private final DumpSettings settings;
        private final Connection connection;
        private final String snapshot;
        private List<String> tables;

        private Session(DBConfig config, DumpSettings settings, Connection connection, String snapshot) {
            this.config = config;
            this.settings = settings;
            this.connection = connection;
            this.snapshot = snapshot;
        }

        @Override
        public List<String> tables() throws SQLException {
            if (tables != null) return tables;
            List<String> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind = 'r' AND %s
                    ORDER BY c.relname""".formatted(NOT_EXTENSION_MEMBER))) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(rs.getString(1));
                }
            }
            tables = List.copyOf(result);
            return tables;
        }

        @Override
        public String fileHeader() {
            return """
                    SET client_encoding = 'UTF8';
                    SET standard_conforming_strings = on;
                    SET check_function_bodies = false;
                    SET search_path = public, pg_catalog;

                    """;
        }

        @Override
        public void writePreData(Writer w) throws SQLException, IOException {
            w.write("-- Extensions\n");
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT extname FROM pg_extension WHERE extname <> 'plpgsql' ORDER BY oid")) {
                while (rs.next()) w.write("CREATE EXTENSION IF NOT EXISTS " + pgIdent(rs.getString(1)) + ";\n");
            }

            w.write("\n-- Enum types\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT t.typname, string_agg(quote_literal(e.enumlabel), ', ' ORDER BY e.enumsortorder)
                    FROM pg_type t
                    JOIN pg_enum e ON e.enumtypid = t.oid
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ?
                      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = t.oid AND d.deptype = 'e')
                    GROUP BY t.typname ORDER BY t.typname""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        w.write("CREATE TYPE " + pgIdent(rs.getString(1)) + " AS ENUM (" + rs.getString(2) + ");\n");
                    }
                }
            }

            w.write("\n-- Sequences (identity sequences are created with their tables)\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.relname, format_type(s.seqtypid, NULL), s.seqstart, s.seqincrement,
                           s.seqmin, s.seqmax, s.seqcache, s.seqcycle
                    FROM pg_sequence s
                    JOIN pg_class c ON c.oid = s.seqrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ?
                      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                                      WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                                        AND d.deptype IN ('i', 'e'))
                    ORDER BY c.relname""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        w.write("CREATE SEQUENCE IF NOT EXISTS %s AS %s START WITH %d INCREMENT BY %d MINVALUE %d MAXVALUE %d CACHE %d%s;\n"
                                .formatted(pgIdent(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4),
                                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getBoolean(8) ? " CYCLE" : ""));
                    }
                }
            }
        }

        @Override
        public void writePostData(Writer w) throws SQLException, IOException {
            Set<String> dumped = new HashSet<>(tables());

            w.write("-- Indexes\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT i.tablename, i.indexdef
                    FROM pg_indexes i
                    WHERE i.schemaname = ?
                      AND NOT EXISTS (SELECT 1 FROM pg_constraint c
                                      WHERE c.contype IN ('p', 'u', 'x')
                                        AND c.conindid = (quote_ident(i.schemaname) || '.' || quote_ident(i.indexname))::regclass)
                    ORDER BY i.tablename, i.indexname""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (dumped.contains(rs.getString(1))) w.write(rs.getString(2) + ";\n");
                    }
                }
            }

            w.write("\n-- Foreign keys\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT cl.relname, c.conname, pg_get_constraintdef(c.oid)
                    FROM pg_constraint c
                    JOIN pg_class cl ON cl.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    WHERE n.nspname = ? AND c.contype = 'f'
                    ORDER BY cl.relname, c.conname""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!dumped.contains(rs.getString(1))) continue;
                        w.write("ALTER TABLE " + pgIdent(rs.getString(1)) + " ADD CONSTRAINT " + pgIdent(rs.getString(2))
                                + " " + rs.getString(3) + ";\n");
                    }
                }
            }

            w.write("\n-- Sequence ownership\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT s.relname, t.relname, a.attname
                    FROM pg_depend d
                    JOIN pg_class s ON s.oid = d.objid AND s.relkind = 'S'
                    JOIN pg_class t ON t.oid = d.refobjid
                    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
                    JOIN pg_namespace n ON n.oid = s.relnamespace
                    WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                      AND d.deptype = 'a' AND n.nspname = ?
                    ORDER BY s.relname""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!dumped.contains(rs.getString(2))) continue;
                        w.write("ALTER SEQUENCE " + pgIdent(rs.getString(1)) + " OWNED BY "
                                + pgIdent(rs.getString(2)) + "." + pgIdent(rs.getString(3)) + ";\n");
                    }
                }
            }

            w.write("\n-- Sequence values\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT s.sequencename, s.last_value
                    FROM pg_sequences s
                    JOIN pg_class c ON c.relname = s.sequencename
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.schemaname
                    WHERE s.schemaname = ? AND s.last_value IS NOT NULL AND %s
                    ORDER BY s.sequencename""".formatted(NOT_EXTENSION_MEMBER))) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        w.write("SELECT pg_catalog.setval(" + pgString(pgIdent(rs.getString(1))) + ", " + rs.getLong(2) + ", true);\n");
                    }
                }
            }

            w.write("\n-- Views\n");
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.relname, c.relkind, pg_get_viewdef(c.oid, true)
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind IN ('v', 'm') AND %s
                    ORDER BY c.oid""".formatted(NOT_EXTENSION_MEMBER))) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String body = rs.getString(3).strip();
                        if (body.endsWith(";")) body = body.substring(0, body.length() - 1);
                        String kind = "m".equals(rs.getString(2)) ? "MATERIALIZED VIEW" : "VIEW";
                        w.write("CREATE " + kind + " " + pgIdent(rs.getString(1)) + " AS\n" + body + ";\n\n");
                    }
                }
            }
        }

        @Override
        public TableDumper openWorker() throws SQLException {
            Connection worker = openReadOnly(config, settings);
            if (snapshot != null) {
                try (Statement st = worker.createStatement()) {
                    st.execute("SET TRANSACTION SNAPSHOT " + pgString(snapshot));
                } catch (SQLException | RuntimeException e) {
                    closeQuietly(worker);
                    throw e;
                }
            }
            return new Worker(worker, settings);
        }

        @Override
        public void close() {
            closeQuietly(connection);
        }
    }

    private record Column(String name, String type, boolean notNull, String identity, String generated, String defaultExpr) {
        boolean insertable() {
            return generated.isEmpty();
        }
    }

    private static final class Worker implements TableDumper {
        private final Connection connection;
        private final DumpSettings settings;

        private Worker(Connection connection, DumpSettings settings) {
            this.connection = connection;
            this.settings = settings;
        }

        @Override
        public long dumpTable(String table, Writer w) throws SQLException, IOException {
            String qualified = pgIdent(SCHEMA) + "." + pgIdent(table);
            List<Column> columns = columns(qualified);
            if (columns.isEmpty()) throw new SQLException("No columns found for table " + table);

            w.write(createTable(table, qualified, columns));
            w.write("\n\n");

            List<Column> insertable = columns.stream().filter(Column::insertable).toList();
            if (insertable.isEmpty()) return 0;

            boolean overridingIdentity = insertable.stream().anyMatch(c -> "a".equals(c.identity()));
            String columnList = String.join(", ", insertable.stream().map(c -> pgIdent(c.name())).toList());
            String insertPrefix = "INSERT INTO " + pgIdent(table) + " (" + columnList + ")"
                    + (overridingIdentity ? " OVERRIDING SYSTEM VALUE" : "") + " VALUES\n";

            long rows = 0;
            try (Statement st = connection.createStatement()) {
                st.setFetchSize(settings.fetchSize());
                try (ResultSet rs = st.executeQuery("SELECT " + columnList + " FROM " + qualified)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int count = meta.getColumnCount();
                    int[] types = new int[count];
                    for (int i = 0; i < count; i++) types[i] = meta.getColumnType(i + 1);

                    int inBatch = 0;
                    StringBuilder row = new StringBuilder(256);
                    while (rs.next()) {
                        row.setLength(0);
                        row.append(inBatch == 0 ? insertPrefix : ",\n").append('(');
                        for (int i = 1; i <= count; i++) {
                            if (i > 1) row.append(", ");
                            appendValue(row, rs, i, types[i - 1]);
                        }
                        row.append(')');
                        w.write(row.toString());
                        rows++;
                        if (++inBatch >= settings.insertBatchRows()) {
                            w.write(";\n");
                            inBatch = 0;
                        }
                    }
                    if (inBatch > 0) w.write(";\n");
                }
            }
            return rows;
        }

        private static void appendValue(StringBuilder sb, ResultSet rs, int index, int jdbcType) throws SQLException {
            if (SqlText.isBinary(jdbcType)) {
                byte[] bytes = rs.getBytes(index);
                if (bytes == null) sb.append("NULL");
                else sb.append("'\\x").append(SqlText.hex(bytes)).append('\'');
                return;
            }
            String value = rs.getString(index);
            if (value == null) sb.append("NULL");
            else if (SqlText.isNumeric(jdbcType) && SqlText.isPlainNumber(value)) sb.append(value);
            else sb.append(pgString(value));
        }

        private List<Column> columns(String qualified) throws SQLException {
            List<Column> columns = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT a.attname,
                           pg_catalog.format_type(a.atttypid, a.atttypmod),
                           a.attnotnull,
                           a.attidentity::text,
                           a.attgenerated::text,
                           pg_get_expr(ad.adbin, ad.adrelid)
                    FROM pg_attribute a
                    LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
                    WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY a.attnum""")) {
                ps.setString(1, qualified);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        columns.add(new Column(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                                nullToEmpty(rs.getString(4)), nullToEmpty(rs.getString(5)), rs.getString(6)));
                    }
                }
            }
            return columns;
        }

        private String createTable(String table, String qualified, List<Column> columns) throws SQLException {
            List<String> parts = new ArrayList<>();
            for (Column c : columns) {
                StringBuilder def = new StringBuilder(pgIdent(c.name())).append(' ').append(c.type());
                if (!c.generated().isEmpty() && c.defaultExpr() != null) {
                    def.append(" GENERATED ALWAYS AS (").append(c.defaultExpr()).append(") STORED");
                } else if ("a".equals(c.identity())) {
                    def.append(" GENERATED ALWAYS AS IDENTITY");
                } else if ("d".equals(c.identity())) {
                    def.append(" GENERATED BY DEFAULT AS IDENTITY");
                } else if (c.defaultExpr() != null) {
                    def.append(" DEFAULT ").append(c.defaultExpr());
                }
                if (c.notNull()) def.append(" NOT NULL");
                parts.add(def.toString());
            }

            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT conname, pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE conrelid = ?::regclass AND contype IN ('p', 'u', 'c', 'x')
                    ORDER BY CASE contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 WHEN 'x' THEN 2 ELSE 3 END, conname""")) {
                ps.setString(1, qualified);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) parts.add("CONSTRAINT " + pgIdent(rs.getString(1)) + " " + rs.getString(2));
                }
            }
            return "CREATE TABLE " + pgIdent(table) + " (\n  " + String.join(",\n  ", parts) + "\n);";
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        @Override
        public void close() {
            closeQuietly(connection);
        }
    }
}
