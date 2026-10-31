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
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Logical dump of the connecting user's Oracle schema, restorable with SQL*Plus or SQLcl.
 * <p>
 * DDL comes from DBMS_METADATA with storage clauses and the schema prefix removed, so the dump
 * loads into any schema. When the system change number is readable every table is queried
 * {@code AS OF SCN} that number, giving one consistent point in time across all workers.
 */
@Slf4j
@Component
class OracleDumpStrategy extends SqlDumpStrategy {
    /** SQL*Plus rejects lines longer than 2499 characters; literals are split well below that. */
    private static final int CHUNK_CHARS = 1000;
    private static final int RAW_CHUNK_BYTES = 1000;
    private static final int INLINE_CLOB_CHARS = 4000;
    private static final int INLINE_BLOB_BYTES = 2000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    private static final DateTimeFormatter TSTZ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS xxx");
    private static final Pattern ALWAYS_IDENTITY = Pattern.compile("GENERATED\\s+ALWAYS\\s+AS\\s+IDENTITY");

    @Override
    public Set<DBType> dbTypes() {
        return Set.of(DBType.ORACLE);
    }

    @Override
    protected String restoreInstructions() {
        return """
                for f in 00-pre-data.sql tables/*.sql 90-post-data.sql; do
                  sqlplus -s <user>/<password>@//<host>:<port>/<service> < "$f" || break
                done
                """;
    }

