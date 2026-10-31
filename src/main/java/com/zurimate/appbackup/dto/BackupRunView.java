package com.zurimate.appbackup.dto;

import com.zurimate.appbackup.utils.BackupRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of one backup run, including where the archive was written, its SHA-256 and the cloud outcome.
 */
public record BackupRunView(UUID id,
                            UUID backupId,
                            BackupRunStatus status,
                            String trigger,
                            Instant startedAt,
                            Instant finishedAt,
                            Long durationMs,
                            Integer tablesTotal,
                            Integer tablesFailed,
                            Long rowsDumped,
                            String dbArchive,
                            Long dbArchiveBytes,
                            String dbArchiveSha256,
                            String otherArchive,
                            String cloudStatus,
                            String message) {
}
