package com.zurimate.AppBackup.adapter.output;

import com.zurimate.AppBackup.dto.BackupDestination;
import com.zurimate.AppBackup.dto.DBConfig;
import com.zurimate.AppBackup.ports.output.BackupStrategy;
import com.zurimate.AppBackup.utils.DBType;
import com.zurimate.AppBackup.utils.DataSourceFactory;
import com.zurimate.AppBackup.utils.DirType;
import com.zurimate.AppBackup.utils.Helpers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Slf4j
@Component(DBType.DBTypeStrategyConstants.MYSQL_STRATEGY)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class MysqlBackupStrategy implements BackupStrategy {

    private DataSource dataSource;
    private Map<DirType, Path> tempDirs, destinationDirs;
    private DBConfig dbConfig;

    @Override
    public DBType dbType() {
        return DBType.MYSQL;
    }

    @Override
    public BackupDestination backup(DBConfig databaseConfig, String destination, List<String> otherDirsToBackup) {
        dbConfig = databaseConfig;
        log.info("MysqlBackup received request with dbConfig: {}, destination:{}, otherDirsToBackup:{}", dbConfig, destination, otherDirsToBackup);

        try {
            dataSource = DataSourceFactory.createDataSource(dbType(), dbConfig);
        } catch (Exception e) {
            log.error("Failed to initiate datasource", e);
            return new BackupDestination(null,null);
        }

        destinationDirs = Helpers.finalBackupDestinationDir(destination);
        tempDirs = Helpers.localTempDir();
        if (!createDirs()) {
            return new BackupDestination(null,null);
        }

        Path dbZipFile = Helpers.getZipFileName(tempDirs.get(DirType.DB));
        Path othersZipFile = Helpers.getZipFileName(tempDirs.get(DirType.OTHER));
        Path dbZipFileDestination = Helpers.destinationFile( destinationDirs.get(DirType.DB),dbZipFile);
        Path othersZipFileDestination = Helpers.destinationFile( destinationDirs.get(DirType.OTHER),othersZipFile);

        //initiate all processes in a new virtual thread (BG JOB)
        Thread.startVirtualThread(()->_backup( dbZipFile, othersZipFile, otherDirsToBackup));

        return new BackupDestination(dbZipFileDestination.toAbsolutePath().toString(),othersZipFileDestination.toAbsolutePath().toString());
    }

    private void _backup(Path dbZipFile,Path othersZipFile,List<String> otherDirsToBackup){
        //db backup
        backupDB(dbZipFile);
        //other backup
        Helpers.backupOtherDirs(otherDirsToBackup, tempDirs.get(DirType.OTHER), destinationDirs.get(DirType.OTHER), othersZipFile);
        //delete temp dirs
        Helpers.deleteDirectory(tempDirs.get(DirType.DB).getParent());
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
        var tableNames = fetchTableNames();
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
                futures.add(CompletableFuture.runAsync(() -> processBatch(batch), executor));
            }

            //wait for all tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Error while initiating batch processor", e);
        }
        log.info("SQL tables successfully generated");

        //compress
        Helpers.compressDirectory(tempDirs.get(DirType.DB), zipFile);

        //copy file
        Helpers.copyFile(destinationDirs.get(DirType.DB), zipFile);
    }

    private List<String> fetchTableNames() {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
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

    private void processBatch(List<String> batch) {
        log.info("Processing batch: {}", batch);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String table : batch) {
                futures.add(CompletableFuture.runAsync(() -> dumpTable(table), executor));
            }

            //wait for all tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Error while processing batch", e);
        }
    }

    private void dumpTable(String tableName) {
        log.info("Dumping table {}", tableName);
        Path tableDumpFile = Helpers.createCustomFile(tempDirs.get(DirType.DB), tableName + ".sql");
        if (Objects.isNull(tableDumpFile)) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {

            //generate CREATE TABLE statement
            String createTableQuery = getCreateTableStatement(connection, tableName);
            if (createTableQuery != null) {
                Files.writeString(tableDumpFile, createTableQuery + ";\n\n");
            }

            //append INSERT statements
            writeInsertStatementsToFile(connection, tableName, tableDumpFile);

            log.info("Successfully dumped table: {}", tableName);
        } catch (Exception e) {
            log.error("Error dumping table: {}", tableName, e);
        }
    }

    private void writeInsertStatementsToFile(Connection connection, String tableName, Path outputFilePath) {
        int offset = 0;
        boolean hasMoreRows;

        try (BufferedWriter writer = Files.newBufferedWriter(outputFilePath, StandardOpenOption.APPEND)) {
            do {
                hasMoreRows = false;
                String query = String.format("SELECT * FROM %s LIMIT %d OFFSET %d", tableName, 10, offset);
                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(query)) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    while (rs.next()) {
                        hasMoreRows = true; // If we fetch any rows, there are more rows to process
                        StringBuilder values = new StringBuilder();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            if (value == null) {
                                values.append("NULL");
                            } else if (value instanceof String || value instanceof Date) {
                                values.append("'").append(value.toString().replace("'", "''")).append("'");
                            } else {
                                values.append(value);
                            }
                            if (i < columnCount) {
                                values.append(", ");
                            }
                        }
                        writer.write(String.format("INSERT INTO %s VALUES (%s);\n", tableName, values));
                    }
                    offset += 10;
                } catch (Exception e) {
                    log.error("Error fetching data batch for table: {}", tableName, e);
                }
            } while (hasMoreRows);
        } catch (Exception e) {
            log.error("Error writing to file for table: {}", tableName, e);
        }

    }

    private String getCreateTableStatement(Connection connection, String tableName) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + tableName)) {
            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (Exception e) {
            log.error("Error fetching CREATE TABLE statement for table: {}", tableName);
        }
        return null;
    }

}
