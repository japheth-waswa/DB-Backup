package com.zurimate.AppBackup.ports.output;

import com.zurimate.AppBackup.dto.BackupDestination;
import com.zurimate.AppBackup.dto.DBConfig;
import com.zurimate.AppBackup.utils.DBType;

import java.util.List;

public interface BackupStrategy {
    DBType dbType();

    BackupDestination backup(DBConfig databaseConfig, String destination, List<String> otherDirsToBackup);
}
