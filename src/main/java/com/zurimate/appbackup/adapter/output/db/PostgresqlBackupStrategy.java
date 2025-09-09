package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.BackupStrategy;
import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@RequiredArgsConstructor
@Slf4j
@Component(DBTypeStrategyConstants.POSTGRESQL_STRATEGY)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class PostgresqlBackupStrategy implements BackupStrategy {

    private DataSource dataSource;
    private Map<DirType, Path> tempDirs, destinationDirs;
    private DBConfig dbConfig;
    private final DbFilesToCloud dbFilesToCloud;

    @Override
    public DBType dbType() {
        return DBType.POSTGRESQL;
    }

    @Override
    public BackupDestination backup(DBConfig databaseConfig, String destination, List<String> otherDirsToBackup,
                                    CloudType cloudType, String cloudDestinationDir, CloudConfig cloudConfig) {
        dbConfig = databaseConfig;
        log.info("PostgreSQLBackup received request with dbConfig: {}, destination:{}, otherDirsToBackup:{}", dbConfig, destination, otherDirsToBackup);

        try {
            dataSource = DataSourceFactory.createDataSource(dbType(), dbConfig);
        } catch (Exception e) {
            log.error("Failed to initiate datasource", e);
            return new BackupDestination(null, null);
        }

        destinationDirs = Helpers.finalBackupDestinationDir(destination);
        tempDirs = Helpers.localTempDir();
        if (!createDirs()) {
            return new BackupDestination(null, null);
        }

        Path dbZipFile = Helpers.getZipFileName(tempDirs.get(DirType.DB));
        Path othersZipFile = Helpers.getZipFileName(tempDirs.get(DirType.OTHER));
        Path dbZipFileDestination = Helpers.destinationFile(destinationDirs.get(DirType.DB), dbZipFile);
        Path othersZipFileDestination = Helpers.destinationFile(destinationDirs.get(DirType.OTHER), othersZipFile);

        //initiate all processes in a new virtual thread (BG JOB)
        Thread.startVirtualThread(() -> _backup(dbZipFile, othersZipFile, otherDirsToBackup,
                dbZipFileDestination, othersZipFileDestination,
                cloudType, cloudDestinationDir, cloudConfig));

        return new BackupDestination(dbZipFileDestination.toAbsolutePath().toString(), othersZipFileDestination.toAbsolutePath().toString());
    }

    private void _backup(Path dbZipFile, Path othersZipFile, List<String> otherDirsToBackup,
                         Path dbZipFileDestination, Path othersZipFileDestination,
                         CloudType cloudType, String cloudDestinationDir, CloudConfig cloudConfig) {
        //db backup
        backupDB(dbZipFile);
        //other backup
        Helpers.backupOtherDirs(otherDirsToBackup, tempDirs.get(DirType.OTHER), destinationDirs.get(DirType.OTHER), othersZipFile);

        //delete temp dirs
        Helpers.deleteDirectory(tempDirs.get(DirType.DB).getParent());

        // upload to cloud
        dbFilesToCloud.uploadFilesToCloud(dbZipFileDestination, othersZipFileDestination, cloudType, cloudDestinationDir, cloudConfig);
    }

    private boolean createDirs() {
        log.info("Creating directories {} and {}", tempDirs, destinationDirs);
        try {
            Files.createDirectories(tempDirs.get(DirType.DB));
            Files.createDirectories(tempDirs.get(DirType.OTHER));
            Files.createDirectories(destinationDirs.get(DirType.DB));
            Files.createDirectories(destinationDirs.get(DirType.OTHER));
        } catch (IOException e) {
            log.error("Error while creating directories", e);
            return false;
        }
        return true;
    }

    private void backupDB(Path zipFile) {
        try (Connection connection = dataSource.getConnection()) {
            var tableNames = fetchTableNames(connection);
            if (tableNames.isEmpty()) {
                log.warn("No tables found in this database, stopping further execution");
                return;
            }
            log.info("Table names found: {}", tableNames);
            List<List<String>> batches = createBatches(tableNames, dbConfig.tableBatchesOf() > 0 ? dbConfig.tableBatchesOf() : 1);
            log.info("Ready to initiate batch processing");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (List<String> batch : batches) {
                    futures.add(CompletableFuture.runAsync(() -> processBatch(connection, batch), executor));
                }
                //wait for all tasks to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.error("Error while initiating batch processor", e);
            }
            log.info("SQL tables successfully generated");
        } catch (SQLException e) {
            log.error("Failed to open SQL connection", e);
        }

        //compress
        Helpers.compressDirectory(tempDirs.get(DirType.DB), zipFile);

        //copy file
        Helpers.copyFile(destinationDirs.get(DirType.DB), zipFile);
    }

    private List<String> fetchTableNames(Connection connection) {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        } catch (Exception e) {
            log.error("Error while reading table names", e);
        }
        return tables;
    }

    private List<List<String>> createBatches(List<String> tableNames, int batchSize) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0, len = tableNames.size(); i < len; i += batchSize) {
            batches.add(tableNames.subList(i, Math.min(i + batchSize, len)));
        }
        return batches;
    }

    private void processBatch(Connection connection, List<String> batch) {
        log.info("Processing batch: {}", batch);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String table : batch) {
                futures.add(CompletableFuture.runAsync(() -> dumpTable(connection, table), executor));
            }
            //wait for all tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Error while processing batch", e);
        }
    }

    private void dumpTable(Connection connection, String tableName) {
        log.info("Dumping table {}", tableName);
        Path tableDumpFile = Helpers.createCustomFile(tempDirs.get(DirType.DB), tableName + ".sql");
        if (Objects.isNull(tableDumpFile)) {
            log.error("Failed to create dump file for table: {}", tableName);
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(tableDumpFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            //generate CREATE TABLE statement
            String createTableQuery = getCreateTableStatement(connection, tableName);
            if (createTableQuery != null) {
                log.debug("Writing CREATE TABLE for {}: {}", tableName, createTableQuery);
                writer.write(createTableQuery + "\n\n");
                writer.flush();
            } else {
                log.warn("No CREATE TABLE statement generated for table: {}", tableName);
            }

            //append INSERT statements
            writeInsertStatementsToFile(connection, tableName, writer);

            log.info("Successfully dumped table: {}", tableName);
        } catch (Exception e) {
            log.error("Error dumping table: {}", tableName, e);
        }
    }

    private void writeInsertStatementsToFile(Connection connection, String tableName, BufferedWriter writer) {
        int offset = 0;
        boolean hasMoreRows;

        try {
            // Fetch column names and their data types
            Map<String, String> columnTypes = new HashMap<>();
            List<String> columnNames = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '" + tableName + "' ORDER BY ordinal_position")) {
                while (rs.next()) {
                    columnNames.add(rs.getString("column_name"));
                    columnTypes.put(rs.getString("column_name"), rs.getString("data_type"));
                }
            } catch (Exception e) {
                log.error("Error fetching column names and types for table: {}", tableName, e);
                return;
            }

            if (columnNames.isEmpty()) {
                log.warn("No columns found for table: {}", tableName);
                return;
            }

            String columnList = "\"" + String.join("\", \"", columnNames) + "\"";

            do {
                hasMoreRows = false;
                String query = String.format("SELECT * FROM \"%s\" LIMIT %d OFFSET %d", tableName, 10, offset);
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(query)) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    while (rs.next()) {
                        hasMoreRows = true;
                        StringBuilder values = new StringBuilder();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnName(i);
                            String dataType = columnTypes.getOrDefault(columnName, "");
                            Object value = rs.getObject(i);
                            if (value == null) {
                                values.append("NULL");
                            } else if (value instanceof String || value instanceof Date || value instanceof Timestamp || value instanceof Time ||
                                    dataType.equalsIgnoreCase("uuid") || value instanceof PGobject) {
                                // Quote UUIDs, strings, dates, timestamps, times, and PGobjects (e.g., JSONB)
                                values.append("'").append(value.toString().replace("'", "''")).append("'");
                            } else if (value instanceof byte[]) {
                                values.append("E'\\\\x").append(bytesToHex((byte[]) value)).append("'");
                            } else {
                                values.append(value);
                            }
                            if (i < columnCount) {
                                values.append(", ");
                            }
                        }
                        writer.write(String.format("INSERT INTO \"%s\" (%s) VALUES (%s);\n", tableName, columnList, values));
                    }
                    offset += 10;
                } catch (Exception e) {
                    log.error("Error fetching data batch for table: {}", tableName, e);
                }
            } while (hasMoreRows);
            writer.flush();
        } catch (Exception e) {
            log.error("Error writing to file for table: {}", tableName, e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String getCreateTableStatement(Connection connection, String tableName) {
        // Validate table existence
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?)"
        )) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next() && !rs.getBoolean(1)) {
                    log.error("Table {} does not exist in schema public", tableName);
                    return null;
                }
            }
        } catch (SQLException e) {
            log.error("Error checking existence of table: {}", tableName, e);
            return null;
        }

        StringBuilder sb = new StringBuilder("CREATE TABLE \"").append(tableName).append("\" (\n");

        // Fetch columns
        List<String> colDefs = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(
                """
                        SELECT 
                            a.attname as column_name,
                            pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                            CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE '' END as not_null,
                            CASE WHEN pg_get_expr(ad.adbin, ad.adrelid) IS NOT NULL AND a.attidentity = '' THEN 'DEFAULT ' || pg_get_expr(ad.adbin, ad.adrelid) ELSE '' END as default_value,
                            CASE WHEN a.attidentity != '' THEN 'GENERATED ' || CASE a.attidentity WHEN 'd' THEN 'BY DEFAULT' WHEN 'a' THEN 'ALWAYS' END || ' AS IDENTITY' ELSE '' END as identity
                        FROM pg_attribute a
                        LEFT JOIN pg_attrdef ad ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                        WHERE a.attrelid = (SELECT oid FROM pg_class WHERE relname = ? AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public'))
                        AND a.attnum > 0
                        AND NOT a.attisdropped
                        ORDER BY a.attnum"""
        )) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String colDef = "\"" + rs.getString("column_name") + "\" " + rs.getString("data_type").trim() +
                            " " + rs.getString("identity").trim() +
                            " " + rs.getString("default_value").trim() +
                            " " + rs.getString("not_null").trim();
                    colDefs.add(colDef.trim());
                }
            }
        } catch (SQLException e) {
            log.error("Error fetching columns for table: {}", tableName, e);
            return null;
        }

        // Fetch constraints (PK, UK, FK, CHECK)
        StringBuilder constraints = new StringBuilder();
        try (PreparedStatement pstmt = connection.prepareStatement(
                """
                        SELECT 
                            conname,
                            pg_get_constraintdef(c.oid) as def
                        FROM pg_constraint c
                        WHERE c.conrelid = (SELECT oid FROM pg_class WHERE relname = ? AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public'))
                        AND c.contype IN ('p', 'u', 'f', 'c')
                        ORDER BY c.contype DESC"""
        )) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    constraints.append(",\n  CONSTRAINT \"").append(rs.getString("conname")).append("\" ").append(rs.getString("def"));
                }
            }
        } catch (SQLException e) {
            log.error("Error fetching constraints for table: {}", tableName, e);
            return null;
        }

        if (colDefs.isEmpty()) {
            log.warn("No columns found for table: {}", tableName);
            return null;
        }

        sb.append(String.join(",\n  ", colDefs));
        sb.append(constraints);
        sb.append("\n);");

        return sb.toString();
    }
}
