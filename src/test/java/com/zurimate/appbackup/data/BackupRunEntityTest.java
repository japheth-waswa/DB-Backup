package com.zurimate.appbackup.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRunEntityTest {

    @Test
    void longMessagesAreTruncatedToFitTheColumn() {
        BackupRunEntity run = new BackupRunEntity();
        run.setMessage("x".repeat(10_000));
        assertThat(run.getMessage()).hasSize(BackupRunEntity.MAX_MESSAGE_LENGTH).endsWith("...");
        run.setMessage(null);
        assertThat(run.getMessage()).isNull();
        run.setMessage("short");
        assertThat(run.getMessage()).isEqualTo("short");
    }

    @Test
    void entityToStringHidesCredentials() {
        BackupEntity entity = BackupEntity.builder().password("db-pass").backBlazeConfigJson("{\"applicationKey\":\"k\"}").build();
        assertThat(entity.toString()).doesNotContain("db-pass").doesNotContain("applicationKey");
    }
}
