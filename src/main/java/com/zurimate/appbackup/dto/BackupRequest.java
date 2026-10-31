package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Registers a backup job. The first run starts immediately; later runs follow {@code schedulerType}.
 * {@code cloudType}, {@code cloudDestinationDir} and the matching cloud config go together.
 */
public record BackupRequest(@NotNull SchedulerType schedulerType,
                            @NotNull @Valid DBConfig dbConfig,
                            @NotBlank String destination,
                            List<String> otherDirsToBackup,
                            CloudType cloudType,
                            String cloudDestinationDir,
                            @Valid BackBlazeConfig blazeCloudConfig) {
}
