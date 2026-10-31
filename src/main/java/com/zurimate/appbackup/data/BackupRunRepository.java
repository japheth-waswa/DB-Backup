package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.BackupRunStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of the run history; old rows are purged by the daily housekeeping job.
 */
public interface BackupRunRepository extends JpaRepository<BackupRunEntity, UUID> {

    List<BackupRunEntity> findByBackupIdOrderByStartedAtDesc(UUID backupId, Limit limit);

    Optional<BackupRunEntity> findFirstByBackupIdOrderByStartedAtDesc(UUID backupId);

    List<BackupRunEntity> findByStatus(BackupRunStatus status);

    @Transactional
    @Modifying
    @Query("delete from BackupRunEntity r where r.startedAt < :cutoff")
    int deleteStartedBefore(Instant cutoff);

    @Transactional
    @Modifying
    @Query("delete from BackupRunEntity r where r.backupId = :backupId")
    int deleteByBackupIdBulk(UUID backupId);
}
