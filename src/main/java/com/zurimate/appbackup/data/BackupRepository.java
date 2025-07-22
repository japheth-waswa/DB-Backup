package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.SchedulerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BackupRepository extends JpaRepository<BackupEntity, UUID> {

    List<BackupEntity> findAllBySchedulerType(SchedulerType schedulerType);

    List<BackupEntity> findAllBySchedulerTypeIn(List<SchedulerType> schedulerType);
}
