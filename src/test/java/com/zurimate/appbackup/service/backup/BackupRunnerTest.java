package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.data.BackupRunEntity;
import com.zurimate.appbackup.data.BackupRunRepository;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.exception.CloudUploadException;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.service.cloud.CloudUploadContext;
import com.zurimate.appbackup.utils.BackupRunStatus;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupRunnerTest {
    @TempDir
    Path tmp;

    private DatabaseDumper dumper;
    private CloudUploadContext cloud;
    private BackupRunRepository runs;
    private final List<BackupRunEntity> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dumper = mock(DatabaseDumper.class);
        cloud = mock(CloudUploadContext.class);
        runs = mock(BackupRunRepository.class);
        when(runs.save(any())).thenAnswer(inv -> {
            BackupRunEntity run = inv.getArgument(0);
            saved.add(copyStatus(run));
            return run;
        });
    }

    private static BackupRunEntity copyStatus(BackupRunEntity run) {
        return BackupRunEntity.builder().id(run.getId()).status(run.getStatus()).build();
    }

    private BackupRunner runner(long minFreeMb, int keepLast) {
        var props = TestProps.with(tmp.resolve("work"), keepLast, minFreeMb, 1, 0);
        return new BackupRunner(dumper, new ArchiveService(), new RetentionService(props), cloud, runs, props);
    }

    private BackupJob job(CloudType cloudType, List<String> otherDirs) {
        BackBlazeConfig b2 = cloudType == null ? null : new BackBlazeConfig("id", "name", "key", "bucket");
        return new BackupJob(UUID.randomUUID(), "TEST",
                new DBConfig(DBType.POSTGRESQL, "h", 5432, "u", "p", "shop", 2),
                tmp.resolve("backups").toString(), otherDirs, cloudType, "shop", b2);
    }

    private void dumpWrites(TreeMap<String, String> failures) throws Exception {
        when(dumper.dump(any(), any())).thenAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.resolve("tables"));
            Files.writeString(out.resolve("tables/t.sql"), "INSERT ...");
            return new DumpReport(3, 42, failures);
        });
    }

    @Test
    void successfulRunPublishesArchiveChecksumAndHistory() throws Exception {
        dumpWrites(new TreeMap<>());
        BackupRunEntity run = runner(0, 0).execute(job(null, List.of()));

        assertThat(run.getStatus()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(run.getTablesTotal()).isEqualTo(3);
        assertThat(run.getRowsDumped()).isEqualTo(42);
        assertThat(run.getCloudStatus()).isEqualTo("NOT_CONFIGURED");
        Path archive = Path.of(run.getDbArchive());
        assertThat(archive).exists().hasFileName(archive.getFileName().toString()).isRegularFile();
        assertThat(archive.getFileName().toString()).matches("db-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.zip");
        assertThat(Path.of(run.getDbArchive() + ".sha256")).content().startsWith(run.getDbArchiveSha256());
        assertThat(run.getDurationMs()).isNotNull();
        assertThat(saved).extracting(BackupRunEntity::getStatus).containsExactly(BackupRunStatus.RUNNING, BackupRunStatus.SUCCESS);
        try (var leftovers = Files.list(tmp.resolve("work"))) {
            assertThat(leftovers).as("scratch space is cleaned").isEmpty();
        }
    }

    @Test
    void incompleteDumpIsMarkedPartialWithTheFailedTables() throws Exception {
        TreeMap<String, String> failures = new TreeMap<>();
        failures.put("tabGL Entry", "syntax error");
        dumpWrites(failures);

        BackupRunEntity run = runner(0, 0).execute(job(null, List.of()));

        assertThat(run.getStatus()).isEqualTo(BackupRunStatus.PARTIAL);
        assertThat(run.getTablesFailed()).isEqualTo(1);
        assertThat(run.getMessage()).contains("tabGL Entry -> syntax error");
        assertThat(run.getDbArchive()).isNotNull();
    }

    @Test
    void dumpFailureIsRecordedCleanedUpAndReleasesTheLock() throws Exception {
        when(dumper.dump(any(), any())).thenThrow(new IllegalStateException("FATAL: sorry, too many clients already"));
        BackupRunner runner = runner(0, 0);
        BackupJob job = job(null, List.of());

        BackupRunEntity run = runner.execute(job);

        assertThat(run.getStatus()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(run.getMessage()).contains("too many clients");
        assertThat(runner.isRunning(job.backupId())).isFalse();
        try (var leftovers = Files.list(tmp.resolve("work"))) {
            assertThat(leftovers).isEmpty();
        }
        assertThat(runner.execute(job).getStatus()).as("next run is not blocked").isEqualTo(BackupRunStatus.FAILED);
    }

    @Test
    void overlappingRunOfTheSameJobIsSkipped() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dumper.dump(any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            Files.createDirectories(inv.<Path>getArgument(1));
            return new DumpReport(0, 0, new TreeMap<>());
        });
        BackupRunner runner = runner(0, 0);
        BackupJob job = job(null, List.of());

        Thread first = Thread.ofVirtual().start(() -> runner.execute(job));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.isRunning(job.backupId())).isTrue();

        BackupRunEntity second = runner.execute(job);
        release.countDown();
        first.join();

        assertThat(second.getStatus()).isEqualTo(BackupRunStatus.SKIPPED);
        assertThat(second.getMessage()).contains("still in progress");
    }

    @Test
    void refusesToStartWhenTheDiskIsAlmostFull() throws Exception {
        BackupRunEntity run = runner(Long.MAX_VALUE / 2, 0).execute(job(null, List.of()));
        assertThat(run.getStatus()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(run.getMessage()).contains("MB free");
        verify(dumper, never()).dump(any(), any());
    }

    @Test
    void cloudFailureKeepsTheLocalBackupAndIsReported() throws Exception {
        dumpWrites(new TreeMap<>());
        doThrow(new CloudUploadException("storage_cap_exceeded", null, false))
                .when(cloud).uploadFileToCloud(eq(CloudType.BACK_BLAZE), any(), anyString(), any());

        BackupRunEntity run = runner(0, 0).execute(job(CloudType.BACK_BLAZE, List.of()));

        assertThat(run.getStatus()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(run.getCloudStatus()).startsWith("FAILED").contains("storage_cap_exceeded");
        assertThat(Path.of(run.getDbArchive())).exists();
    }

    @Test
    void uploadsIntoATimestampedRemoteFolderAndAppliesRetention() throws Exception {
        dumpWrites(new TreeMap<>());
        when(cloud.applyRetention(any(), any(), any())).thenReturn(2);

        BackupRunEntity run = runner(0, 0).execute(job(CloudType.BACK_BLAZE, List.of()));

        verify(cloud).uploadFileToCloud(eq(CloudType.BACK_BLAZE), eq(Path.of(run.getDbArchive())),
                org.mockito.ArgumentMatchers.matches("shop/\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-gmt"), any());
        assertThat(run.getCloudStatus()).isEqualTo("UPLOADED (retention removed 2)");
    }

    @Test
    void otherDirectoriesAreArchivedAndMissingOnesNoted() throws Exception {
        dumpWrites(new TreeMap<>());
        Path uploads = Files.createDirectories(tmp.resolve("uploads"));
        Files.writeString(uploads.resolve("a.txt"), "a");

        BackupRunEntity run = runner(0, 0).execute(job(null, List.of(uploads.toString(), tmp.resolve("gone").toString())));

        assertThat(Path.of(run.getOtherArchive())).exists();
        assertThat(run.getMessage()).contains("Missing directories").contains("gone");
    }

    @Test
    void localRetentionRunsAfterASuccessfulBackup() throws Exception {
        dumpWrites(new TreeMap<>());
        Path dest = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(dest.resolve("db-2020-01-01-00-00-00.zip"), "old");

        BackupRunEntity run = runner(0, 1).execute(job(null, List.of()));

        assertThat(dest.resolve("db-2020-01-01-00-00-00.zip")).doesNotExist();
        assertThat(run.getMessage()).contains("Retention removed 1");
    }

    @Test
    void restartRecoveryClosesInterruptedRunsAndClearsScratchSpace() throws IOException {
        BackupRunEntity stuck = BackupRunEntity.builder().id(UUID.randomUUID()).status(BackupRunStatus.RUNNING).build();
        when(runs.findByStatus(BackupRunStatus.RUNNING)).thenReturn(List.of(stuck));
        Files.createDirectories(tmp.resolve("work/run-old"));

        runner(0, 0).recoverAfterRestart();

        assertThat(stuck.getStatus()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(stuck.getMessage()).contains("restart");
        verify(runs).saveAll(List.of(stuck));
        assertThat(tmp.resolve("work")).doesNotExist();
    }

    @Test
    void historyOutageDoesNotAbortTheBackup() throws Exception {
        dumpWrites(new TreeMap<>());
        doThrow(new IllegalStateException("metadata db down")).when(runs).save(any());
        assertThat(runner(0, 0).execute(job(null, List.of())).getStatus()).isEqualTo(BackupRunStatus.SUCCESS);
    }
}
