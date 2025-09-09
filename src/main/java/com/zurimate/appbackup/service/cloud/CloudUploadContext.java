package com.zurimate.appbackup.service.cloud;

import com.zurimate.appbackup.ports.output.CloudUploadStrategy;
import com.zurimate.appbackup.utils.CloudType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudUploadContext {
    private final Map<CloudType, CloudUploadStrategyFactory> cloudUploadType;

    public void uploadFileToCloud(CloudType cloudType, Path fileToUpload, String destinationPath, CloudConfig config) {
        CloudUploadStrategyFactory strategyFactory = cloudUploadType.getOrDefault(cloudType, null);
        if (strategyFactory == null) {
            log.error("CloudType: {} does not have an existing implementation", cloudType);
            return;
        }

        //get a new instance of the strategy since each request can have different cloud configs
        log.info("Creating a new instance of cloud upload strategy");
        CloudUploadStrategy cloudUploadStrategy = strategyFactory.createInstance();

        //upload the zipped file
        log.info("Instance created for cloud upload");
        cloudUploadStrategy.uploadFileToCloud(fileToUpload, destinationPath, config);
    }
}
