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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.zurimate.appbackup.adapter.output.db.SqlText.mysqlIdent;
import static com.zurimate.appbackup.adapter.output.db.SqlText.mysqlString;

/**
 * Logical dump of a MySQL / MariaDB database.
 * <p>
 * Each connection reads inside its own {@code START TRANSACTION WITH CONSISTENT SNAPSHOT}.
 * MySQL cannot share one snapshot between connections, so with more than one worker the
 * tables are consistent individually; set {@code tableBatchesOf=1} for a fully consistent dump.
 * Rows are streamed, never paged with OFFSET.
 */
@Slf4j
@Component
class MysqlDumpStrategy extends SqlDumpStrategy {
    private static final Pattern DEFINER = Pattern.compile("DEFINER=`(?:[^`]|``)*`@`(?:[^`]|``)*`\\s*");
    private static final Pattern GENERATED = Pattern.compile("(?i)\\b(VIRTUAL|STORED|PERSISTENT)\\b");

    @Override
    public Set<DBType> dbTypes() {
        return Set.of(DBType.MYSQL, DBType.MARIADB);
    }

    @Override
    protected String restoreInstructions() {
        return """
                cat 00-pre-data.sql tables/*.sql 90-post-data.sql | mysql <db>
                """;
    }

    @Override
    protected DumpSession openSession(DBConfig config, DumpSettings settings) throws SQLException {
        return new Session(config, settings, openSnapshotConnection(config, settings));
    }

    private static Connection openSnapshotConnection(DBConfig config, DumpSettings settings) throws SQLException {
        Connection connection = DbConnectionFactory.open(config, settings.connectTimeoutSeconds());
        try (Statement st = connection.createStatement()) {
            st.execute("SET SESSION time_zone = '+00:00'");
            st.execute("SET SESSION net_write_timeout = 3600");
            st.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            st.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
            return connection;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try (Statement st = connection.createStatement()) {
            st.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // connection may already be broken; closing below is what matters
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close connection cleanly", e);
        }
    }

    private static String stripDefiner(String ddl) {
        return DEFINER.matcher(ddl).replaceAll("");
    }

    private static List<String> showTables(Connection connection, String type) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SHOW FULL TABLES WHERE Table_type = " + mysqlString(type))) {
            while (rs.next()) result.add(rs.getString(1));
        }
        result.sort(String::compareTo);
        return result;
    }

    private static final class Session implements DumpSession {
        private final DBConfig config;
        private final DumpSettings settings;
        private final Connection connection;
        private List<String> tables;

        private Session(DBConfig config, DumpSettings settings, Connection connection) {
            this.config = config;
            this.settings = settings;
            this.connection = connection;
        }

        @Override
        public List<String> tables() throws SQLException {
            if (tables == null) tables = List.copyOf(showTables(connection, "BASE TABLE"));
            return tables;
        }

        @Override
        public String fileHeader() {
            return """
                    /*!40101 SET NAMES utf8mb4 */;
                    SET FOREIGN_KEY_CHECKS = 0;
                    SET UNIQUE_CHECKS = 0;
                    SET SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO';
                    SET time_zone = '+00:00';

                    """;
        }

        @Override
        public void writePreData(Writer w) throws IOException {
            // table definitions are self-contained in MySQL; nothing has to exist beforehand
            w.write("-- Nothing to create before the tables for MySQL\n");
        }

        @Override
        public void writePostData(Writer w) throws SQLException, IOException {
            w.write("-- Views\n");
            for (String view : showTables(connection, "VIEW")) {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SHOW CREATE VIEW " + mysqlIdent(view))) {
                    if (rs.next()) w.write(stripDefiner(rs.getString(2)) + ";\n\n");
                }
            }

            w.write("-- Triggers\n");
            List<String> triggers = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SHOW TRIGGERS")) {
                while (rs.next()) triggers.add(rs.getString("Trigger"));
            }
            for (String trigger : triggers) {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SHOW CREATE TRIGGER " + mysqlIdent(trigger))) {
                    if (rs.next()) {
                        w.write("DELIMITER ;;\n" + stripDefiner(rs.getString("SQL Original Statement")) + ";;\nDELIMITER ;\n\n");
                    }
                }
            }
            w.write("SET FOREIGN_KEY_CHECKS = 1;\nSET UNIQUE_CHECKS = 1;\n");
        }

        @Override
        public TableDumper openWorker() throws SQLException {
            return new Worker(openSnapshotConnection(config, settings), settings);
        }

        @Override
        public void close() {
            closeQuietly(connection);
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
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + mysqlIdent(table))) {
                if (!rs.next()) throw new SQLException("SHOW CREATE TABLE returned nothing for " + table);
                w.write(rs.getString(2) + ";\n\n");
            }

            List<String> columns = insertableColumns(table);
            if (columns.isEmpty()) return 0;
            String columnList = String.join(", ", columns.stream().map(SqlText::mysqlIdent).toList());
            String insertPrefix = "INSERT INTO " + mysqlIdent(table) + " (" + columnList + ") VALUES\n";

            long rows = 0;
            try (Statement st = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                st.setFetchSize(Integer.MIN_VALUE); // stream rows instead of buffering the table in memory
                try (ResultSet rs = st.executeQuery("SELECT " + columnList + " FROM " + mysqlIdent(table))) {
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
            if (SqlText.isBinary(jdbcType) || jdbcType == Types.BIT) {
                byte[] bytes = rs.getBytes(index);
                if (bytes == null) sb.append("NULL");
                else if (bytes.length == 0) sb.append("''");
                else sb.append("0x").append(SqlText.hex(bytes));
                return;
            }
            String value = rs.getString(index);
            if (value == null) sb.append("NULL");
            else if (SqlText.isNumeric(jdbcType) && SqlText.isPlainNumber(value)) sb.append(value);
            else sb.append(mysqlString(value));
        }

        private List<String> insertableColumns(String table) throws SQLException {
            List<String> columns = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT COLUMN_NAME, EXTRA FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION""")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String extra = rs.getString(2);
                        if (extra != null && GENERATED.matcher(extra).find()) continue;
                        columns.add(rs.getString(1));
                    }
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
