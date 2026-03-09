package com.firstclub.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA {@link AttributeConverter} that transparently encrypts and decrypts
 * {@link String} entity fields using AES-256-GCM.
 *
 * <h3>Key provisioning</h3>
 * The 256-bit key must be supplied as a Base64-encoded string via the
 * {@code PII_ENC_KEY} environment variable.  In development the application
 * falls back to a fixed predictable key so that unit tests can run without
 * external configuration, but this fallback is <em>never</em> used in
 * production (guarded by an assertion / warning).
 *
 * <h3>Storage format</h3>
 * The encrypted value stored in the database column is:
 * <pre>{@code base64(iv):base64(ciphertext+authTag)}</pre>
 * where {@code iv} is a 12-byte randomly generated value and
 * {@code ciphertext+authTag} is the 128-bit GCM authentication tag appended
 * by the JCE provider.
 *
 * <h3>Security properties</h3>
 * <ul>
 *   <li>AES-256 with GCM provides authenticated encryption — any tampering
 *       with the stored value is detected at decryption time.
 *   <li>A fresh random IV per encryption call ensures that identical
 *       plaintext values produce different ciphertexts.
 *   <li>{@link SecureRandom} is used for IV generation.
 * </ul>
 */
@Converter
@Slf4j
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /** AES-GCM transformation string. */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM authentication tag length in bits. */
    private static final int GCM_TAG_BITS = 128;

    /** GCM IV length in bytes (96 bits is the recommended nonce size). */
    private static final int GCM_IV_BYTES = 12;

    /** Separator between IV and ciphertext in the stored representation. */
    private static final String SEPARATOR = ":";

    /**
     * Dev-only fallback key (32 zero-bytes = 256 bits).
     * Logged as a warning at startup; MUST NOT reach production.
     */
    private static final String DEV_FALLBACK_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    private final SecretKey secretKey;

    public EncryptedStringConverter() {
        this(System.getenv("PII_ENC_KEY"));
    }

    /**
     * Package-private constructor that accepts the raw Base64 key string.
     * Exposed for unit testing without requiring env-var manipulation.
     *
     * @param rawKeyBase64 Base64-encoded 32-byte AES key, or null to use
     *                     the dev-fallback (all-zero) key.
     */
    EncryptedStringConverter(String rawKeyBase64) {
        String raw = rawKeyBase64;
        if (raw == null || raw.isBlank()) {
            log.warn("PII_ENC_KEY env var not set — using insecure dev fallback key. " +
                     "DO NOT deploy this configuration to production.");
            raw = DEV_FALLBACK_KEY_B64;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PII_ENC_KEY is not valid Base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "PII_ENC_KEY must decode to exactly 32 bytes (256 bits), got " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    // ------------------------------------------------------------------
    // AttributeConverter contract
    // ------------------------------------------------------------------

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                    + SEPARATOR
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            int sepIdx = stored.indexOf(SEPARATOR);
            if (sepIdx < 0) {
                throw new IllegalArgumentException("Stored value is missing IV separator");
            }
            byte[] iv         = Base64.getDecoder().decode(stored.substring(0, sepIdx));
            byte[] ciphertext = Base64.getDecoder().decode(stored.substring(sepIdx + 1));

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        return iv;
    }
}
