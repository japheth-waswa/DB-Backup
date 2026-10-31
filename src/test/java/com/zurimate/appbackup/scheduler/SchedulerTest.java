package com.zurimate.appbackup.scheduler;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import com.zurimate.appbackup.utils.SchedulerType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SchedulerTest {

    private static BackupProperties props(boolean runOnStartup) {
        return new BackupProperties(Path.of("w"), 2, 3, 100, 100, 5, runOnStartup, 0,
                new BackupProperties.Retention(0, 90), new BackupProperties.Cloud(1, 0, 0));
    }

    @Test
    void startupRunIsOptional() {
        BackupSchedulerService service = mock(BackupSchedulerService.class);
        new Scheduler(service, props(false)).runBackupsOnStartup();
        verify(service, never()).processScheduledBackup(any(List.class), anyString());

        new Scheduler(service, props(true)).runBackupsOnStartup();
        verify(service).processScheduledBackup(List.of(SchedulerType.values()), "STARTUP");
    }

    @Test
    void eachTriggerQueuesItsTypeAndSurvivesFailures() {
        BackupSchedulerService service = mock(BackupSchedulerService.class);
        doThrow(new IllegalStateException("db down")).when(service).processScheduledBackup(SchedulerType.HOURLY);
        Scheduler scheduler = new Scheduler(service, props(false));

        scheduler.everyHourScheduler();
        scheduler.everySixHourScheduler();
        scheduler.everyTwelveHourScheduler();
        scheduler.dailyScheduler();
        scheduler.threeDaysScheduler();
        scheduler.weeklyScheduler();
        scheduler.fortnightScheduler();
        scheduler.everyMonthScheduler();
        scheduler.annualScheduler();
        scheduler.purgeRunHistory();

        for (SchedulerType type : SchedulerType.values()) {
            if (type != SchedulerType.MINUTE) verify(service).processScheduledBackup(type);
        }
        verify(service).purgeRunHistory();
    }
}
