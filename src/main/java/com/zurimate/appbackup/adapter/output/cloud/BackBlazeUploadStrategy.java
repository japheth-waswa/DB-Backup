package com.zurimate.appbackup.adapter.output.cloud;

import com.backblaze.b2.client.B2StorageClient;
import com.backblaze.b2.client.contentSources.B2ContentSource;
import com.backblaze.b2.client.contentSources.B2ContentTypes;
import com.backblaze.b2.client.contentSources.B2FileContentSource;
import com.backblaze.b2.client.structures.B2Bucket;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.backblaze.b2.client.webApiHttpClient.B2StorageHttpClientFactory;
import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.exception.InternalException;
import com.zurimate.appbackup.ports.output.CloudUploadStrategy;
import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.CloudTypeStrategyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component(CloudTypeStrategyConstants.BACK_BLAZE_STRATEGY)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class BackBlazeUploadStrategy implements CloudUploadStrategy {
    private static final long MAX_SMALL_FILE_SIZE = 5L * 1024 * 1024 * 1024; // 5 GB

    private B2StorageClient storageClient;

    @Override
    public void uploadFileToCloud(Path fileToUpload, String destinationPath, CloudConfig config) {
        log.info("Init file upload to BackBlaze cloud this file: {}",fileToUpload.toAbsolutePath());

        var backBlazeConfig = parseStorageClient(config);

        try {

            log.info("Actual upload to BackBlaze cloud");

            B2Bucket bucket = storageClient.getBucketOrNullByName(backBlazeConfig.getBucketName());

            if (bucket == null) {
                log.warn("Bucket not found: {}", backBlazeConfig.getBucketName());
                return;
            }

            log.info("BackBlaze Builder for file upload");

            B2ContentSource source = B2FileContentSource.build(fileToUpload.toFile());
            String fileNameInBucket = parseFileName(fileToUpload, destinationPath);

            long fileSize = Files.size(fileToUpload);

            B2UploadFileRequest request = B2UploadFileRequest
                    .builder(bucket.getBucketId(),
                            fileNameInBucket,
                            B2ContentTypes.B2_AUTO, source)
                    .build();

            if (fileSize < MAX_SMALL_FILE_SIZE) {
                log.info("Uploading small file [{}] ({} bytes) to bucket [{}]", fileNameInBucket, fileSize, bucket.getBucketName());
                storageClient.uploadSmallFile(request);
            } else {
                log.info("Uploading LARGE file [{}] ({} bytes) to bucket [{}]", fileNameInBucket, fileSize, bucket.getBucketName());
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    storageClient.uploadLargeFile(request, executor);
                }
            }

            log.info("Upload complete: {} -> {}", fileToUpload, fileNameInBucket);
        } catch (Exception e) {
            log.error("Error while uploading file to BackBlaze", e);
        }
    }


    private BackBlazeConfig parseStorageClient(CloudConfig config) {
        if (!(config instanceof BackBlazeConfig backBlazeConfig)) {
            var message = "Invalid config type; expected BackBlazeConfig";
            log.error(message);
            throw new InternalException(message);
        }

        if (storageClient == null) {
            B2StorageHttpClientFactory factory  = new B2StorageHttpClientFactory();
            storageClient = factory
                    .create(backBlazeConfig.getKeyId(),
                            backBlazeConfig.getApplicationKey(),
                            "java-backup-script");
        }

        return backBlazeConfig;
    }

    private String parseFileName(Path fileToUpload, String destinationPath) {
        if (!StringUtils.hasText(destinationPath)) return fileToUpload.getFileName().toString();

        return "%s/%s".formatted(destinationPath, fileToUpload.getFileName());
    }

}
