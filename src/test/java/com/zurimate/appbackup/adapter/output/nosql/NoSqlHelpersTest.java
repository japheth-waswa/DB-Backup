package com.zurimate.appbackup.adapter.output.nosql;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.exception.InvalidPayloadException;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoSqlHelpersTest {

    @Test
    void mongoFileNamesEscapeOnlyWhatTheFileSystemNeeds() {
        assertThat(MongoDumpStrategy.fileBase("orders.2026 archive")).isEqualTo("orders.2026 archive");
        assertThat(MongoDumpStrategy.fileBase("a/b%c:d")).isEqualTo("a%2Fb%25c%3Ad");
    }

    @Test
    void redisDatabaseMustBeAnIndex() {
        assertThat(RedisDumpStrategy.databaseIndex(config(" 3 "))).isEqualTo(3);
        assertThatThrownBy(() -> RedisDumpStrategy.databaseIndex(config("cache"))).isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> RedisDumpStrategy.databaseIndex(config("-1"))).isInstanceOf(InvalidPayloadException.class);
    }

    private static DBConfig config(String db) {
        return new DBConfig(DBType.REDIS, "h", 6379, null, null, db, 1);
    }
}
