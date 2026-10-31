package com.zurimate.appbackup.service.cloud;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.exception.CloudUploadException;
import com.zurimate.appbackup.ports.output.CloudUploadStrategy;
import com.zurimate.appbackup.utils.CloudType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Picks the cloud strategy for a job and retries transient failures with a linear back-off. */
@Slf4j
@Component
public class CloudUploadContext {
    private final Map<CloudType, CloudUploadStrategy> strategies = new EnumMap<>(CloudType.class);
    private final BackupProperties properties;

    public CloudUploadContext(List<CloudUploadStrategy> strategies, BackupProperties properties) {
        strategies.forEach(s -> this.strategies.put(s.cloudType(), s));
        this.properties = properties;
    }

    public void uploadFileToCloud(CloudType cloudType, Path fileToUpload, String destinationPath, CloudConfig config) {
        CloudUploadStrategy strategy = strategy(cloudType);
        int attempts = Math.max(1, properties.cloud().uploadAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                strategy.uploadFileToCloud(fileToUpload, destinationPath, config);
                return;
            } catch (CloudUploadException e) {
                if (!e.isRetryable() || attempt >= attempts) throw e;
                Duration wait = Duration.ofSeconds((long) properties.cloud().retryDelaySeconds() * attempt);
                log.warn("Upload attempt {}/{} for {} failed ({}); retrying in {}s",
                        attempt, attempts, fileToUpload.getFileName(), e.getMessage(), wait.toSeconds());
                sleep(wait);
            }
        }
    }

    /** Deletes remote archives older than the configured retention; returns the number removed. */
    public int applyRetention(CloudType cloudType, String destinationPrefix, CloudConfig config) {
        int days = properties.cloud().retentionDays();
        if (days <= 0) return 0;
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        return strategy(cloudType).deleteOlderThan(destinationPrefix, cutoff, config);
    }

    private CloudUploadStrategy strategy(CloudType cloudType) {
        CloudUploadStrategy strategy = strategies.get(cloudType);
        if (strategy == null) throw new CloudUploadException("No implementation for cloud type " + cloudType, null, false);
        return strategy;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudUploadException("Interrupted while waiting to retry upload", e, false);
        }
    }
}
