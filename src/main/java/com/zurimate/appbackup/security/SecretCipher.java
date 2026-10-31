package com.zurimate.appbackup.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for credentials stored in the application database.
 * <p>
 * Values are stored as {@code enc:v1:<base64(iv | ciphertext | tag)>}. Values without the
 * prefix are treated as legacy plaintext, which lets existing rows keep working until they
 * are re-encrypted on startup.
 */
public final class SecretCipher {
    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretCipher current = new SecretCipher(null);

    private final SecretKey key;

    private SecretCipher(SecretKey key) {
        this.key = key;
    }

    /** Builds a cipher from a base64 encoded 32 byte key; blank input disables encryption. */
    public static SecretCipher fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) return new SecretCipher(null);
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BACKUP_APP_ENCRYPTION_KEY is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("BACKUP_APP_ENCRYPTION_KEY must decode to 32 bytes, got " + raw.length);
        }
        return new SecretCipher(new SecretKeySpec(raw, "AES"));
    }

    public static void install(SecretCipher cipher) {
        current = cipher;
    }

    public static SecretCipher current() {
        return current;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (plain == null || !enabled() || isEncrypted(plain)) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        if (!enabled()) {
            throw new IllegalStateException("Stored credentials are encrypted but BACKUP_APP_ENCRYPTION_KEY is not set");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt stored credentials; is BACKUP_APP_ENCRYPTION_KEY correct?", e);
        }
    }
}
