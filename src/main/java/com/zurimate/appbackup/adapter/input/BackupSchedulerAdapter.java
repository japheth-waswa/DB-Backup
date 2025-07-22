package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.data.BackupEntity;
import com.zurimate.appbackup.data.BackupRepository;
import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import com.zurimate.appbackup.service.BackupContext;
import com.zurimate.appbackup.utils.SchedulerType;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

@Validated
@AllArgsConstructor
@Component
public class BackupSchedulerAdapter implements BackupSchedulerService {
    private final BackupRepository backupRepository;
    private final BackupContext backupContext;

    @Override
    public BackupDestination createScheduledBackup(BackupRequest backupRequest) {
        //create record
        backupRepository.save(BackupEntity.builder()
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
                .build());

        return backupContext.backup(backupRequest.dbConfig(),
                backupRequest.destination(),
                backupRequest.otherDirsToBackup());
    }

    @Override
    public void processScheduledBackup(SchedulerType schedulerType) {
        var scheduledBackups = backupRepository.findAllBySchedulerType(schedulerType);
        if (scheduledBackups.isEmpty()) return;
        scheduledBackups.forEach(this::processBackup);
    }

    @Override
    public void processScheduledBackup(List<SchedulerType> schedulerTypes) {
        var scheduledBackups = backupRepository.findAllBySchedulerTypeIn(schedulerTypes);
        if (scheduledBackups.isEmpty()) return;
        scheduledBackups.forEach(this::processBackup);
    }

    private void processBackup(BackupEntity backup) {
        backupContext
                .backup(new DBConfig(backup.getDbType(),
                                backup.getHost(),
                                backup.getPort(),
                                backup.getUsername(),
                                backup.getPassword(),
                                backup.getDbName(),
                                backup.getTableBatchesOf()),
                        backup.getDestination(),
                        backup.getOtherDirsToBackup());
    }
}
