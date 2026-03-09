package com.firstclub.platform.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EncryptedStringConverter}.
 *
 * Tests run without any application context or DB, using the dev-fallback key
 * (all-zero bytes) that is activated when {@code PII_ENC_KEY} env var is absent.
 */
class EncryptedStringConverterTest {

    // Instantiate once; dev fallback key is used (PII_ENC_KEY not set in unit test env)
    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    // -------------------------------------------------------------------------
    // Null passthrough
    // -------------------------------------------------------------------------

    @Test
    void convertToDatabaseColumn_nullInput_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_nullInput_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    // -------------------------------------------------------------------------
    // Round-trip
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "9876543210",                      // phone number
            "42, MG Road, Bengaluru, 560001",  // address
            "",                                // empty string
            "Special chars: 🇮🇳 ₹ & <script>", // unicode + special
    })
    void encryptDecrypt_roundTrip(String plaintext) {
        String encrypted = converter.convertToDatabaseColumn(plaintext);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    // -------------------------------------------------------------------------
    // Values are NOT stored as plaintext
    // -------------------------------------------------------------------------

    @Test
    void encrypt_outputDoesNotContainPlaintext() {
        String phone     = "9123456789";
        String encrypted = converter.convertToDatabaseColumn(phone);

        // The raw DB value must not contain the original digits
        assertThat(encrypted).doesNotContain(phone);
    }

    @Test
    void encrypt_outputContainsSeparator() {
        // Format is base64(iv):base64(ciphertext)
        String encrypted = converter.convertToDatabaseColumn("test");
        assertThat(encrypted).contains(":");
    }

    @Test
    void encrypt_ivPartIsValidBase64() {
        String encrypted = converter.convertToDatabaseColumn("data");
        String ivPart = encrypted.split(":", 2)[0];
        // Must parse as Base64 without throwing
        assertThatCode(() -> Base64.getDecoder().decode(ivPart)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Each encryption call produces a distinct ciphertext (IV randomness)
    // -------------------------------------------------------------------------

    @Test
    void encrypt_sameInputProducesDifferentCiphertexts() {
        String plaintext = "9876543210";
        String first  = converter.convertToDatabaseColumn(plaintext);
        String second = converter.convertToDatabaseColumn(plaintext);

        // Must differ due to random IV
        assertThat(first).isNotEqualTo(second);

        // But both must decrypt to the original
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(plaintext);
    }

    // -------------------------------------------------------------------------
    // Tamper detection (AES-GCM auth tag)
    // -------------------------------------------------------------------------

    @Test
    void decrypt_tamperedCiphertext_throwsException() {
        String encrypted  = converter.convertToDatabaseColumn("sensitive");
        // Flip the last character of the ciphertext part to break the auth tag
        String tampered   = encrypted.substring(0, encrypted.length() - 1) + "X";
        if (tampered.equals(encrypted)) {
            tampered = encrypted.substring(0, encrypted.length() - 1) + "Y";
        }

        final String tamperedFinal = tampered;
        assertThatThrownBy(() -> converter.convertToEntityAttribute(tamperedFinal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII decryption failed");
    }

    @Test
    void decrypt_missingSeparator_throwsException() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("notvalidformat"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Key validation
    // -------------------------------------------------------------------------

    @Test
    void constructor_invalidBase64Key_throwsIllegalState() {
        String badKey = "not-valid-base64!!!";
        assertThatThrownBy(() -> {
            // Force the constructor to use a bad key by setting env via reflection — instead
            // we call the package-private test constructor
            new EncryptedStringConverter(badKey);
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not valid Base64");
    }

    @Test
    void constructor_wrongKeyLength_throwsIllegalState() {
        // 16 bytes = 128-bit key — we require 256-bit
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new EncryptedStringConverter(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
