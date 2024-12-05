package com.zurimate.AppBackup.dto;

import com.zurimate.AppBackup.utils.DBType;

public record DBConfig(DBType dbType,
                       String host,
                       int port,
                       String username,
                       String password,
                       String dbName,
                       int tableBatchesOf) {
}
