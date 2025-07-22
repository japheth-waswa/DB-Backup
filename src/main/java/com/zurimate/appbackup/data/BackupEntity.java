package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
@Entity
@Table(name = "backups",
        indexes = {
                @Index(name = "idx_id_scheduler_type_created_at", columnList = "id,scheduler_type,created_at")
        }
)
public class BackupEntity {

    @Id
    private UUID id;

    @Convert(converter = SchedulerTypeConverter.class)
    @Column(columnDefinition = "VARCHAR")
    private SchedulerType schedulerType;

    @Convert(converter = DbTypeConverter.class)
    @Column(columnDefinition = "VARCHAR")
    private DBType dbType;

    private String host;

    private int port;

    private String username;

    private String password;

    private String dbName;

    private int tableBatchesOf;

    private String destination;

    List<String> otherDirsToBackup;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private Instant updatedAt;
}
