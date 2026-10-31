package com.zurimate.appbackup.utils;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.exception.InternalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpersAndConnectionFactoryTest {

    @Test
    void parsesDriverOptionsInEitherStyle() {
        assertThat(DbConnectionFactory.parseOptions("authSource=admin&tls=true")).containsEntry("authSource", "admin").containsEntry("tls", "true");
        assertThat(DbConnectionFactory.parseOptions(" encrypt=true ; trustServerCertificate=true;")).hasSize(2)
                .containsEntry("trustServerCertificate", "true");
        assertThat(DbConnectionFactory.parseOptions("novalue&=x&k=a=b")).containsOnlyKeys("k").containsEntry("k", "a=b");
        assertThat(DbConnectionFactory.parseOptions(null)).isEmpty();
    }

    @Test
    void refusesJdbcForNoSqlEngines() {
        assertThatThrownBy(() -> DbConnectionFactory.open(new DBConfig(DBType.REDIS, null, 6379, null, null, "0", 1), 1))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void connectionFailuresSurfaceQuickly() {
        DBConfig closedPort = new DBConfig(DBType.POSTGRESQL, "127.0.0.1", 1, "u", "p", "db", 1);
        assertThatThrownBy(() -> DbConnectionFactory.open(closedPort, 2)).isInstanceOf(SQLException.class);
    }

    @Test
    void dumpFileNamesCannotEscapeTheDumpDirectory() {
        assertThat(Helpers.dumpFileName("../../etc/passwd")).doesNotContain("/").endsWith(".sql").startsWith("_");
        assertThat(Helpers.dumpFileName(".hidden")).isEqualTo("_.hidden.sql");
        assertThat(Helpers.dumpFileName("  ")).isEqualTo("_  .sql");
        assertThat(Helpers.dumpFileName("tabGL Entry")).isEqualTo("tabGL Entry.sql");
    }

    @Test
    void rootMessageShowsTheUnderlyingCause() {
        Exception e = new RuntimeException("dump failed", new IllegalStateException("FATAL: sorry, too many clients already"));
        assertThat(Helpers.rootMessage(e)).isEqualTo("dump failed (FATAL: sorry, too many clients already)");
        assertThat(Helpers.rootMessage(new IOException())).isEqualTo("IOException");
    }

    @Test
    void timestampsKeepTheirHistoricFormats() {
        Instant instant = Instant.parse("2026-10-31T07:26:41Z");
        assertThat(Helpers.cloudDirName(instant)).isEqualTo("2026-10-31-07-26-gmt");
        assertThat(Helpers.archiveTimestamp(instant)).matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}");
    }

    @Test
    void deleteRecursivelyHandlesTreesAndMissingPaths(@TempDir Path tmp) throws IOException {
        Path tree = Files.createDirectories(tmp.resolve("a/b/c"));
        Files.writeString(tree.resolve("f.txt"), "x");
        Helpers.deleteRecursively(tmp.resolve("a"));
        assertThat(tmp.resolve("a")).doesNotExist();
        Helpers.deleteRecursively(tmp.resolve("missing"));
        Helpers.deleteRecursively(null);
    }
}
