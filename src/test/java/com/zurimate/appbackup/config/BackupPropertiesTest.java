package com.zurimate.appbackup.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupPropertiesTest {

    @Test
    void nonsenseValuesAreClampedToSafeMinimums() {
        BackupProperties p = new BackupProperties(Path.of("w"), 0, 1, 0, 0, 0, true, 0,
                new BackupProperties.Retention(0, 0), new BackupProperties.Cloud(0, 0, 0));
        assertThat(p.maxConcurrentBackups()).isEqualTo(1);
        assertThat(p.maxConnectionsPerBackup()).as("coordinator plus at least one worker").isEqualTo(2);
        assertThat(p.maxWorkersPerBackup()).isEqualTo(1);
        assertThat(p.fetchSize()).isEqualTo(10);
        assertThat(p.insertBatchRows()).isEqualTo(1);
        assertThat(p.connectTimeoutSeconds()).isEqualTo(1);
    }
}
