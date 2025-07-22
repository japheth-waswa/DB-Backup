package com.zurimate.appbackup.ports.output;

import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.utils.DBType;

import java.util.List;

public interface BackupStrategy {
    DBType dbType();

    BackupDestination backup(DBConfig databaseConfig, String destination, List<String> otherDirsToBackup);
}
