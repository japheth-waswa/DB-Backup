package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.utils.DBType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Connection details of the database to back up.
 *
 * @param dbName         database name; Oracle service name; Redis database index
 * @param tableBatchesOf how many tables/collections are dumped in parallel
 * @param options        extra driver options as {@code key=value} pairs separated by {@code &} or {@code ;},
 *                       e.g. {@code authSource=admin} (MongoDB) or {@code trustServerCertificate=true} (SQL Server)
 */
public record DBConfig(@NotNull DBType dbType,
                       String host,
                       @NotNull @Min(1) @Max(65535) Integer port,
                       String username,
                       String password,
                       @NotBlank String dbName,
                       @Min(1) @Max(32) Integer tableBatchesOf,
                       @Size(max = 1024) String options) {

    public DBConfig(DBType dbType, String host, Integer port, String username, String password, String dbName,
                    Integer tableBatchesOf) {
        this(dbType, host, port, username, password, dbName, tableBatchesOf, null);
    }

    public String hostOrDefault() {
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    /** Number of tables that may be dumped in parallel, never below one. */
    public int parallelism() {
        return tableBatchesOf == null || tableBatchesOf < 1 ? 1 : tableBatchesOf;
    }

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "DBConfig[dbType=%s, host=%s, port=%s, username=%s, password=%s, dbName=%s, tableBatchesOf=%s, options=%s]"
                .formatted(dbType, host, port, username, password == null ? null : "****", dbName, tableBatchesOf, options);
    }
}
