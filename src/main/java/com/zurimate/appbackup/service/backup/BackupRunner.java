package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.data.BackupRunEntity;
import com.zurimate.appbackup.data.BackupRunRepository;
import com.zurimate.appbackup.ports.output.DumpReport;
import com.zurimate.appbackup.service.cloud.CloudUploadContext;
import com.zurimate.appbackup.utils.BackupRunStatus;
import com.zurimate.appbackup.utils.Helpers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs one backup job end to end: dump, archive, checksum, publish, upload, retention.
 * Every run is recorded in {@code backup_runs}; failures are reported, never swallowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupRunner {
    /** Scratch directories left in the working directory by releases before the work-dir setting. */
    private static final Pattern LEGACY_TEMP_DIR = Pattern.compile("^temp-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-[0-9a-f-]{36}$");

    private final DatabaseDumper databaseDumper;
    private final ArchiveService archiveService;
    private final RetentionService retentionService;
    private final CloudUploadContext cloudUploadContext;
    private final BackupRunRepository runRepository;
    private final BackupProperties properties;

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public boolean isRunning(UUID backupId) {
        return running.contains(backupId.toString());
    }

    public BackupRunEntity execute(BackupJob job) {
        Instant startedAt = Instant.now();
        BackupRunEntity run = BackupRunEntity.builder()
                .id(UUID.randomUUID())
                .backupId(job.backupId())
                .triggeredBy(job.trigger())
                .startedAt(startedAt)
                .status(BackupRunStatus.RUNNING)
                .build();

        if (!running.add(job.lockKey())) {
            log.warn("Skipping backup of {}: the previous run is still in progress", job.label());
            run.setStatus(BackupRunStatus.SKIPPED);
            run.setFinishedAt(startedAt);
            run.setDurationMs(0L);
            run.setMessage("Previous run still in progress");
            return save(run);
        }

        save(run);
        Path workDir = null;
        try {
            Path destination = Path.of(job.destination()).toAbsolutePath().normalize();
            Files.createDirectories(destination);
            long freeMb = archiveService.usableSpaceMb(destination);
            if (freeMb < properties.minFreeDiskMb()) {
                throw new IOException("Only %d MB free at %s, below the %d MB minimum".formatted(freeMb, destination, properties.minFreeDiskMb()));
            }

            String timestamp = Helpers.archiveTimestamp(startedAt);
            workDir = Files.createDirectories(properties.workDir().toAbsolutePath()
                    .resolve("run-" + timestamp + "-" + run.getId().toString().substring(0, 8)));

            log.info("Backup of {} started (run {}, trigger {})", job.label(), run.getId(), job.trigger());
            DumpReport report = databaseDumper.dump(job.dbConfig(), workDir.resolve("db"));
            run.setTablesTotal(report.objectsTotal());
            run.setTablesFailed(report.failures().size());
            run.setRowsDumped(report.recordsDumped());

            List<String> notes = new ArrayList<>();
            if (!report.complete()) {
                notes.add("Failed tables: " + String.join("; ", report.failures().entrySet().stream()
                        .map(e -> e.getKey() + " -> " + e.getValue()).toList()));
            }

            Path dbZip = workDir.resolve("db-" + timestamp + ".zip");
            archiveService.zipDirectory(workDir.resolve("db"), dbZip);
            String dbSha = archiveService.sha256(dbZip);
            Path publishedDb = archiveService.publish(dbZip, destination, dbSha);
            run.setDbArchive(publishedDb.toString());
            run.setDbArchiveBytes(Files.size(publishedDb));
            run.setDbArchiveSha256(dbSha);

            Path publishedOther = null;
            if (!job.otherDirsToBackup().isEmpty()) {
                Path otherZip = workDir.resolve("other-" + timestamp + ".zip");
                List<String> missing = archiveService.zipPaths(job.otherDirsToBackup(), otherZip);
                if (!missing.isEmpty()) notes.add("Missing directories: " + String.join(", ", missing));
                publishedOther = archiveService.publish(otherZip, destination, archiveService.sha256(otherZip));
                run.setOtherArchive(publishedOther.toString());
            }

            // the local copy is safe now; scratch space is no longer needed
            archiveService.cleanup(workDir);
            workDir = null;

            run.setCloudStatus(uploadToCloud(job, startedAt, publishedDb, publishedOther));

            int removed = retentionService.apply(destination);
            if (removed > 0) notes.add("Retention removed " + removed + " old archive(s)");

            run.setStatus(report.complete() ? BackupRunStatus.SUCCESS : BackupRunStatus.PARTIAL);
            run.setMessage(notes.isEmpty() ? null : String.join(" | ", notes));
        } catch (Exception e) {
            log.error("Backup of {} failed", job.label(), e);
            run.setStatus(BackupRunStatus.FAILED);
            run.setMessage(Helpers.rootMessage(e));
        } finally {
            if (workDir != null) archiveService.cleanup(workDir);
            running.remove(job.lockKey());
            Instant finishedAt = Instant.now();
            run.setFinishedAt(finishedAt);
            run.setDurationMs(Duration.between(startedAt, finishedAt).toMillis());
            save(run);
            logOutcome(job, run);
        }
        return run;
    }

    private String uploadToCloud(BackupJob job, Instant startedAt, Path dbArchive, Path otherArchive) {
        if (job.cloudType() == null || job.cloudConfig() == null) return "NOT_CONFIGURED";
        String remoteDir = "%s/%s".formatted(job.cloudDestinationDir(), Helpers.cloudDirName(startedAt));
        try {
            cloudUploadContext.uploadFileToCloud(job.cloudType(), dbArchive, remoteDir, job.cloudConfig());
            if (otherArchive != null) {
                cloudUploadContext.uploadFileToCloud(job.cloudType(), otherArchive, remoteDir, job.cloudConfig());
            }
        } catch (Exception e) {
            log.error("Cloud upload for {} failed; the local archive is kept at {}", job.label(), dbArchive, e);
            return "FAILED: " + Helpers.rootMessage(e);
        }

        try {
            int removed = cloudUploadContext.applyRetention(job.cloudType(), job.cloudDestinationDir(), job.cloudConfig());
            return removed > 0 ? "UPLOADED (retention removed %d)".formatted(removed) : "UPLOADED";
        } catch (Exception e) {
            log.error("Cloud retention for {} failed", job.label(), e);
            return "UPLOADED (retention failed: " + Helpers.rootMessage(e) + ")";
        }
    }

    private void logOutcome(BackupJob job, BackupRunEntity run) {
        String summary = "Backup of {} finished: {} in {} ms, tables={}, failed={}, rows={}, archive={}, cloud={}";
        Object[] args = {job.label(), run.getStatus(), run.getDurationMs(), run.getTablesTotal(), run.getTablesFailed(),
                run.getRowsDumped(), run.getDbArchive(), run.getCloudStatus()};
        switch (run.getStatus()) {
            case SUCCESS -> log.info(summary, args);
            case PARTIAL -> log.warn(summary + " - {}", append(args, run.getMessage()));
            default -> log.error(summary + " - {}", append(args, run.getMessage()));
        }
    }

    private static Object[] append(Object[] args, Object extra) {
        Object[] out = java.util.Arrays.copyOf(args, args.length + 1);
        out[args.length] = extra;
        return out;
    }

    private BackupRunEntity save(BackupRunEntity run) {
        try {
            return runRepository.save(run);
        } catch (Exception e) {
            // history is best effort; a database hiccup must not abort the backup itself
            log.error("Could not record backup run {}", run.getId(), e);
            return run;
        }
    }

    /** Runs left in RUNNING by a crash or restart can never finish; close them and clear scratch space. */
    @Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        List<BackupRunEntity> interrupted = runRepository.findByStatus(BackupRunStatus.RUNNING);
        for (BackupRunEntity run : interrupted) {
            run.setStatus(BackupRunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setMessage("Interrupted by application restart");
        }
        if (!interrupted.isEmpty()) {
            runRepository.saveAll(interrupted);
            log.warn("Marked {} interrupted backup run(s) as failed", interrupted.size());
        }

        archiveService.cleanup(properties.workDir().toAbsolutePath());
        try (Stream<Path> entries = Files.list(Path.of("").toAbsolutePath())) {
            entries.filter(Files::isDirectory)
                    .filter(p -> LEGACY_TEMP_DIR.matcher(p.getFileName().toString()).matches())
                    .forEach(p -> {
                        log.info("Removing leftover scratch directory {}", p);
                        archiveService.cleanup(p);
                    });
        } catch (IOException e) {
            log.warn("Could not scan for leftover scratch directories", e);
        }
    }
}
