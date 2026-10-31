package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.config.BackupProperties;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.utils.DBType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupDispatcherTest {

    @Test
    void runsEveryJobButNeverMoreThanTheConfiguredNumberAtOnce() throws Exception {
        BackupRunner runner = mock(BackupRunner.class);
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(6);
        when(runner.execute(any())).thenAnswer(inv -> {
            peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            Thread.sleep(40);
            live.decrementAndGet();
            done.countDown();
            if (done.getCount() == 3) throw new IllegalStateException("one bad job must not stop the rest");
            return null;
        });
        BackupProperties props = new BackupProperties(Path.of("w"), 2, 3, 100, 100, 5, false, 0,
                new BackupProperties.Retention(0, 90), new BackupProperties.Cloud(1, 0, 0));
        BackupDispatcher dispatcher = new BackupDispatcher(runner, props);

        for (int i = 0; i < 6; i++) {
            dispatcher.submit(new BackupJob(UUID.randomUUID(), "TEST",
                    new DBConfig(DBType.MYSQL, "h", 3306, "u", "p", "db" + i, 1), "/tmp", List.of(), null, null, null));
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(peak.get()).isEqualTo(2);
        dispatcher.shutdown();
    }

    @Test
    void lockKeyFallsBackToTheDatabaseIdentity() {
        DBConfig db = new DBConfig(DBType.MYSQL, "erp", 3306, "u", "p", "erpnext", 1);
        assertThat(new BackupJob(null, "T", db, "/b", null, null, null, null).lockKey()).isEqualTo("MYSQL:erp:3306/erpnext");
        UUID id = UUID.randomUUID();
        assertThat(new BackupJob(id, "T", db, "/b", null, null, null, null).lockKey()).isEqualTo(id.toString());
        assertThat(new BackupJob(id, "T", db, "/b", null, null, null, null).otherDirsToBackup()).isEmpty();
    }
}
