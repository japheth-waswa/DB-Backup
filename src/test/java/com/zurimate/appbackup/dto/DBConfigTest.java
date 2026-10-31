package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DBConfigTest {

    @Test
    void neverPrintsSecrets() {
        DBConfig config = new DBConfig(DBType.MYSQL, "db", 3306, "erp", "Sup3r$ecret", "erpnext", 5, "ssl=true");
        assertThat(config.toString()).contains("password=****", "erp", "ssl=true").doesNotContain("Sup3r$ecret");

        BackBlazeConfig b2 = new BackBlazeConfig("keyId", "Management", "K005-very-secret", "bucket");
        assertThat(b2.toString()).doesNotContain("K005-very-secret");
    }

    @Test
    void defaultsAreSafe() {
        DBConfig config = new DBConfig(DBType.REDIS, " ", 6379, null, null, "0", null);
        assertThat(config.parallelism()).isEqualTo(1);
        assertThat(config.hostOrDefault()).isEqualTo("127.0.0.1");
        assertThat(config.toString()).contains("password=null");
        assertThat(new DBConfig(DBType.MYSQL, "h", 1, "u", "p", "d", 0).parallelism()).isEqualTo(1);
    }

    @Test
    void onlySqlEnginesRequireCredentials() {
        assertThat(DBType.POSTGRESQL.requiresCredentials()).isTrue();
        assertThat(DBType.ORACLE.requiresCredentials()).isTrue();
        assertThat(DBType.MONGODB.requiresCredentials()).isFalse();
        assertThat(DBType.REDIS.requiresCredentials()).isFalse();
    }
}
