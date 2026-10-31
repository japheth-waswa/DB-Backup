package com.zurimate.appbackup.service.cloud;

import com.zurimate.appbackup.exception.CloudUploadException;
import com.zurimate.appbackup.ports.output.CloudUploadStrategy;
import com.zurimate.appbackup.service.backup.TestProps;
import com.zurimate.appbackup.utils.CloudType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudUploadContextTest {

    static final class ScriptedStrategy implements CloudUploadStrategy {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Instant> cutoff = new AtomicReference<>();
        int failures;
        boolean retryable = true;

        @Override
        public CloudType cloudType() {
            return CloudType.BACK_BLAZE;
        }

        @Override
        public void uploadFileToCloud(Path fileToUpload, String destinationPath, CloudConfig config) {
            if (calls.incrementAndGet() <= failures) throw new CloudUploadException("boom", null, retryable);
        }

        @Override
        public int deleteOlderThan(String prefix, Instant cutoff, CloudConfig config) {
            this.cutoff.set(cutoff);
            return 4;
        }
    }

    private static CloudUploadContext context(ScriptedStrategy strategy, int attempts, int retentionDays) {
        return new CloudUploadContext(List.of(strategy), TestProps.with(Path.of("w"), 0, 0, attempts, retentionDays));
    }

    @Test
    void retriesTransientFailures() {
        ScriptedStrategy strategy = new ScriptedStrategy();
        strategy.failures = 2;
        context(strategy, 3, 0).uploadFileToCloud(CloudType.BACK_BLAZE, Path.of("f"), "d", null);
        assertThat(strategy.calls.get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        ScriptedStrategy strategy = new ScriptedStrategy();
        strategy.failures = 10;
        assertThatThrownBy(() -> context(strategy, 3, 0).uploadFileToCloud(CloudType.BACK_BLAZE, Path.of("f"), "d", null))
                .isInstanceOf(CloudUploadException.class);
        assertThat(strategy.calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryPermanentErrorsLikeAnExceededStorageCap() {
        ScriptedStrategy strategy = new ScriptedStrategy();
        strategy.failures = 10;
        strategy.retryable = false;
        assertThatThrownBy(() -> context(strategy, 5, 0).uploadFileToCloud(CloudType.BACK_BLAZE, Path.of("f"), "d", null))
                .isInstanceOf(CloudUploadException.class);
        assertThat(strategy.calls.get()).isEqualTo(1);
    }

    @Test
    void retentionOnlyRunsWhenConfigured() {
        ScriptedStrategy strategy = new ScriptedStrategy();
        assertThat(context(strategy, 1, 0).applyRetention(CloudType.BACK_BLAZE, "erp", null)).isZero();
        assertThat(strategy.cutoff.get()).isNull();

        assertThat(context(strategy, 1, 30).applyRetention(CloudType.BACK_BLAZE, "erp", null)).isEqualTo(4);
        assertThat(strategy.cutoff.get()).isBetween(Instant.now().minus(Duration.ofDays(30)).minusSeconds(5),
                Instant.now().minus(Duration.ofDays(30)).plusSeconds(5));
    }

    @Test
    void unknownCloudTypeIsAPermanentError() {
        CloudUploadContext empty = new CloudUploadContext(List.of(), TestProps.with(Path.of("w"), 0, 0, 3, 0));
        assertThatThrownBy(() -> empty.uploadFileToCloud(CloudType.BACK_BLAZE, Path.of("f"), "d", null))
                .isInstanceOfSatisfying(CloudUploadException.class, e -> assertThat(e.isRetryable()).isFalse());
    }
}
