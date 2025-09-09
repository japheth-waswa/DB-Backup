package com.zurimate.appbackup.config;

import com.zurimate.appbackup.service.BackupStrategyFactory;
import com.zurimate.appbackup.service.cloud.CloudUploadStrategyFactory;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.DBType;
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

    @Bean
    public Map<CloudType, CloudUploadStrategyFactory> cloudUploadType() {
        Map<CloudType, CloudUploadStrategyFactory> cloudUploadStrategyFactoryMap = new EnumMap<>(CloudType.class);
        Arrays.stream(CloudType.values())
                .forEach(cloudType -> cloudUploadStrategyFactoryMap.put(cloudType, new CloudUploadStrategyFactory(cloudType, applicationContext)));
        return cloudUploadStrategyFactoryMap;
    }
}
