package com.zurimate.appbackup.config;

import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;

public record BackBlazeTestConfig(String keyId,
                                  String keyName,
                                  String applicationKey,
                                  String bucketName) {
    
    public BackBlazeConfig toBackBlazeConfig(){
//        return new BackBlazeConfig(keyId(),keyName(),applicationKey(),bucketName());
        return BackBlazeConfig.builder()
                .keyId(keyId())
                .keyName(keyName())
                .applicationKey(applicationKey())
                .bucketName(bucketName())
                .build();
    }
    
}
