package com.zurimate.appbackup.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedStringConverterTest {
    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @AfterEach
    void reset() {
        SecretCipher.install(SecretCipher.fromBase64Key(""));
    }

    @Test
    void encryptsOnWriteAndDecryptsOnRead() {
        SecretCipher.install(SecretCipher.fromBase64Key(SecretCipherTest.randomKey()));
        String stored = converter.convertToDatabaseColumn("db-password");
        assertThat(stored).startsWith(SecretCipher.PREFIX);
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("db-password");
        assertThat(converter.convertToEntityAttribute("legacy-plain")).isEqualTo("legacy-plain");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void passesThroughWhenEncryptionIsOff() {
        assertThat(converter.convertToDatabaseColumn("plain")).isEqualTo("plain");
    }
}
