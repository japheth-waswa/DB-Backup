package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.ports.output.DatabaseDumpStrategy;
import com.zurimate.appbackup.service.backup.DatabaseDumper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

final class DumpTestSupport {
    private DumpTestSupport() {
    }

    static BackupProperties properties(int maxConnections, int insertBatchRows) {
        return new BackupProperties(Path.of("build-work"), 2, maxConnections, 100, insertBatchRows, 10, false, 0,
                new BackupProperties.Retention(0, 90), new BackupProperties.Cloud(1, 0, 0));
    }

    static DatabaseDumper dumper(DatabaseDumpStrategy strategy, BackupProperties properties) {
        return new DatabaseDumper(List.of(strategy), properties);
    }

    /** Restore order of a relational dump: pre-data, every table file, post-data. */
    static List<Path> restoreOrder(Path dumpDir) throws java.io.IOException {
        try (java.util.stream.Stream<Path> tables = java.nio.file.Files.list(dumpDir.resolve(SqlDumpStrategy.TABLES_DIR))) {
            List<Path> files = new java.util.ArrayList<>();
            files.add(dumpDir.resolve(SqlDumpStrategy.PRE_DATA));
            files.addAll(tables.sorted().toList());
            files.add(dumpDir.resolve(SqlDumpStrategy.POST_DATA));
            return files;
        }
    }

    static void exec(Connection connection, String... statements) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }

    /**
     * Reads every row as comparable values (strings, byte arrays rendered as hex, LOB contents)
     * so two databases can be compared regardless of the engine.
     */
    static List<List<String>> rows(Connection connection, String sql) throws SQLException {
        List<List<String>> rows = new java.util.ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int count = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new java.util.ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof java.sql.Blob blob) {
                        value = java.util.HexFormat.of().formatHex(blob.getBytes(1, (int) blob.length()));
                    } else if (value instanceof java.sql.Clob clob) {
                        value = clob.getSubString(1, (int) clob.length());
                    } else if (value instanceof byte[] bytes) {
                        value = java.util.HexFormat.of().formatHex(bytes);
                    } else if (value != null) {
                        value = rs.getString(i);
                    }
                    row.add(value == null ? null : value.toString());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    static String single(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
