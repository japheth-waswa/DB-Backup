package com.zurimate.AppBackup.service;

import com.zurimate.AppBackup.ports.output.BackupStrategy;
import com.zurimate.AppBackup.utils.DBType;
import com.zurimate.AppBackup.utils.Helpers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;

@RequiredArgsConstructor
public class BackupStrategyFactory {
    private final DBType dbType;
    private final ApplicationContext applicationContext;

    public BackupStrategy createInstance(){
        return Helpers.createNewBackupStrategyInstance(applicationContext,dbType);
    }
}
