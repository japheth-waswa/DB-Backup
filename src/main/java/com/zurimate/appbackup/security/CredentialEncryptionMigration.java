package com.zurimate.appbackup.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Encrypts credentials that were stored before encryption was enabled.
 * Runs once per startup and is a no-op when everything is already encrypted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CredentialEncryptionMigration {
    private final JdbcTemplate jdbcTemplate;

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void encryptLegacyCredentials() {
        SecretCipher cipher = SecretCipher.current();
        if (!cipher.enabled()) {
            log.warn("BACKUP_APP_ENCRYPTION_KEY is not set; database credentials are stored unencrypted");
            return;
        }
        record Row(UUID id, String password, String cloudConfig) {
        }
        List<Row> legacy = jdbcTemplate.query("SELECT id, password, back_blaze_config_json FROM backups",
                        (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("password"), rs.getString("back_blaze_config_json")))
                .stream()
                .filter(r -> (r.password() != null && !SecretCipher.isEncrypted(r.password()))
                        || (r.cloudConfig() != null && !SecretCipher.isEncrypted(r.cloudConfig())))
                .toList();
        for (Row row : legacy) {
            jdbcTemplate.update("UPDATE backups SET password = ?, back_blaze_config_json = ? WHERE id = ?",
                    cipher.encrypt(row.password()), cipher.encrypt(row.cloudConfig()), row.id());
        }
        if (!legacy.isEmpty()) log.info("Encrypted stored credentials for {} backup job(s)", legacy.size());
    }
}
