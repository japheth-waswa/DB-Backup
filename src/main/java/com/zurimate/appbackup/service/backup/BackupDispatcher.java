package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.config.BackupProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Queues backup jobs in the background. A semaphore bounds how many run at once; extra jobs
 * wait for a slot instead of being rejected, so a busy schedule never silently drops work.
 */
@Slf4j
@Component
public class BackupDispatcher {
    private final BackupRunner runner;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("backup-", 0).factory());

    public BackupDispatcher(BackupRunner runner, BackupProperties properties) {
        this.runner = runner;
        this.slots = new Semaphore(properties.maxConcurrentBackups(), true);
    }

    public void submit(BackupJob job) {
        executor.submit(() -> {
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                runner.execute(job);
            } catch (Exception e) {
                log.error("Unexpected error while running backup {}", job.label(), e);
            } finally {
                slots.release();
            }
        });
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Backups were still running at shutdown; they will be marked as interrupted on next start");
        }
    }
}
