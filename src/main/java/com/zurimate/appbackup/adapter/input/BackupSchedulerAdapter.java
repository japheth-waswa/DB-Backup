package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.data.BackupEntity;
import com.zurimate.appbackup.data.BackupRepository;
import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.exception.InvalidPayloadException;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import com.zurimate.appbackup.service.BackupContext;
import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.SchedulerType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.zurimate.appbackup.utils.CloudType.BACK_BLAZE;

@Slf4j
@Validated
@AllArgsConstructor
@Component
public class BackupSchedulerAdapter implements BackupSchedulerService {

    private static final ExecutorService BACKUP_EXECUTOR = new ThreadPoolExecutor(
            0,
            10, // max parallel backups at once
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            Thread.ofVirtual().factory()
    );

    private final BackupRepository backupRepository;
    private final BackupContext backupContext;

    private CloudConfig parseCloudConfig(BackupRequest backupRequest) {
        if (backupRequest.cloudType() == null) return null;

        //validate BACK_BLAZE
        if (BACK_BLAZE.equals(backupRequest.cloudType())
                && backupRequest.blazeCloudConfig() == null) {
            throw new InvalidPayloadException("blazeCloudConfig must be provided for %s".formatted(BACK_BLAZE.name()));
        } else if (BACK_BLAZE.equals(backupRequest.cloudType())) {
            return backupRequest.blazeCloudConfig();
        }

        return null;
    }

    private CloudConfig parseCloudConfig(BackupEntity entity) {
        if (entity.getCloudType() == null) return null;

        //return BACK_BLAZE
        if (BACK_BLAZE.equals(entity.getCloudType())
                && entity.getBackBlazeConfig() != null) {
            return entity.getBackBlazeConfig();
        }

        return null;
    }

    @Override
    public BackupDestination createScheduledBackup(BackupRequest backupRequest) {
        //validate cloud type
        var cloudConfig = parseCloudConfig(backupRequest);

        //create record
        var backupEntity = BackupEntity.builder()
                .id(UUID.randomUUID())
                .schedulerType(backupRequest.schedulerType())
                .dbType(backupRequest.dbConfig().dbType())
                .host(backupRequest.dbConfig().host())
                .port(backupRequest.dbConfig().port())
                .username(backupRequest.dbConfig().username())
                .password(backupRequest.dbConfig().password())
                .dbName(backupRequest.dbConfig().dbName())
                .tableBatchesOf(backupRequest.dbConfig().tableBatchesOf())
                .destination(backupRequest.destination())
                .otherDirsToBackup(backupRequest.otherDirsToBackup())
                .cloudType(backupRequest.cloudType())
                .cloudDestinationDir(backupRequest.cloudDestinationDir())
                .build();

        //setup BackBlazeConfig
        backupEntity.setBackBlazeConfig(backupRequest.blazeCloudConfig());
        backupRepository.save(backupEntity);

        return backupContext.backup(backupRequest.dbConfig(),
                backupRequest.destination(),
                backupRequest.otherDirsToBackup(),
                backupRequest.cloudType(),
                backupRequest.cloudDestinationDir(),
                cloudConfig);
    }

    @Override
    public void processScheduledBackup(SchedulerType schedulerType) {
        var scheduledBackups = backupRepository.findAllBySchedulerType(schedulerType);
        if (CollectionUtils.isEmpty(scheduledBackups)) return;
        processBackup(scheduledBackups);
    }

    @Override
    public void processScheduledBackup(List<SchedulerType> schedulerTypes) {
        var scheduledBackups = backupRepository.findAllBySchedulerTypeIn(schedulerTypes);
        if (CollectionUtils.isEmpty(scheduledBackups)) return;
        processBackup(scheduledBackups);
    }

    @Override
    public void initiateAllBackups() {
        var scheduledBackups = backupRepository.findAll();
        if (CollectionUtils.isEmpty(scheduledBackups)) return;
        processBackup(scheduledBackups);
    }

    private void processBackup(List<BackupEntity> backups) {
        if (CollectionUtils.isEmpty(backups)) return;
        for (BackupEntity backup : backups) {
            BACKUP_EXECUTOR.submit(() -> {
                try {
                    processBackup(backup);
                } catch (Exception e) {
                    log.error("Backup failed for {}", backup.getId(), e);
                }
            });
        }
    }

    private void processBackup(BackupEntity backup) {
        var cloudConfig = parseCloudConfig(backup);
        backupContext
                .backup(new DBConfig(backup.getDbType(),
                                backup.getHost(),
                                backup.getPort(),
                                backup.getUsername(),
                                backup.getPassword(),
                                backup.getDbName(),
                                backup.getTableBatchesOf()),
                        backup.getDestination(),
                        backup.getOtherDirsToBackup(),
                        backup.getCloudType(),
                        backup.getCloudDestinationDir(),
                        cloudConfig);
    }
}
