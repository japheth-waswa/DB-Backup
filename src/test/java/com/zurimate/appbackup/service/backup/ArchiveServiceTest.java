package com.zurimate.appbackup.service.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveServiceTest {
    private final ArchiveService archives = new ArchiveService();

    @Test
    void zipsDirectoriesWithRelativePaths(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("db/tables"));
        Files.writeString(tmp.resolve("db/00-pre-data.sql"), "pre");
        Files.writeString(src.resolve("t.sql"), "rows");
        Path zip = tmp.resolve("db.zip");

        archives.zipDirectory(tmp.resolve("db"), zip);

        assertThat(entries(zip)).containsExactlyInAnyOrder("00-pre-data.sql", "tables/t.sql");
    }

    @Test
    void zipsOtherDirectoriesUnderTheirOwnNameAndReportsMissingOnes(@TempDir Path tmp) throws IOException {
        Path uploads = Files.createDirectories(tmp.resolve("uploads/2026"));
        Files.writeString(uploads.resolve("a.png"), "img");
        Path single = Files.writeString(tmp.resolve("config.yml"), "k: v");
        Path zip = tmp.resolve("other.zip");

        List<String> missing = archives.zipPaths(List.of(tmp.resolve("uploads").toString(), single.toString(),
                tmp.resolve("nope").toString()), zip);

        assertThat(missing).containsExactly(tmp.resolve("nope").toString());
        assertThat(entries(zip)).containsExactlyInAnyOrder("uploads/2026/a.png", "config.yml");
    }

    @Test
    void computesSha256(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("abc.txt"), "abc");
        assertThat(archives.sha256(file)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void publishesAtomicallyWithASha256sumCompatibleChecksum(@TempDir Path tmp) throws IOException {
        Path archive = Files.writeString(tmp.resolve("db-2026-10-31-06-00-00.zip"), "zip-bytes");
        Path dest = tmp.resolve("backups/erp");

        Path published = archives.publish(archive, dest, "deadbeef");

        assertThat(published).hasContent("zip-bytes");
        assertThat(dest.resolve("db-2026-10-31-06-00-00.zip.sha256")).hasContent("deadbeef  db-2026-10-31-06-00-00.zip\n");
        try (var files = Files.list(dest)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".partial"));
        }
    }

    @Test
    void reportsFreeSpaceOfTheNearestExistingParent(@TempDir Path tmp) throws IOException {
        assertThat(archives.usableSpaceMb(tmp.resolve("not/yet/created"))).isPositive();
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }
}
