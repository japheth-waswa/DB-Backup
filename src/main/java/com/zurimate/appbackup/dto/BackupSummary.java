package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.SchedulerType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public view of a registered backup job. Never carries credentials. */
public record BackupSummary(UUID id,
                            SchedulerType schedulerType,
                            DBType dbType,
                            String host,
                            int port,
                            String dbName,
                            String destination,
                            List<String> otherDirsToBackup,
                            CloudType cloudType,
                            String cloudDestinationDir,
                            Instant createdAt,
                            BackupRunView lastRun) {
}
