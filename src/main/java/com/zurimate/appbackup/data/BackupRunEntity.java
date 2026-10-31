package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.BackupRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** One execution of a backup job, kept as an audit trail. */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backup_runs",
        indexes = {
                @Index(name = "idx_backup_runs_backup_id_started_at", columnList = "backup_id,started_at"),
                @Index(name = "idx_backup_runs_status", columnList = "status")
        })
public class BackupRunEntity {
    public static final int MAX_MESSAGE_LENGTH = 4000;

    @Id
    private UUID id;

    @Column(name = "backup_id")
    private UUID backupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackupRunStatus status;

    /** What started the run: SCHEDULE, STARTUP, MANUAL or REGISTRATION. */
    @Column(length = 32)
    private String triggeredBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private Long durationMs;

    private Integer tablesTotal;

    private Integer tablesFailed;

    private Long rowsDumped;

    @Column(length = 1024)
    private String dbArchive;

    private Long dbArchiveBytes;

    @Column(length = 64)
    private String dbArchiveSha256;

    @Column(length = 1024)
    private String otherArchive;

    @Column(length = 512)
    private String cloudStatus;

    @Column(length = MAX_MESSAGE_LENGTH)
    private String message;

    public void setMessage(String message) {
        this.message = message == null || message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
