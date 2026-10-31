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
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logical dump of a Microsoft SQL Server database (all user schemas), restorable with {@code sqlcmd}.
 * <p>
 * When the database allows snapshot isolation every connection reads a transactionally consistent
 * view; otherwise each table is read under READ COMMITTED. Foreign keys, indexes, views, routines
 * and triggers are created after the data.
 */
@Slf4j
@Component
class MssqlDumpStrategy extends SqlDumpStrategy {
    private static final int TRANSACTION_SNAPSHOT = 0x1000; // SQLServerConnection.TRANSACTION_SNAPSHOT
    private static final int MAX_ROWS_PER_INSERT = 1000;   // SQL Server limit for a VALUES list
    private static final int TYPE_DATETIMEOFFSET = -155;
    private static final Set<String> BYTE_TYPES = Set.of("geography", "geometry", "hierarchyid");

    @Override
    public Set<DBType> dbTypes() {
        return Set.of(DBType.MSSQL);
    }

    @Override
    protected String restoreInstructions() {
        return """
                for f in 00-pre-data.sql tables/*.sql 90-post-data.sql; do
                  sqlcmd -S <host>,<port> -d <db> -U <user> -P <password> -b -i "$f" || break
                done
                """;
    }

    @Override
    protected DumpSession openSession(DBConfig config, DumpSettings settings) throws SQLException {
        Connection connection = DbConnectionFactory.open(config, settings.connectTimeoutSeconds());
        try {
            boolean snapshot = "1".equals(single(connection,
                    "SELECT snapshot_isolation_state FROM sys.databases WHERE name = DB_NAME()"));
            if (!snapshot) {
                log.warn("Snapshot isolation is off for {}; tables are read under READ COMMITTED and may not be mutually consistent. "
                        + "Enable it with ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON", config.dbName());
            }
            return new Session(config, settings, prepare(connection, snapshot), snapshot);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static Connection prepare(Connection connection, boolean snapshot) throws SQLException {
        if (snapshot) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(TRANSACTION_SNAPSHOT);
        }
        return connection;
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
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

    static String ident(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }

    static String nstring(String value) {
        return "N'" + value.replace("'", "''") + "'";
    }

    private record TableRef(String schema, String name) {
        String qualified() {
            return ident(schema) + "." + ident(name);
        }

        /** Argument for OBJECT_ID(); quoted so odd names resolve. */
        String objectIdArg() {
            return qualified();
        }
    }

    private static final class Session implements DumpSession {
        private final DBConfig config;
        private final DumpSettings settings;
        private final Connection connection;
        private final boolean snapshot;
        private final Map<String, TableRef> tableRefs = new LinkedHashMap<>();

        private Session(DBConfig config, DumpSettings settings, Connection connection, boolean snapshot) {
            this.config = config;
            this.settings = settings;
            this.connection = connection;
            this.snapshot = snapshot;
        }

        @Override
        public List<String> tables() throws SQLException {
            if (tableRefs.isEmpty()) {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("""
                             SELECT s.name, t.name FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id
                             WHERE t.is_ms_shipped = 0 AND t.temporal_type <> 1
                             ORDER BY s.name, t.name""")) {
                    while (rs.next()) {
                        TableRef ref = new TableRef(rs.getString(1), rs.getString(2));
                        tableRefs.put(ref.schema() + "." + ref.name(), ref);
                    }
                }
            }
            return List.copyOf(tableRefs.keySet());
        }

        @Override
        public String fileHeader() {
            return """
                    SET NOCOUNT ON;
                    SET XACT_ABORT ON;
                    SET DATEFORMAT ymd;
                    SET QUOTED_IDENTIFIER ON;
                    SET ANSI_NULLS ON;
                    GO

                    """;
        }

        @Override
        public void writePreData(Writer w) throws SQLException, IOException {
            w.write("-- Schemas\n");
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT name FROM sys.schemas
                         WHERE schema_id > 4 AND schema_id < 16384 AND name NOT IN ('guest', 'sys', 'INFORMATION_SCHEMA')
                         ORDER BY name""")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    w.write("IF SCHEMA_ID(" + nstring(name) + ") IS NULL EXEC(" + nstring("CREATE SCHEMA " + ident(name)) + ");\nGO\n");
                }
            }

            w.write("\n-- Sequences (positioned at their next value)\n");
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT s.name, sc.name, TYPE_NAME(s.user_type_id), s.precision,
                                CAST(s.start_value AS bigint), CAST(s.increment AS bigint),
                                CAST(s.minimum_value AS bigint), CAST(s.maximum_value AS bigint),
                                s.is_cycling, CAST(s.last_used_value AS bigint)
                         FROM sys.sequences s JOIN sys.schemas sc ON sc.schema_id = s.schema_id
                         ORDER BY sc.name, s.name""")) {
                while (rs.next()) {
                    String type = rs.getString(3);
                    if ("decimal".equals(type) || "numeric".equals(type)) type += "(" + rs.getInt(4) + ", 0)";
                    long increment = rs.getLong(6);
                    long lastUsed = rs.getLong(10);
                    long start = rs.wasNull() ? rs.getLong(5) : lastUsed + increment;
                    w.write("CREATE SEQUENCE %s.%s AS %s START WITH %d INCREMENT BY %d MINVALUE %d MAXVALUE %d %s;\nGO\n"
                            .formatted(ident(rs.getString(2)), ident(rs.getString(1)), type, start, increment,
                                    rs.getLong(7), rs.getLong(8), rs.getBoolean(9) ? "CYCLE" : "NO CYCLE"));
                }
            }
        }

