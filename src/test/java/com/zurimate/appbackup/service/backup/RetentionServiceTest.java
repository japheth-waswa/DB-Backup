package com.zurimate.appbackup.service.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionServiceTest {

    @Test
    void keepsTheNewestArchivesOfEachKindAndTheirChecksums(@TempDir Path dir) throws IOException {
        for (String ts : new String[]{"2026-10-29-00-00-00", "2026-10-30-00-00-00", "2026-10-31-00-00-00"}) {
            Files.writeString(dir.resolve("db-" + ts + ".zip"), "x");
            Files.writeString(dir.resolve("db-" + ts + ".zip.sha256"), "x");
            Files.writeString(dir.resolve("other-" + ts + ".zip"), "x");
        }
        Files.writeString(dir.resolve("db-manual-copy.zip"), "not ours");
        Files.writeString(dir.resolve("notes.txt"), "not ours");

        int removed = new RetentionService(TestProps.with(dir, 2, 0, 1, 0)).apply(dir);

        assertThat(removed).isEqualTo(2);
        assertThat(dir.resolve("db-2026-10-29-00-00-00.zip")).doesNotExist();
        assertThat(dir.resolve("db-2026-10-29-00-00-00.zip.sha256")).doesNotExist();
        assertThat(dir.resolve("other-2026-10-29-00-00-00.zip")).doesNotExist();
        assertThat(dir.resolve("db-2026-10-30-00-00-00.zip")).exists();
        assertThat(dir.resolve("db-2026-10-31-00-00-00.zip")).exists();
        assertThat(dir.resolve("db-manual-copy.zip")).as("files outside the naming scheme are never touched").exists();
        assertThat(dir.resolve("notes.txt")).exists();
    }

    @Test
    void disabledByDefault(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("db-2026-10-29-00-00-00.zip"), "x");
        assertThat(new RetentionService(TestProps.with(dir, 0, 0, 1, 0)).apply(dir)).isZero();
        assertThat(new RetentionService(TestProps.with(dir, 1, 0, 1, 0)).apply(dir.resolve("missing"))).isZero();
        assertThat(dir.resolve("db-2026-10-29-00-00-00.zip")).exists();
    }
}
