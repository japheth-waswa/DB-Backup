package com.zurimate.appbackup.ports.input;

import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.dto.BackupRunView;
import com.zurimate.appbackup.dto.BackupSummary;
import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Use cases exposed to the REST API and the scheduler: registering jobs, triggering runs and
 * reading their history. Implementations never return credentials.
 */
public interface BackupSchedulerService {
    BackupSummary createScheduledBackup(@NotNull @Valid BackupRequest backupRequest);

    void processScheduledBackup(SchedulerType schedulerType);

    void processScheduledBackup(List<SchedulerType> schedulerTypes, String trigger);

    void initiateAllBackups();

    void triggerBackup(UUID backupId);

    List<BackupSummary> listBackups();

    BackupSummary getBackup(UUID backupId);

    List<BackupRunView> listRuns(UUID backupId, int limit);

    void deleteBackup(UUID backupId);

    int purgeRunHistory();
}
