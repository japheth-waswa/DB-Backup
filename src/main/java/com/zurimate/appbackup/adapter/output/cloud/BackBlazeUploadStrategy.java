package com.zurimate.appbackup.adapter.output.cloud;

import com.backblaze.b2.client.B2StorageClient;
import com.backblaze.b2.client.contentSources.B2ContentSource;
import com.backblaze.b2.client.contentSources.B2ContentTypes;
import com.backblaze.b2.client.contentSources.B2FileContentSource;
import com.backblaze.b2.client.exceptions.B2BadRequestException;
import com.backblaze.b2.client.exceptions.B2Exception;
import com.backblaze.b2.client.exceptions.B2ForbiddenException;
import com.backblaze.b2.client.exceptions.B2UnauthorizedException;
import com.backblaze.b2.client.structures.B2Bucket;
import com.backblaze.b2.client.structures.B2FileVersion;
import com.backblaze.b2.client.structures.B2ListFileVersionsRequest;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.backblaze.b2.client.webApiHttpClient.B2StorageHttpClientFactory;
import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.exception.CloudUploadException;
import com.zurimate.appbackup.exception.InternalException;
import com.zurimate.appbackup.ports.output.CloudUploadStrategy;
import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.CloudType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backblaze B2 uploads. A client is created per call and always closed, so credentials
 * from different jobs never mix and no HTTP connections are left behind.
 */
@Slf4j
@Component
class BackBlazeUploadStrategy implements CloudUploadStrategy {
    private static final long MAX_SMALL_FILE_SIZE = 200L * 1024 * 1024; // B2 recommends large-file API above this
    private static final String USER_AGENT = "AppBackup";

    @Override
    public CloudType cloudType() {
        return CloudType.BACK_BLAZE;
    }

    @Override
    public void uploadFileToCloud(Path fileToUpload, String destinationPath, CloudConfig config) {
        BackBlazeConfig b2Config = asBackBlaze(config);
        String fileNameInBucket = StringUtils.hasText(destinationPath)
                ? "%s/%s".formatted(destinationPath, fileToUpload.getFileName())
                : fileToUpload.getFileName().toString();

        try (B2StorageClient client = createClient(b2Config)) {
            B2Bucket bucket = requireBucket(client, b2Config);
            long fileSize = Files.size(fileToUpload);
            B2ContentSource source = B2FileContentSource.build(fileToUpload.toFile());
            B2UploadFileRequest request = B2UploadFileRequest
                    .builder(bucket.getBucketId(), fileNameInBucket, B2ContentTypes.B2_AUTO, source)
                    .build();

            log.info("Uploading [{}] ({} bytes) to bucket [{}]", fileNameInBucket, fileSize, bucket.getBucketName());
            if (fileSize < MAX_SMALL_FILE_SIZE) {
                client.uploadSmallFile(request);
            } else {
                try (ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
                    client.uploadLargeFile(request, executor);
                }
            }
            log.info("Upload complete: {} -> {}", fileToUpload, fileNameInBucket);
        } catch (B2Exception e) {
            throw new CloudUploadException("BackBlaze upload of %s failed: %s".formatted(fileToUpload.getFileName(), e.getMessage()),
                    e, isRetryable(e));
        } catch (IOException e) {
            throw new CloudUploadException("Cannot read %s: %s".formatted(fileToUpload, e.getMessage()), e, false);
        }
    }

    @Override
    public int deleteOlderThan(String prefix, Instant cutoff, CloudConfig config) {
        if (!StringUtils.hasText(prefix)) {
            // never sweep a whole bucket
            throw new InternalException("Refusing to apply cloud retention without a destination prefix");
        }
        BackBlazeConfig b2Config = asBackBlaze(config);
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        try (B2StorageClient client = createClient(b2Config)) {
            B2Bucket bucket = requireBucket(client, b2Config);
            List<B2FileVersion> expired = new ArrayList<>();
            for (B2FileVersion version : client.fileVersions(B2ListFileVersionsRequest.builder(bucket.getBucketId())
                    .setPrefix(normalizedPrefix).build())) {
                if (version.getUploadTimestamp() < cutoff.toEpochMilli()) expired.add(version);
            }
            for (B2FileVersion version : expired) {
                client.deleteFileVersion(version);
                log.info("Deleted expired cloud backup {}", version.getFileName());
            }
            return expired.size();
        } catch (B2Exception e) {
            throw new CloudUploadException("BackBlaze retention under %s failed: %s".formatted(normalizedPrefix, e.getMessage()),
                    e, isRetryable(e));
        }
    }

    private static B2StorageClient createClient(BackBlazeConfig config) {
        // instantiated directly (not via the reflective default factory) so it works in the native image
        return new B2StorageHttpClientFactory().create(config.getKeyId(), config.getApplicationKey(), USER_AGENT);
    }

    private static B2Bucket requireBucket(B2StorageClient client, BackBlazeConfig config) throws B2Exception {
        B2Bucket bucket = client.getBucketOrNullByName(config.getBucketName());
        if (bucket == null) {
            throw new CloudUploadException("Bucket not found: " + config.getBucketName(), null, false);
        }
        return bucket;
    }

    /** Authorization problems, exceeded caps and bad requests will not fix themselves on retry. */
    private static boolean isRetryable(B2Exception e) {
        return !(e instanceof B2ForbiddenException
                || e instanceof B2UnauthorizedException
                || e instanceof B2BadRequestException);
    }

    private static BackBlazeConfig asBackBlaze(CloudConfig config) {
        if (config instanceof BackBlazeConfig backBlazeConfig) return backBlazeConfig;
        throw new InternalException("Invalid config type; expected BackBlazeConfig");
    }
}
