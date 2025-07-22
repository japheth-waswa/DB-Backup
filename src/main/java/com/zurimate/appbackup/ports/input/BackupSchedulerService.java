package com.zurimate.appbackup.ports.input;

import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public interface BackupSchedulerService {
    BackupDestination createScheduledBackup(@NotNull @Valid BackupRequest backupRequest);

    void processScheduledBackup(SchedulerType schedulerType);

    void processScheduledBackup(List<SchedulerType> schedulerTypes);
}
