package com.zurimate.appbackup.service.cloud;

import com.zurimate.appbackup.ports.output.CloudUploadStrategy;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.Helpers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;

@RequiredArgsConstructor
public class CloudUploadStrategyFactory {
    private final CloudType cloudType;
    private final ApplicationContext applicationContext;

    public CloudUploadStrategy createInstance() {
        return Helpers.createNewCloudUploadStrategyInstance(applicationContext, cloudType);
    }
}
