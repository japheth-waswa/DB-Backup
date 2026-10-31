package com.zurimate.appbackup.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that keeps a column encrypted at rest using the installed {@link SecretCipher}.
 * Legacy plaintext values are read transparently and re-encrypted on startup.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return SecretCipher.current().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return SecretCipher.current().decrypt(dbData);
    }
}
