package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.data.BackupEntity;
import com.zurimate.appbackup.data.BackupRepository;
import com.zurimate.appbackup.data.BackupRunRepository;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.exception.InvalidPayloadException;
import com.zurimate.appbackup.exception.NotFoundException;
import com.zurimate.appbackup.service.backup.BackupDispatcher;
import com.zurimate.appbackup.service.backup.BackupJob;
import com.zurimate.appbackup.service.backup.DatabaseDumper;
import com.zurimate.appbackup.service.backup.TestProps;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.SchedulerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupSchedulerAdapterTest {
    private BackupRepository backups;
    private BackupRunRepository runs;
    private BackupDispatcher dispatcher;
    private BackupSchedulerAdapter adapter;

    @BeforeEach
    void setUp() {
        backups = mock(BackupRepository.class);
        runs = mock(BackupRunRepository.class);
        dispatcher = mock(BackupDispatcher.class);
        DatabaseDumper dumper = mock(DatabaseDumper.class);
        when(dumper.supports(any())).thenReturn(true);
        when(backups.save(any())).thenAnswer(inv -> inv.getArgument(0));
        adapter = new BackupSchedulerAdapter(backups, runs, dispatcher, TestProps.with(Path.of("w"), 0, 0, 1, 0), dumper);
    }

    private static BackupRequest request(DBConfig db, String destination, CloudType cloud, String cloudDir, BackBlazeConfig b2) {
        return new BackupRequest(SchedulerType.DAILY, db, destination, List.of(), cloud, cloudDir, b2);
    }

    private static DBConfig pg(String user, String password) {
        return new DBConfig(DBType.POSTGRESQL, "h", 5432, user, password, "shop", 2);
    }

    @Test
    void registersAndQueuesTheFirstRun() {
        var summary = adapter.createScheduledBackup(request(new DBConfig(DBType.MONGODB, "m", 27017, null, null, "app", 2, "authSource=admin"),
                "/backups/app", null, null, null));

        ArgumentCaptor<BackupEntity> saved = ArgumentCaptor.forClass(BackupEntity.class);
        verify(backups).save(saved.capture());
        assertThat(saved.getValue().getConnectionOptions()).isEqualTo("authSource=admin");
        ArgumentCaptor<BackupJob> job = ArgumentCaptor.forClass(BackupJob.class);
        verify(dispatcher).submit(job.capture());
        assertThat(job.getValue().trigger()).isEqualTo("REGISTRATION");
        assertThat(job.getValue().dbConfig().options()).isEqualTo("authSource=admin");
        assertThat(summary.dbType()).isEqualTo(DBType.MONGODB);
    }

    @Test
    void sqlEnginesNeedCredentials() {
        assertThatThrownBy(() -> adapter.createScheduledBackup(request(pg("u", " "), "/b", null, null, null)))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("username and password");
        assertThatThrownBy(() -> adapter.createScheduledBackup(request(pg(null, "p"), "/b", null, null, null)))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void rejectsRelativeDestinationsAndBadRedisDatabases() {
        assertThatThrownBy(() -> adapter.createScheduledBackup(request(pg("u", "p"), "backups", null, null, null)))
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> adapter.createScheduledBackup(request(
                new DBConfig(DBType.REDIS, "r", 6379, null, null, "cache", 1), "/b", null, null, null)))
                .hasMessageContaining("database index");
    }

    @Test
    void cloudSettingsMustBeComplete() {
        assertThatThrownBy(() -> adapter.createScheduledBackup(request(pg("u", "p"), "/b", CloudType.BACK_BLAZE, "dir", null)))
                .hasMessageContaining("blazeCloudConfig");
        BackBlazeConfig b2 = new BackBlazeConfig("id", "n", "k", "bucket");
        assertThatThrownBy(() -> adapter.createScheduledBackup(request(pg("u", "p"), "/b", CloudType.BACK_BLAZE, " ", b2)))
                .hasMessageContaining("cloudDestinationDir");
    }

    @Test
    void unsupportedEnginesAreRejected() {
        DatabaseDumper none = mock(DatabaseDumper.class);
        BackupSchedulerAdapter strict = new BackupSchedulerAdapter(backups, runs, dispatcher, TestProps.with(Path.of("w"), 0, 0, 1, 0), none);
        assertThatThrownBy(() -> strict.createScheduledBackup(request(pg("u", "p"), "/b", null, null, null)))
                .hasMessageContaining("is not built into this AppBackup edition");
    }

    @Test
    void oneBrokenJobDoesNotStopTheSchedule() {
        BackupEntity a = entity();
        BackupEntity b = entity();
        when(backups.findAllBySchedulerType(SchedulerType.DAILY)).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("cannot decrypt")).doNothing().when(dispatcher).submit(any());

        adapter.processScheduledBackup(SchedulerType.DAILY);

        verify(dispatcher, times(2)).submit(any());
    }

    @Test
    void unknownBackupIsNotFoundAndRunLimitIsBounded() {
        UUID id = UUID.randomUUID();
        when(backups.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.triggerBackup(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> adapter.deleteBackup(id)).isInstanceOf(NotFoundException.class);

        BackupEntity known = entity();
        when(backups.findById(known.getId())).thenReturn(Optional.of(known));
        adapter.listRuns(known.getId(), 10_000);
        verify(runs).findByBackupIdOrderByStartedAtDesc(known.getId(), org.springframework.data.domain.Limit.of(200));
    }

    @Test
    void deletingRemovesHistoryButNeverArchives() {
        BackupEntity known = entity();
        when(backups.findById(known.getId())).thenReturn(Optional.of(known));
        adapter.deleteBackup(known.getId());
        verify(runs).deleteByBackupIdBulk(known.getId());
        verify(backups).delete(known);
    }

    private static BackupEntity entity() {
        return BackupEntity.builder().id(UUID.randomUUID()).schedulerType(SchedulerType.DAILY).dbType(DBType.MYSQL)
                .host("h").port(3306).username("u").password("p").dbName("db").tableBatchesOf(1)
                .destination("/b").createdAt(Instant.now()).build();
    }
}
