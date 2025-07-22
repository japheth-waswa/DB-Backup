package com.zurimate.appbackup.service;

import com.zurimate.appbackup.ports.output.BackupStrategy;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.Helpers;
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
