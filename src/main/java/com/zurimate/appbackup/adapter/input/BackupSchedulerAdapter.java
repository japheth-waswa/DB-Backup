package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.data.BackupEntity;
import com.zurimate.appbackup.data.BackupRepository;
import com.zurimate.appbackup.data.BackupRunEntity;
import com.zurimate.appbackup.data.BackupRunRepository;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.dto.BackupRunView;
import com.zurimate.appbackup.dto.BackupSummary;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.exception.InvalidPayloadException;
import com.zurimate.appbackup.exception.NotFoundException;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import com.zurimate.appbackup.service.backup.BackupDispatcher;
import com.zurimate.appbackup.service.backup.BackupJob;
import com.zurimate.appbackup.service.backup.DatabaseDumper;
import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.SchedulerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.zurimate.appbackup.utils.CloudType.BACK_BLAZE;

/**
 * Implements the backup use cases: validates requests per engine, persists jobs and hands runs to
 * the {@link BackupDispatcher}. Never blocks the caller on a running backup.
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@Component
public class BackupSchedulerAdapter implements BackupSchedulerService {
    static final String TRIGGER_SCHEDULE = "SCHEDULE";
    static final String TRIGGER_MANUAL = "MANUAL";
    static final String TRIGGER_REGISTRATION = "REGISTRATION";

    private final BackupRepository backupRepository;
    private final BackupRunRepository runRepository;
    private final BackupDispatcher dispatcher;
    private final BackupProperties properties;
    private final DatabaseDumper dumper;

    private CloudConfig parseCloudConfig(BackupRequest backupRequest) {
        if (backupRequest.cloudType() == null) return null;
        if (BACK_BLAZE.equals(backupRequest.cloudType())) {
            if (backupRequest.blazeCloudConfig() == null) {
                throw new InvalidPayloadException("blazeCloudConfig must be provided for %s".formatted(BACK_BLAZE.name()));
            }
            if (!StringUtils.hasText(backupRequest.cloudDestinationDir())) {
                throw new InvalidPayloadException("cloudDestinationDir must be provided when cloudType is set");
            }
            return backupRequest.blazeCloudConfig();
        }
        return null;
    }

    private static CloudConfig parseCloudConfig(BackupEntity entity) {
        if (BACK_BLAZE.equals(entity.getCloudType())) return entity.getBackBlazeConfig();
        return null;
    }

    private void validate(BackupRequest request) {
        DBConfig db = request.dbConfig();
        if (!dumper.supports(db.dbType())) {
            throw new InvalidPayloadException("Database type %s is not built into this AppBackup edition (build without -Dslim for all engines)".formatted(db.dbType()));
        }
        if (db.dbType().requiresCredentials() && (!StringUtils.hasText(db.username()) || !StringUtils.hasText(db.password()))) {
            throw new InvalidPayloadException("username and password are required for %s".formatted(db.dbType()));
        }
        if (db.dbType() == DBType.REDIS && !db.dbName().strip().matches("\\d+")) {
            throw new InvalidPayloadException("For REDIS, dbName must be the database index (0, 1, ...)");
        }
        if (!java.nio.file.Path.of(request.destination()).isAbsolute()) {
            throw new InvalidPayloadException("destination must be an absolute path");
        }
        parseCloudConfig(request);
    }

    @Override
    public BackupSummary createScheduledBackup(BackupRequest backupRequest) {
        validate(backupRequest);

        var backupEntity = BackupEntity.builder()
                .id(UUID.randomUUID())
                .schedulerType(backupRequest.schedulerType())
                .dbType(backupRequest.dbConfig().dbType())
                .host(backupRequest.dbConfig().host())
                .port(backupRequest.dbConfig().port())
                .username(backupRequest.dbConfig().username())
                .password(backupRequest.dbConfig().password())
                .dbName(backupRequest.dbConfig().dbName())
                .tableBatchesOf(backupRequest.dbConfig().parallelism())
                .connectionOptions(backupRequest.dbConfig().options())
                .destination(backupRequest.destination())
                .otherDirsToBackup(backupRequest.otherDirsToBackup())
                .cloudType(backupRequest.cloudType())
                .cloudDestinationDir(backupRequest.cloudDestinationDir())
                .build();
        backupEntity.setBackBlazeConfig(backupRequest.blazeCloudConfig());
        backupEntity = backupRepository.save(backupEntity);
        log.info("Registered backup {} for {} {}", backupEntity.getId(), backupEntity.getDbType(), backupEntity.getDbName());

        dispatcher.submit(toJob(backupEntity, TRIGGER_REGISTRATION));
        return toSummary(backupEntity);
    }

    @Override
    public void processScheduledBackup(SchedulerType schedulerType) {
        dispatchAll(backupRepository.findAllBySchedulerType(schedulerType), TRIGGER_SCHEDULE);
    }

    @Override
    public void processScheduledBackup(List<SchedulerType> schedulerTypes, String trigger) {
        dispatchAll(backupRepository.findAllBySchedulerTypeIn(schedulerTypes), trigger);
    }

    @Override
    public void initiateAllBackups() {
        dispatchAll(backupRepository.findAll(), TRIGGER_MANUAL);
    }

    @Override
    public void triggerBackup(UUID backupId) {
        dispatcher.submit(toJob(find(backupId), TRIGGER_MANUAL));
    }

    @Override
    public List<BackupSummary> listBackups() {
        return backupRepository.findAll().stream()
                .sorted(Comparator.comparing(BackupEntity::getCreatedAt))
                .map(this::toSummary)
                .toList();
    }

    @Override
    public BackupSummary getBackup(UUID backupId) {
        return toSummary(find(backupId));
    }

    @Override
    public List<BackupRunView> listRuns(UUID backupId, int limit) {
        find(backupId);
        int bounded = Math.clamp(limit, 1, 200);
        return runRepository.findByBackupIdOrderByStartedAtDesc(backupId, Limit.of(bounded)).stream()
                .map(BackupSchedulerAdapter::toView)
                .toList();
    }

    @Override
    @Transactional
    public void deleteBackup(UUID backupId) {
        BackupEntity entity = find(backupId);
        runRepository.deleteByBackupIdBulk(backupId);
        backupRepository.delete(entity);
        log.info("Deleted backup {} ({} {}); existing archives were left in place", backupId, entity.getDbType(), entity.getDbName());
    }

    @Override
    public int purgeRunHistory() {
        int days = properties.retention().runHistoryDays();
        if (days <= 0) return 0;
        return runRepository.deleteStartedBefore(Instant.now().minus(Duration.ofDays(days)));
    }

    private void dispatchAll(List<BackupEntity> backups, String trigger) {
        if (backups == null || backups.isEmpty()) return;
        for (BackupEntity backup : backups) {
            try {
                dispatcher.submit(toJob(backup, trigger));
            } catch (Exception e) {
                // one broken job (e.g. undecryptable credentials) must not stop the others
                log.error("Could not queue backup {}", backup.getId(), e);
            }
        }
    }

    private BackupEntity find(UUID backupId) {
        return backupRepository.findById(backupId)
                .orElseThrow(() -> new NotFoundException("Backup %s not found".formatted(backupId)));
    }

    private static BackupJob toJob(BackupEntity backup, String trigger) {
        return new BackupJob(backup.getId(),
                trigger,
                new DBConfig(backup.getDbType(),
                        backup.getHost(),
                        backup.getPort(),
                        backup.getUsername(),
                        backup.getPassword(),
                        backup.getDbName(),
                        backup.getTableBatchesOf(),
                        backup.getConnectionOptions()),
                backup.getDestination(),
                backup.getOtherDirsToBackup(),
                backup.getCloudType(),
                backup.getCloudDestinationDir(),
                parseCloudConfig(backup));
    }

    private BackupSummary toSummary(BackupEntity e) {
        BackupRunView lastRun = runRepository.findFirstByBackupIdOrderByStartedAtDesc(e.getId())
                .map(BackupSchedulerAdapter::toView)
                .orElse(null);
        return new BackupSummary(e.getId(), e.getSchedulerType(), e.getDbType(), e.getHost(), e.getPort(), e.getDbName(),
                e.getDestination(), e.getOtherDirsToBackup(), e.getCloudType(), e.getCloudDestinationDir(),
                e.getCreatedAt(), lastRun);
    }

    private static BackupRunView toView(BackupRunEntity r) {
        return new BackupRunView(r.getId(), r.getBackupId(), r.getStatus(), r.getTriggeredBy(), r.getStartedAt(),
                r.getFinishedAt(), r.getDurationMs(), r.getTablesTotal(), r.getTablesFailed(), r.getRowsDumped(),
                r.getDbArchive(), r.getDbArchiveBytes(), r.getDbArchiveSha256(), r.getOtherArchive(),
                r.getCloudStatus(), r.getMessage());
    }
}
