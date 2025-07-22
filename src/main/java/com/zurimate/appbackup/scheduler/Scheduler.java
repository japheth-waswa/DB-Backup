package com.zurimate.appbackup.scheduler;

import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import com.zurimate.appbackup.utils.SchedulerType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@AllArgsConstructor
@Component
public class Scheduler {
    private final BackupSchedulerService backupSchedulerService;

    @EventListener(ApplicationReadyEvent.class)
    public void runBackupsOnStartup(){
        backupSchedulerService.processScheduledBackup(List.of(SchedulerType.values()));
    }

//    @Scheduled(cron = "0 * * * * *")
//    public void everyMinuteScheduler() {
//        log.info("Running minute scheduler at {}", LocalDateTime.now());
//        backupSchedulerService.processScheduledBackup(SchedulerType.MINUTE);
//    }

    @Scheduled(cron = "0 0 * * * *")
    public void everyHourScheduler() {
        log.info("Running hourly scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.HOURLY);
    }

    @Scheduled(cron = "0 0 0,6,12,18 * * *")
    public void everySixHourScheduler() {
        log.info("Running every 6 hours scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.EVERY_6_HOURS);
    }

    @Scheduled(cron = "0 0 0,12 * * *")
    public void everyTwelveHourScheduler() {
        log.info("Running every 12 hours scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.EVERY_12_HOURS);
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void dailyScheduler() {
        log.info("Running daily scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.DAILY);
    }

    @Scheduled(fixedRate = 259200000)
    public void threeDaysScheduler() {
        log.info("Running every 3 days scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.EVERY_3_DAYS);
    }

    @Scheduled(cron = "0 0 0 * * SUN")
    public void weeklyScheduler() {
        log.info("Running weekly scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.EVERY_WEEK);
    }

    @Scheduled(fixedRate = 1209600000)
    public void fortnightScheduler() {
        log.info("Running every fortnight scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.EVERY_FORTNIGHT);
    }

    @Scheduled(cron = "0 0 0 1 * *")
    public void everyMonthScheduler() {
        log.info("Running every month scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.MONTHLY);
    }

    @Scheduled(cron = "0 0 0 1 1 *")
    public void annualScheduler() {
        log.info("Running annual scheduler at {}", LocalDateTime.now());
        backupSchedulerService.processScheduledBackup(SchedulerType.ANNUALLY);
    }

}
