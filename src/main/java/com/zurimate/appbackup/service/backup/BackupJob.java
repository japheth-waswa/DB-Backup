package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.CloudType;

import java.util.List;
import java.util.UUID;

/** Everything a single backup run needs. Immutable, so concurrent runs never share state. */
public record BackupJob(UUID backupId,
                        String trigger,
                        DBConfig dbConfig,
                        String destination,
                        List<String> otherDirsToBackup,
                        CloudType cloudType,
                        String cloudDestinationDir,
                        CloudConfig cloudConfig) {

    public BackupJob {
        otherDirsToBackup = otherDirsToBackup == null ? List.of() : List.copyOf(otherDirsToBackup);
    }

    /** Identifies the job for the "one run at a time" rule. */
    String lockKey() {
        if (backupId != null) return backupId.toString();
        return "%s:%s:%s/%s".formatted(dbConfig.dbType(), dbConfig.host(), dbConfig.port(), dbConfig.dbName());
    }

    String label() {
        return "%s %s@%s:%s".formatted(dbConfig.dbType(), dbConfig.dbName(), dbConfig.host(), dbConfig.port());
    }
}