    @Override
    protected DumpSession openSession(DBConfig config, DumpSettings settings) throws SQLException {
        Connection connection = openConnection(config, settings);
        try {
            String owner = single(connection, "SELECT USER FROM dual");
            Long scn = null;
            for (String query : List.of("SELECT current_scn FROM v$database",
                    "SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER FROM dual")) {
                try {
                    scn = Long.parseLong(single(connection, query));
                    break;
                } catch (SQLException | NumberFormatException e) {
                    log.debug("SCN lookup '{}' failed: {}", query, e.getMessage());
                }
            }
            if (scn == null) {
                log.warn("Cannot read the SCN as {}; tables are read individually and may not be mutually consistent. "
                        + "Grant SELECT ON v_$database or EXECUTE ON DBMS_FLASHBACK for a consistent dump", owner);
            }
            return new Session(config, settings, connection, owner, scn);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static Connection openConnection(DBConfig config, DumpSettings settings) throws SQLException {
        Connection connection = DbConnectionFactory.open(config, settings.connectTimeoutSeconds());
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER SESSION SET TIME_ZONE = '+00:00'");
            st.execute("""
                    BEGIN
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', FALSE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'TABLESPACE', FALSE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'REF_CONSTRAINTS', FALSE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE);
                      DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE);
                    END;""");
            return connection;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close connection cleanly", e);
        }
    }

    static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** String literal split into short concatenated pieces to respect the SQL*Plus line limit. */
    static String literal(String value, boolean national) {
        if (value.isEmpty()) return "NULL"; // Oracle treats '' as NULL anyway
        String quote = national ? "N'" : "'";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        int start = 0;
        while (start < value.length()) {
            int end = chunkEnd(value, start);
            if (start > 0) sb.append(" ||\n");
            sb.append(quote).append(value.substring(start, end).replace("'", "''")).append('\'');
            start = end;
        }
        return sb.toString();
    }

    /** End of the next chunk; never splits a surrogate pair, which would not survive UTF-8 encoding. */
    static int chunkEnd(CharSequence value, int start) {
        int end = Math.min(value.length(), start + CHUNK_CHARS);
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return end;
    }

    private static String rawLiteral(byte[] bytes) {
        String hex = HexFormat.of().formatHex(bytes);
        StringBuilder sb = new StringBuilder("HEXTORAW(");
        for (int i = 0; i < hex.length(); i += CHUNK_CHARS) {
            if (i > 0) sb.append(" ||\n");
            sb.append('\'').append(hex, i, Math.min(hex.length(), i + CHUNK_CHARS)).append('\'');
        }
        return sb.append(')').toString();
    }

    private String stripOwner(String ddl, String owner) {
        return ddl.replace(ident(owner) + ".", "").strip();
    }

    private final class Session implements DumpSession {
        private final DBConfig config;
        private final DumpSettings settings;
        private final Connection connection;
        private final String owner;
        private final Long scn;
        private List<String> tables;

        private Session(DBConfig config, DumpSettings settings, Connection connection, String owner, Long scn) {
            this.config = config;
            this.settings = settings;
            this.connection = connection;
            this.owner = owner;
            this.scn = scn;
        }

        @Override
        public List<String> tables() throws SQLException {
            if (tables != null) return tables;
            List<String> result = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT table_name FROM user_tables
                         WHERE nested = 'NO' AND secondary = 'N' AND temporary = 'N' AND dropped = 'NO'
                           AND (iot_type IS NULL OR iot_type = 'IOT')
                           AND table_name NOT IN (SELECT mview_name FROM user_mviews)
                           AND table_name NOT IN (SELECT table_name FROM user_external_tables)
                         ORDER BY table_name""")) {
                while (rs.next()) result.add(rs.getString(1));
            }
            tables = List.copyOf(result);
            return tables;
        }

        @Override
        public String fileHeader() {
            return """
                    SET DEFINE OFF
                    SET SQLBLANKLINES ON
                    SET FEEDBACK OFF
                    WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
                    ALTER SESSION SET TIME_ZONE = '+00:00';

                    """;
        }

        @Override
        public void writePreData(Writer w) throws SQLException, IOException {
            w.write("-- Sequences (DBMS_METADATA starts them at their next value)\n");
            for (String name : names("""
                    SELECT sequence_name FROM user_sequences
                    WHERE sequence_name NOT IN (SELECT sequence_name FROM user_tab_identity_cols)
                    ORDER BY sequence_name""")) {
                w.write(ddl("SEQUENCE", name) + "\n\n");
            }
        }

        @Override
        public void writePostData(Writer w) throws SQLException, IOException {
            Set<String> dumped = Set.copyOf(tables());

            w.write("-- Indexes\n");
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT i.index_name, i.table_name FROM user_indexes i
                         WHERE i.generated = 'N' AND i.index_type NOT IN ('LOB', 'IOT - TOP')
                           AND NOT EXISTS (SELECT 1 FROM user_constraints c WHERE c.index_name = i.index_name)
                         ORDER BY i.table_name, i.index_name""")) {
                List<String> indexes = new ArrayList<>();
                while (rs.next()) if (dumped.contains(rs.getString(2))) indexes.add(rs.getString(1));
                for (String index : indexes) w.write(ddl("INDEX", index) + "\n\n");
            }

            w.write("-- Foreign keys\n");
            for (String name : names("SELECT constraint_name FROM user_constraints WHERE constraint_type = 'R' ORDER BY table_name, constraint_name")) {
                w.write(ddl("REF_CONSTRAINT", name) + "\n\n");
            }

            w.write("-- Identity columns: restore generation mode and move past the loaded values\n");
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT table_name, column_name, generation_type FROM user_tab_identity_cols ORDER BY table_name")) {
                while (rs.next()) {
                    if (!dumped.contains(rs.getString(1))) continue;
                    w.write("ALTER TABLE %s MODIFY (%s GENERATED %s AS IDENTITY (START WITH LIMIT VALUE));\n"
                            .formatted(ident(rs.getString(1)), ident(rs.getString(2)), rs.getString(3)));
                }
            }

            w.write("\n-- Program units\n");
            for (String type : List.of("FUNCTION", "PROCEDURE", "PACKAGE")) {
                for (String name : names("SELECT object_name FROM user_objects WHERE object_type = '%s' ORDER BY object_name".formatted(type))) {
                    w.write(ddl(type, name) + "\n\n");
                }
            }

            w.write("-- Views\n");
            for (String name : names("SELECT view_name FROM user_views ORDER BY view_name")) {
                w.write(ddl("VIEW", name) + "\n\n");
            }

