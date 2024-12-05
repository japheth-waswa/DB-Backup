package com.zurimate.AppBackup.service;

import com.zurimate.AppBackup.dto.BackupDestination;
import com.zurimate.AppBackup.dto.DBConfig;
import com.zurimate.AppBackup.ports.output.BackupStrategy;
import com.zurimate.AppBackup.utils.DBType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class BackupContext {
    private final Map<DBType, BackupStrategyFactory> backupDataByDBType;

    public BackupDestination backup(DBConfig dbConfig, String destination, List<String> otherDirsToBackup) {
        BackupStrategyFactory strategyFactory = backupDataByDBType.getOrDefault(dbConfig.dbType(), null);
        if (Objects.isNull(strategyFactory)) {
            log.error("DBType: {} does not have an existing implementation", dbConfig.dbType());
            return new BackupDestination(null,null);
        }
        //get a new instance of the strategy since each request can have different db configs
        BackupStrategy backupStrategy = strategyFactory.createInstance();
       return backupStrategy.backup(dbConfig, destination, otherDirsToBackup);
    }
}
