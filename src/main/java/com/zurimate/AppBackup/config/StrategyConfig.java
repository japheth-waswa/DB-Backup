package com.zurimate.AppBackup.config;

import com.zurimate.AppBackup.service.BackupStrategyFactory;
import com.zurimate.AppBackup.utils.DBType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
class StrategyConfig {
    private final ApplicationContext applicationContext;

    @Bean
    public Map<DBType, BackupStrategyFactory> backupDataByDBType() {
        Map<DBType, BackupStrategyFactory> backupStrategyFactoryMap = new EnumMap<>(DBType.class);
        Arrays.stream(DBType.values())
                .forEach(dbType -> backupStrategyFactoryMap.put(dbType, new BackupStrategyFactory(dbType, applicationContext)));
        return backupStrategyFactoryMap;
    }
}