        @Override
        public void writePostData(Writer w) throws SQLException, IOException {
            writeIndexes(w);
            writeForeignKeys(w);

            w.write("\n-- Routines, views and triggers\n");
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT m.definition
                         FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id
                         WHERE o.is_ms_shipped = 0 AND o.type IN ('FN', 'IF', 'TF', 'P', 'V', 'TR')
                         ORDER BY CASE o.type WHEN 'TR' THEN 2 WHEN 'V' THEN 1 ELSE 0 END, o.create_date, o.object_id""")) {
                while (rs.next()) {
                    String definition = rs.getString(1);
                    if (definition == null) continue; // encrypted modules expose no text
                    w.write(definition.strip() + "\nGO\n\n");
                }
            }
        }

        private void writeIndexes(Writer w) throws SQLException, IOException {
            w.write("-- Indexes\n");
            record Idx(String schema, String table, String name, String type, boolean unique, String filter,
                       List<String> keys, List<String> includes) {
            }
            Map<String, Idx> indexes = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT s.name, t.name, i.name, i.type_desc, i.is_unique, i.filter_definition,
                                c.name, ic.is_included_column, ic.is_descending_key
                         FROM sys.indexes i
                         JOIN sys.tables t ON t.object_id = i.object_id
                         JOIN sys.schemas s ON s.schema_id = t.schema_id
                         JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                         JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                         WHERE t.is_ms_shipped = 0 AND i.is_primary_key = 0 AND i.is_unique_constraint = 0
                           AND i.type IN (1, 2) AND i.is_hypothetical = 0
                         ORDER BY s.name, t.name, i.name, ic.is_included_column, ic.key_ordinal, ic.index_column_id""")) {
                while (rs.next()) {
                    String key = rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3);
                    Idx idx = indexes.computeIfAbsent(key, k -> {
                        try {
                            return new Idx(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                    rs.getBoolean(5), rs.getString(6), new ArrayList<>(), new ArrayList<>());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    String column = ident(rs.getString(7));
                    if (rs.getBoolean(8)) idx.includes().add(column);
                    else idx.keys().add(column + (rs.getBoolean(9) ? " DESC" : " ASC"));
                }
            }
            for (Idx idx : indexes.values()) {
                StringBuilder sb = new StringBuilder("CREATE ");
                if (idx.unique()) sb.append("UNIQUE ");
                sb.append(idx.type()).append(" INDEX ").append(ident(idx.name())).append(" ON ")
                        .append(ident(idx.schema())).append('.').append(ident(idx.table()))
                        .append(" (").append(String.join(", ", idx.keys())).append(')');
                if (!idx.includes().isEmpty()) sb.append(" INCLUDE (").append(String.join(", ", idx.includes())).append(')');
                if (idx.filter() != null) sb.append(" WHERE ").append(idx.filter());
                w.write(sb.append(";\nGO\n").toString());
            }
        }

        private void writeForeignKeys(Writer w) throws SQLException, IOException {
            w.write("\n-- Foreign keys\n");
            record Fk(String schema, String table, String name, String refSchema, String refTable,
                      List<String> columns, List<String> refColumns, String onDelete, String onUpdate) {
            }
            Map<String, Fk> fks = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT ps.name, pt.name, fk.name, rs.name, rt.name, pc.name, rc.name,
                                fk.delete_referential_action_desc, fk.update_referential_action_desc
                         FROM sys.foreign_keys fk
                         JOIN sys.tables pt ON pt.object_id = fk.parent_object_id
                         JOIN sys.schemas ps ON ps.schema_id = pt.schema_id
                         JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
                         JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
                         JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                         JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
                         JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
                         ORDER BY ps.name, pt.name, fk.name, fkc.constraint_column_id""")) {
                while (rs.next()) {
                    String key = rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3);
                    Fk fk = fks.computeIfAbsent(key, k -> {
                        try {
                            return new Fk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                    new ArrayList<>(), new ArrayList<>(), rs.getString(8), rs.getString(9));
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    fk.columns().add(ident(rs.getString(6)));
                    fk.refColumns().add(ident(rs.getString(7)));
                }
            }
            for (Fk fk : fks.values()) {
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(ident(fk.schema())).append('.').append(ident(fk.table()))
                        .append(" ADD CONSTRAINT ").append(ident(fk.name()))
                        .append(" FOREIGN KEY (").append(String.join(", ", fk.columns())).append(") REFERENCES ")
                        .append(ident(fk.refSchema())).append('.').append(ident(fk.refTable()))
                        .append(" (").append(String.join(", ", fk.refColumns())).append(')');
                if (!"NO_ACTION".equals(fk.onDelete())) sb.append(" ON DELETE ").append(fk.onDelete().replace('_', ' '));
                if (!"NO_ACTION".equals(fk.onUpdate())) sb.append(" ON UPDATE ").append(fk.onUpdate().replace('_', ' '));
                w.write(sb.append(";\nGO\n").toString());
            }
        }

        @Override
        public TableDumper openWorker() throws SQLException {
            Connection worker = DbConnectionFactory.open(config, settings.connectTimeoutSeconds());
            try {
                return new Worker(prepare(worker, snapshot), settings, tableRefs);
            } catch (SQLException | RuntimeException e) {
                closeQuietly(worker);
                throw e;
            }
        }

        @Override
        public void close() {
            closeQuietly(connection);
        }
    }

    private record Column(String name, String typeName, int maxLength, int precision, int scale, boolean nullable,
                          boolean identity, long seed, long increment, boolean computed, String computedDefinition,
                          boolean persisted, String defaultName, String defaultDefinition, String collation,
                          boolean userDefinedType) {

        boolean insertable() {
            return !computed && !"timestamp".equals(typeName) && !"rowversion".equals(typeName);
        }

        String renderType() {
            if (userDefinedType) return ident(typeName);
            return switch (typeName) {
                case "varchar", "char", "varbinary", "binary" -> typeName + "(" + (maxLength == -1 ? "max" : maxLength) + ")";
                case "nvarchar", "nchar" -> typeName + "(" + (maxLength == -1 ? "max" : maxLength / 2) + ")";
                case "decimal", "numeric" -> typeName + "(" + precision + ", " + scale + ")";
                case "datetime2", "time", "datetimeoffset" -> typeName + "(" + scale + ")";
                case "float" -> typeName + "(" + precision + ")";
                default -> typeName;
            };
        }
    }

    private static final class Worker implements TableDumper {
        private final Connection connection;
        private final DumpSettings settings;
        private final Map<String, TableRef> tableRefs;

        private Worker(Connection connection, DumpSettings settings, Map<String, TableRef> tableRefs) {
            this.connection = connection;
            this.settings = settings;
            this.tableRefs = tableRefs;
        }

        @Override
        public long dumpTable(String table, Writer w) throws SQLException, IOException {
            TableRef ref = tableRefs.get(table);
            List<Column> columns = columns(ref);
            if (columns.isEmpty()) throw new SQLException("No columns found for table " + table);
            w.write(createTable(ref, columns));

            List<Column> insertable = columns.stream().filter(Column::insertable).toList();
            if (insertable.isEmpty()) return 0;
            boolean identityInsert = insertable.stream().anyMatch(Column::identity);
            String columnList = String.join(", ", insertable.stream().map(c -> ident(c.name())).toList());
            String insertPrefix = "INSERT INTO " + ref.qualified() + " (" + columnList + ") VALUES\n";
            int batchRows = Math.min(settings.insertBatchRows(), MAX_ROWS_PER_INSERT);

            if (identityInsert) w.write("SET IDENTITY_INSERT " + ref.qualified() + " ON;\nGO\n");
            long rows = 0;
            try (Statement st = connection.createStatement()) {
                st.setFetchSize(settings.fetchSize());
                try (ResultSet rs = st.executeQuery("SELECT " + columnList + " FROM " + ref.qualified())) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int count = meta.getColumnCount();
                    int[] types = new int[count];
                    boolean[] asBytes = new boolean[count];
                    for (int i = 0; i < count; i++) {
                        types[i] = meta.getColumnType(i + 1);
                        asBytes[i] = BYTE_TYPES.contains(insertable.get(i).typeName().toLowerCase(Locale.ROOT));
                    }

                    int inBatch = 0;
                    StringBuilder row = new StringBuilder(256);
                    while (rs.next()) {
                        row.setLength(0);
                        row.append(inBatch == 0 ? insertPrefix : ",\n").append('(');
                        for (int i = 1; i <= count; i++) {
                            if (i > 1) row.append(", ");
                            appendValue(row, rs, i, types[i - 1], asBytes[i - 1]);
                        }
                        row.append(')');
                        w.write(row.toString());
                        rows++;
                        if (++inBatch >= batchRows) {
                            w.write(";\nGO\n");
                            inBatch = 0;
                        }
                    }
                    if (inBatch > 0) w.write(";\nGO\n");
                }
            }
            if (identityInsert) w.write("SET IDENTITY_INSERT " + ref.qualified() + " OFF;\nGO\n");
            if (!connection.getAutoCommit()) connection.commit(); // ends the snapshot transaction for this table
            return rows;
        }

        private static void appendValue(StringBuilder sb, ResultSet rs, int index, int jdbcType, boolean asBytes)
                throws SQLException {
            if (asBytes || SqlText.isBinary(jdbcType)) {
                byte[] bytes = rs.getBytes(index);
                if (bytes == null) sb.append("NULL");
                else sb.append("0x").append(SqlText.hex(bytes));
                return;
            }
            if (jdbcType == Types.BIT || jdbcType == Types.BOOLEAN) {
                boolean value = rs.getBoolean(index);
                sb.append(rs.wasNull() ? "NULL" : value ? "1" : "0");
                return;
            }
            String value = rs.getString(index);
            if (value == null) sb.append("NULL");
            else if (SqlText.isNumeric(jdbcType) && SqlText.isPlainNumber(value)) sb.append(value);
            else if (jdbcType == TYPE_DATETIMEOFFSET) sb.append('\'').append(value).append('\'');
            else sb.append(nstring(value));
        }

        private List<Column> columns(TableRef ref) throws SQLException {
            List<Column> columns = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.name, TYPE_NAME(c.user_type_id), c.max_length, c.precision, c.scale, c.is_nullable,
                           c.is_identity, CAST(ic.seed_value AS bigint), CAST(ic.increment_value AS bigint),
                           c.is_computed, cc.definition, cc.is_persisted, dc.name, dc.definition, c.collation_name,
                           t.is_user_defined
                    FROM sys.columns c
                    JOIN sys.types t ON t.user_type_id = c.user_type_id
                    LEFT JOIN sys.identity_columns ic ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                    LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
                    LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
                    WHERE c.object_id = OBJECT_ID(?)
                    ORDER BY c.column_id""")) {
                ps.setString(1, ref.objectIdArg());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        columns.add(new Column(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                                rs.getBoolean(6), rs.getBoolean(7), rs.getLong(8), rs.getLong(9), rs.getBoolean(10),
                                rs.getString(11), rs.getBoolean(12), rs.getString(13), rs.getString(14), rs.getString(15),
                                rs.getBoolean(16)));
                    }
                }
            }
            return columns;
        }

        private String createTable(TableRef ref, List<Column> columns) throws SQLException {
            List<String> parts = new ArrayList<>();
            for (Column c : columns) {
                StringBuilder def = new StringBuilder(ident(c.name())).append(' ');
                if (c.computed()) {
                    def.append("AS ").append(c.computedDefinition());
                    if (c.persisted()) def.append(" PERSISTED");
                    parts.add(def.toString());
                    continue;
                }
                def.append(c.renderType());
                if (c.collation() != null) def.append(" COLLATE ").append(c.collation());
                if (c.identity()) def.append(" IDENTITY(").append(c.seed()).append(", ").append(c.increment()).append(')');
                def.append(c.nullable() ? " NULL" : " NOT NULL");
                if (c.defaultDefinition() != null) {
                    def.append(" CONSTRAINT ").append(ident(c.defaultName())).append(" DEFAULT ").append(c.defaultDefinition());
                }
                parts.add(def.toString());
            }

            // primary keys and unique constraints
            Map<String, StringBuilder> keys = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT kc.name, kc.type, i.type_desc, col.name, ic.is_descending_key
                    FROM sys.key_constraints kc
                    JOIN sys.indexes i ON i.object_id = kc.parent_object_id AND i.index_id = kc.unique_index_id
                    JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                    JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
                    WHERE kc.parent_object_id = OBJECT_ID(?) AND ic.is_included_column = 0
                    ORDER BY CASE kc.type WHEN 'PK' THEN 0 ELSE 1 END, kc.name, ic.key_ordinal""")) {
                ps.setString(1, ref.objectIdArg());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        StringBuilder sb = keys.get(name);
                        String column = ident(rs.getString(4)) + (rs.getBoolean(5) ? " DESC" : " ASC");
                        if (sb == null) {
                            String kind = "PK".equals(rs.getString(2).strip()) ? "PRIMARY KEY" : "UNIQUE";
                            keys.put(name, new StringBuilder("CONSTRAINT ").append(ident(name)).append(' ').append(kind)
                                    .append(' ').append(rs.getString(3)).append(" (").append(column));
                        } else {
                            sb.append(", ").append(column);
                        }
                    }
                }
            }
            keys.values().forEach(sb -> parts.add(sb.append(')').toString()));

            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT name, definition FROM sys.check_constraints
                    WHERE parent_object_id = OBJECT_ID(?) ORDER BY name""")) {
                ps.setString(1, ref.objectIdArg());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) parts.add("CONSTRAINT " + ident(rs.getString(1)) + " CHECK " + rs.getString(2));
                }
            }
            return "CREATE TABLE " + ref.qualified() + " (\n  " + String.join(",\n  ", parts) + "\n);\nGO\n\n";
        }

        @Override
        public void close() {
            closeQuietly(connection);
        }
    }
}
