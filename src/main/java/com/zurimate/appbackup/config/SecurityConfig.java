package com.zurimate.appbackup.config;

import com.zurimate.appbackup.security.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs the credential cipher before any entity is read, and enables the typed configuration properties.
 */
@Configuration
@EnableConfigurationProperties({SecurityProperties.class, BackupProperties.class})
class SecurityConfig {

    @Bean
    SecretCipher secretCipher(SecurityProperties properties) {
        SecretCipher cipher = SecretCipher.fromBase64Key(properties.encryptionKey());
        SecretCipher.install(cipher);
        return cipher;
    }
}
