package com.zurimate.appbackup.service;

import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.ports.output.BackupStrategy;
import com.zurimate.appbackup.utils.DBType;
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
