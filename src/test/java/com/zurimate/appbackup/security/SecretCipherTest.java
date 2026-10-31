package com.zurimate.appbackup.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripsAndUsesFreshIvEachTime() {
        SecretCipher cipher = SecretCipher.fromBase64Key(randomKey());
        String first = cipher.encrypt("s3cr3t|=*'");
        String second = cipher.encrypt("s3cr3t|=*'");

        assertThat(first).startsWith(SecretCipher.PREFIX).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("s3cr3t|=*'");
        assertThat(cipher.encrypt(first)).as("already encrypted values are left alone").isEqualTo(first);
    }

    @Test
    void readsLegacyPlaintextAndPassesThroughWhenDisabled() {
        SecretCipher enabled = SecretCipher.fromBase64Key(randomKey());
        SecretCipher disabled = SecretCipher.fromBase64Key("");

        assertThat(enabled.decrypt("legacy-password")).isEqualTo("legacy-password");
        assertThat(disabled.encrypt("plain")).isEqualTo("plain");
        assertThatThrownBy(() -> disabled.decrypt(enabled.encrypt("x")))
                .hasMessageContaining("BACKUP_APP_ENCRYPTION_KEY");
    }

    @Test
    void rejectsWrongKeyAndBadKeys() {
        String encrypted = SecretCipher.fromBase64Key(randomKey()).encrypt("x");
        assertThatThrownBy(() -> SecretCipher.fromBase64Key(randomKey()).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretCipher.fromBase64Key("dG9vLXNob3J0"))
                .hasMessageContaining("32 bytes");
    }
}
