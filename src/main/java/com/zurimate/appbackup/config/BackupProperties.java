package com.zurimate.appbackup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Runtime tuning for backup jobs. Every value has a safe default so the
 * application keeps working with an empty configuration.
 */
@ConfigurationProperties(prefix = "app.backup")
public record BackupProperties(
        /* Scratch space for dumps before they are archived. Cleaned after every run. */
        @DefaultValue("./work") Path workDir,
        /* How many different backup jobs may run at the same time. */
        @DefaultValue("2") int maxConcurrentBackups,
        /* Hard cap on database connections a single backup may hold (coordinator + workers). */
        @DefaultValue("3") int maxConnectionsPerBackup,
        /* Rows fetched per round trip while streaming a table. */
        @DefaultValue("1000") int fetchSize,
        /* Rows grouped in one multi-row INSERT statement. */
        @DefaultValue("500") int insertBatchRows,
        @DefaultValue("15") int connectTimeoutSeconds,
        /* Run every registered job once when the application starts. */
        @DefaultValue("true") boolean runOnStartup,
        /* Refuse to start a backup when the destination has less free space than this. */
        @DefaultValue("512") long minFreeDiskMb,
        @DefaultValue Retention retention,
        @DefaultValue Cloud cloud
) {

    public BackupProperties {
        maxConcurrentBackups = Math.max(1, maxConcurrentBackups);
        maxConnectionsPerBackup = Math.max(2, maxConnectionsPerBackup);
        fetchSize = Math.max(10, fetchSize);
        insertBatchRows = Math.max(1, insertBatchRows);
        connectTimeoutSeconds = Math.max(1, connectTimeoutSeconds);
    }

    /** Number of worker connections a backup may open next to its coordinator connection. */
    public int maxWorkersPerBackup() {
        return maxConnectionsPerBackup - 1;
    }

    public record Retention(
            /* Archives kept per destination directory. 0 keeps everything. */
            @DefaultValue("0") int keepLast,
            /* Run history rows older than this are purged. 0 keeps everything. */
            @DefaultValue("90") int runHistoryDays
    ) {
    }

    public record Cloud(
            @DefaultValue("3") int uploadAttempts,
            @DefaultValue("30") int retryDelaySeconds,
            /* Remote archives older than this are deleted after a successful upload. 0 disables it. */
            @DefaultValue("0") int retentionDays
    ) {
    }
}
