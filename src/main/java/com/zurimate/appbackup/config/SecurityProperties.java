package com.zurimate.appbackup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API protection and credential encryption settings, read from {@code BACKUP_APP_API_KEY}
 * and {@code BACKUP_APP_ENCRYPTION_KEY}.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        /* Shared secret expected in the X-API-Key header. Empty disables the API. */
        @DefaultValue("") String apiKey,
        /* Base64 encoded 256-bit key used to encrypt stored credentials. Empty stores them as-is. */
        @DefaultValue("") String encryptionKey
) {
}
