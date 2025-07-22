package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.utils.DBType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DBConfig(@NotNull DBType dbType,
                       @NotNull String host,
                       @NotNull Integer port,
                       @NotBlank String username,
                       @NotBlank String password,
                       @NotBlank String dbName,
                       @NotNull Integer tableBatchesOf) {
}