            w.write("-- Triggers\n");
            for (String name : names("SELECT trigger_name FROM user_triggers WHERE base_object_type = 'TABLE' ORDER BY trigger_name")) {
                w.write(ddl("TRIGGER", name) + "\n\n");
            }
            w.write("COMMIT;\n");
        }

        private List<String> names(String sql) throws SQLException {
            List<String> names = new ArrayList<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) names.add(rs.getString(1));
            }
            return names;
        }

        private String ddl(String type, String name) throws SQLException {
            return OracleDumpStrategy.this.ddl(connection, type, name, owner);
        }

        @Override
        public TableDumper openWorker() throws SQLException {
            return new Worker(openConnection(config, settings), settings, owner, scn);
        }

        @Override
        public void close() {
            closeQuietly(connection);
        }
    }

    private String ddl(Connection connection, String type, String name, String owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?) FROM dual")) {
            ps.setString(1, type);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? stripOwner(rs.getString(1), owner) : "";
            }
        }
    }

    private record Column(String name, String dataType) {
        String kind() {
            String t = dataType.toUpperCase(Locale.ROOT);
            if (t.startsWith("TIMESTAMP") && t.contains("WITH TIME ZONE")) return "TSTZ";
            if (t.startsWith("TIMESTAMP")) return "TS";
            if (t.startsWith("INTERVAL")) return "TEXT";
            return switch (t) {
                case "NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE" -> "NUMBER";
                case "DATE" -> "TS";
                case "CHAR", "VARCHAR2", "LONG", "ROWID", "UROWID" -> "TEXT";
                case "NCHAR", "NVARCHAR2" -> "NTEXT";
                case "CLOB" -> "CLOB";
                case "NCLOB" -> "NCLOB";
                case "BLOB" -> "BLOB";
                case "RAW", "LONG RAW" -> "RAW";
                default -> "UNSUPPORTED";
            };
        }
    }

    private final class Worker implements TableDumper {
        private final Connection connection;
        private final DumpSettings settings;
        private final String owner;
        private final Long scn;

        private Worker(Connection connection, DumpSettings settings, String owner, Long scn) {
            this.connection = connection;
            this.settings = settings;
            this.owner = owner;
            this.scn = scn;
        }

        @Override
        public long dumpTable(String table, Writer w) throws SQLException, IOException {
            // load with BY DEFAULT identity so original key values can be inserted; post-data restores ALWAYS
            String tableDdl = ALWAYS_IDENTITY.matcher(ddl(connection, "TABLE", table, owner))
                    .replaceAll("GENERATED BY DEFAULT AS IDENTITY");
            w.write(tableDdl + "\n\n");

            List<Column> columns = columns(table);
            List<Column> unsupported = columns.stream().filter(c -> "UNSUPPORTED".equals(c.kind())).toList();
            if (!unsupported.isEmpty()) {
                throw new SQLException("Unsupported column type(s): " + unsupported.stream()
                        .map(c -> c.name() + " " + c.dataType()).toList());
            }
            if (columns.isEmpty()) return 0;

            String columnList = String.join(", ", columns.stream().map(c -> ident(c.name())).toList());
            String from = ident(table) + (scn == null ? "" : " AS OF SCN " + scn);
            long rows = 0;
            try (Statement st = connection.createStatement()) {
                st.setFetchSize(settings.fetchSize());
                try (ResultSet rs = st.executeQuery("SELECT " + columnList + " FROM " + from)) {
                    StringBuilder sb = new StringBuilder(512);
                    while (rs.next()) {
                        sb.setLength(0);
                        writeRow(sb, rs, table, columns, columnList);
                        w.write(sb.toString());
                        rows++;
                        if (rows % settings.insertBatchRows() == 0) w.write("COMMIT;\n");
                    }
                }
            }
            w.write("COMMIT;\n");
            return rows;
        }

        private void writeRow(StringBuilder sb, ResultSet rs, String table, List<Column> columns, String columnList)
                throws SQLException, IOException {
            Map<String, Object> largeLobs = new LinkedHashMap<>();
            List<String> values = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                int index = i + 1;
                switch (column.kind()) {
                    case "NUMBER" -> {
                        String v = rs.getString(index);
                        values.add(v == null ? "NULL" : SqlText.isPlainNumber(v) ? v : literal(v, false));
                    }
                    case "TS" -> {
                        LocalDateTime v = rs.getObject(index, LocalDateTime.class);
                        values.add(v == null ? "NULL" : "TO_TIMESTAMP('" + TS.format(v) + "', 'YYYY-MM-DD HH24:MI:SS.FF9')");
                    }
                    case "TSTZ" -> {
                        OffsetDateTime v = rs.getObject(index, OffsetDateTime.class);
                        values.add(v == null ? "NULL" : "TO_TIMESTAMP_TZ('" + TSTZ.format(v) + "', 'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM')");
                    }
                    case "TEXT", "NTEXT" -> {
                        String v = rs.getString(index);
                        values.add(v == null ? "NULL" : literal(v, "NTEXT".equals(column.kind())));
                    }
                    case "RAW" -> {
                        byte[] v = rs.getBytes(index);
                        values.add(v == null || v.length == 0 ? "NULL" : rawLiteral(v));
                    }
                    case "CLOB", "NCLOB" -> {
                        Clob clob = rs.getClob(index);
                        if (clob == null) {
                            values.add("NULL");
                        } else if (clob.length() <= INLINE_CLOB_CHARS) {
                            String text = clob.getSubString(1, (int) clob.length());
                            values.add(text.isEmpty() ? "EMPTY_CLOB()" : "TO_CLOB(" + literal(text, "NCLOB".equals(column.kind())) + ")");
                        } else {
                            largeLobs.put(column.name(), clob);
                            values.add("EMPTY_CLOB()");
                        }
                    }
                    case "BLOB" -> {
                        Blob blob = rs.getBlob(index);
                        if (blob == null) {
                            values.add("NULL");
                        } else if (blob.length() <= INLINE_BLOB_BYTES) {
                            byte[] bytes = blob.getBytes(1, (int) blob.length());
                            values.add(bytes.length == 0 ? "EMPTY_BLOB()" : "TO_BLOB(" + rawLiteral(bytes) + ")");
                        } else {
                            largeLobs.put(column.name(), blob);
                            values.add("EMPTY_BLOB()");
                        }
                    }
                    default -> throw new SQLException("Unsupported column " + column);
                }
            }

            String insert = "INSERT INTO " + ident(table) + " (" + columnList + ") VALUES (\n" + String.join(",\n", values) + ")";
            if (largeLobs.isEmpty()) {
                sb.append(insert).append(";\n");
                return;
            }

            // large LOBs: insert empty locators, then append the content in chunks from PL/SQL
            sb.append("DECLARE\n");
            int n = 0;
            for (Map.Entry<String, Object> lob : largeLobs.entrySet()) {
                sb.append("  l").append(n++).append(lob.getValue() instanceof Blob ? " BLOB;\n" : " CLOB;\n");
            }
            sb.append("BEGIN\n").append(insert).append("\nRETURNING ")
                    .append(String.join(", ", largeLobs.keySet().stream().map(OracleDumpStrategy::ident).toList()))
                    .append(" INTO ");
            List<String> vars = new ArrayList<>();
            for (int i = 0; i < largeLobs.size(); i++) vars.add("l" + i);
            sb.append(String.join(", ", vars)).append(";\n");
            n = 0;
            for (Object lob : largeLobs.values()) {
                String var = "l" + n++;
                if (lob instanceof Blob blob) {
                    try (InputStream in = blob.getBinaryStream()) {
                        byte[] buffer = new byte[RAW_CHUNK_BYTES];
                        int read;
                        while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                            byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                            sb.append("  DBMS_LOB.WRITEAPPEND(").append(var).append(", ").append(read).append(", ")
                                    .append(rawLiteral(chunk)).append(");\n");
                        }
                    }
                } else {
                    try (Reader in = ((Clob) lob).getCharacterStream()) {
                        char[] buffer = new char[CHUNK_CHARS];
                        StringBuilder pending = new StringBuilder();
                        int read;
                        while ((read = in.read(buffer)) > 0 || !pending.isEmpty()) {
                            if (read > 0) pending.append(buffer, 0, read);
                            // hold back a trailing high surrogate until its pair arrives with the next read
                            int end = pending.length();
                            if (read > 0 && Character.isHighSurrogate(pending.charAt(end - 1))) end--;
                            if (end == 0) continue;
                            String chunk = pending.substring(0, end);
                            pending.delete(0, end);
                            sb.append("  DBMS_LOB.WRITEAPPEND(").append(var).append(", ").append(chunk.length()).append(", ")
                                    .append(literal(chunk, false)).append(");\n");
                        }
                    }
                }
            }
            sb.append("END;\n/\n");
        }

        private List<Column> columns(String table) throws SQLException {
            List<Column> columns = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT column_name, data_type FROM user_tab_cols
                    WHERE table_name = ? AND hidden_column = 'NO' AND virtual_column = 'NO'
                    ORDER BY column_id""")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) columns.add(new Column(rs.getString(1), rs.getString(2)));
                }
            }
            return columns;
        }

        @Override
        public void close() {
            closeQuietly(connection);
        }
    }
}
