package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BackupRequest(@NotNull SchedulerType schedulerType,
                            @NotNull @Valid DBConfig dbConfig,
                            @NotNull String destination,
                            List<String> otherDirsToBackup,
                            CloudType cloudType,
                            String cloudDestinationDir,
                            @Valid BackBlazeConfig blazeCloudConfig) {
}
