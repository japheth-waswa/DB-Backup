package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.config.BackupProperties;

import java.nio.file.Path;

public final class TestProps {
    private TestProps() {
    }

    public static BackupProperties with(Path workDir, int keepLast, long minFreeMb, int uploadAttempts, int cloudRetentionDays) {
        return new BackupProperties(workDir, 2, 3, 100, 100, 5, false, minFreeMb,
                new BackupProperties.Retention(keepLast, 90), new BackupProperties.Cloud(uploadAttempts, 0, cloudRetentionDays));
    }
}
