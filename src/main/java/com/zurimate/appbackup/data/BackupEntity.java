package com.zurimate.appbackup.data;

import com.zurimate.appbackup.dto.cloud.BackBlazeConfig;
import com.zurimate.appbackup.security.EncryptedStringConverter;
import com.zurimate.appbackup.utils.CloudType;
import com.zurimate.appbackup.utils.DBType;
import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.zurimate.appbackup.utils.Helpers.OBJECT_MAPPER;

/**
 * A registered backup job. The database password and cloud credentials are encrypted at rest
 * (see {@link EncryptedStringConverter}) and excluded from {@code toString()}.
 */
@Slf4j
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

    @ToString.Exclude
    @Convert(converter = EncryptedStringConverter.class)
    private String password;

    private String dbName;

    private int tableBatchesOf;

    /** Extra driver options, e.g. authSource=admin or trustServerCertificate=true. */
    @Column(length = 1024)
    private String connectionOptions;

    private String destination;

    private List<String> otherDirsToBackup;

    @Convert(converter = CloudTypeConverter.class)
    @Column(columnDefinition = "VARCHAR")
    private CloudType cloudType;

    private String cloudDestinationDir;

    @Transient
    @ToString.Exclude
    private BackBlazeConfig backBlazeConfig;

    @ToString.Exclude
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String backBlazeConfigJson;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private Instant updatedAt;

    public void setBackBlazeConfig(BackBlazeConfig backBlazeConfig) {
        this.backBlazeConfig = backBlazeConfig;
        serializeBackBlazeConfig();
    }

    private void serializeBackBlazeConfig() {
        if (backBlazeConfig != null) {
            try {
                this.backBlazeConfigJson = OBJECT_MAPPER.writeValueAsString(backBlazeConfig);
            } catch (Exception e) {
                log.error("Error serializing backBlazeConfig", e);
            }
        }
    }

    @PrePersist
    @PreUpdate
    private void serializeEntityObjects() {
        serializeBackBlazeConfig();
    }

    @PostLoad
    private void deserializeEntityObjects() {
        if (StringUtils.hasText(backBlazeConfigJson)) {
            try {
                this.backBlazeConfig = OBJECT_MAPPER.readValue(backBlazeConfigJson, BackBlazeConfig.class);
            } catch (Exception e) {
                log.error("Error deserializing backBlazeConfigJson", e);
            }
        }
    }
}
