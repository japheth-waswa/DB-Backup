package com.zurimate.appbackup.adapter.output.db;

import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.service.cloud.CloudUploadContext;
import com.zurimate.appbackup.utils.CloudType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.zurimate.appbackup.utils.Helpers.parseFileNameWithDateTime;

@Slf4j
@RequiredArgsConstructor
@Component
public class DbFilesToCloud {
    private final CloudUploadContext cloudUploadContext;

    public void uploadFilesToCloud(Path dbZipFileDestination,
                                   Path othersZipFileDestination,
                                   CloudType cloudType,
                                   String cloudDestinationDir,
                                   CloudConfig cloudConfig) {
        log.info("Request to upload files to cloud initiated with , cloudType:{},  {}", cloudType, cloudConfig);
        if (cloudType == null || cloudConfig == null) {
            log.warn("Terminated request to upload files to cloud, cloudType:{},  {}", cloudType, cloudConfig);
            return;
        }

        //parse destinationDir
        var parsedCloudDestinationDir = "%s/%s".formatted(cloudDestinationDir, parseFileNameWithDateTime());
        log.info("Cloud destination dir: {}",parsedCloudDestinationDir);

        if (dbZipFileDestination != null && Files.exists(dbZipFileDestination)) {
            log.info("Found: {} for upload to cloud",dbZipFileDestination.toAbsolutePath());
            //upload db files
            cloudUploadContext.uploadFileToCloud(cloudType, dbZipFileDestination, parsedCloudDestinationDir, cloudConfig);
        }

        if (othersZipFileDestination != null && Files.exists(othersZipFileDestination)) {
            log.info("Found: {} for upload to cloud",othersZipFileDestination.toAbsolutePath());
            //upload other files
            cloudUploadContext.uploadFileToCloud(cloudType, othersZipFileDestination, parsedCloudDestinationDir, cloudConfig);
        }
    }
}
