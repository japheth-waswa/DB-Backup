package com.zurimate.appbackup.scheduler;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import com.zurimate.appbackup.utils.SchedulerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cron triggers per {@link SchedulerType}. Jobs are only queued here; the dispatcher decides
 * when they actually run, so a slow backup never blocks the scheduler thread.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class Scheduler {
    private final BackupSchedulerService backupSchedulerService;
    private final BackupProperties properties;

    @Order(10)
    @EventListener(ApplicationReadyEvent.class)
    public void runBackupsOnStartup() {
        if (!properties.runOnStartup()) {
            log.info("Startup backups disabled (app.backup.run-on-startup=false)");
            return;
        }
        backupSchedulerService.processScheduledBackup(List.of(SchedulerType.values()), "STARTUP");
    }

    @Scheduled(cron = "0 0 * * * *")
    public void everyHourScheduler() {
        run(SchedulerType.HOURLY);
    }

    @Scheduled(cron = "0 0 0,6,12,18 * * *")
    public void everySixHourScheduler() {
        run(SchedulerType.EVERY_6_HOURS);
    }

    @Scheduled(cron = "0 0 0,12 * * *")
    public void everyTwelveHourScheduler() {
        run(SchedulerType.EVERY_12_HOURS);
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void dailyScheduler() {
        run(SchedulerType.DAILY);
    }

    /** Cron cannot express "every 72 hours"; the 1st, 4th, 7th... of each month is close and survives restarts. */
    @Scheduled(cron = "0 0 0 1/3 * *")
    public void threeDaysScheduler() {
        run(SchedulerType.EVERY_3_DAYS);
    }

    @Scheduled(cron = "0 0 0 * * SUN")
    public void weeklyScheduler() {
        run(SchedulerType.EVERY_WEEK);
    }

    @Scheduled(cron = "0 0 0 1,15 * *")
    public void fortnightScheduler() {
        run(SchedulerType.EVERY_FORTNIGHT);
    }

    @Scheduled(cron = "0 0 0 1 * *")
    public void everyMonthScheduler() {
        run(SchedulerType.MONTHLY);
    }

    @Scheduled(cron = "0 0 0 1 1 *")
    public void annualScheduler() {
        run(SchedulerType.ANNUALLY);
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeRunHistory() {
        int removed = backupSchedulerService.purgeRunHistory();
        if (removed > 0) log.info("Purged {} old backup run record(s)", removed);
    }

    private void run(SchedulerType type) {
        log.info("Running {} scheduler", type);
        try {
            backupSchedulerService.processScheduledBackup(type);
        } catch (Exception e) {
            log.error("{} scheduler failed to queue backups", type, e);
        }
    }
}
