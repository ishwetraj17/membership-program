package com.firstclub.payments.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Signs and verifies webhook payloads using HMAC-SHA256.
 *
 * <p>The gateway places {@code hex(hmac(payload, secret))} in the
 * {@code X-Signature} HTTP header.  Receivers call {@link #verify} before
 * processing any inbound event.
 */
@Service
public class WebhookSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    @Value("${payments.webhook.secret}")
    private String secret;

    /**
     * Computes HMAC-SHA256 of {@code payload} using the configured secret and
     * returns the lower-case hex-encoded digest.
     */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * Returns {@code true} if {@code signature} matches {@link #sign(String) sign(payload)}.
     * Comparison is done after computing the expected digest to prevent timing attacks.
     */
    public boolean verify(String payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        return sign(payload).equalsIgnoreCase(signature);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
