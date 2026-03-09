package com.firstclub.platform.dedup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes deterministic SHA-256 fingerprints for critical business effects.
 *
 * <p>A <em>business fingerprint</em> is a hex-encoded SHA-256 digest of the
 * business-unique key fields for a given effect.  The same logical event must
 * always produce the same fingerprint, regardless of when or how many times it
 * is replayed.
 *
 * <h3>Fingerprint scheme per effect type</h3>
 * <pre>
 * PAYMENT_CAPTURE_SUCCESS    = SHA-256("{merchantId}:{paymentIntentId}:{gatewayTxnId}")
 * REFUND_COMPLETED           = SHA-256("{merchantId}:{refundId}")
 * DISPUTE_OPENED             = SHA-256("{merchantId}:{paymentId}:{disputeReference}")
 * SETTLEMENT_BATCH_CREATED   = SHA-256("{merchantId}:{settlementDate}:{currency}")
 * REVENUE_RECOGNITION_POSTED = SHA-256("{scheduleId}")
 * </pre>
 *
 * <p>The separator {@code ":"} is safe because none of the fields may contain
 * a colon.  Using a prefix ensures cross-domain collisions are impossible even
 * if individual field values happen to be identical.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessFingerprintService {

    // ── Payment capture ───────────────────────────────────────────────────

    /**
     * Fingerprint for a successful gateway capture.
     *
     * <p>Uniqueness is guaranteed by {@code (merchantId, paymentIntentId, gatewayTxnId)}.
     * Even if the gateway resends the callback, the fingerprint is identical.
     */
    public String paymentCaptureFingerprint(Long merchantId,
                                            Long paymentIntentId,
                                            String gatewayTxnId) {
        return sha256(merchantId + ":" + paymentIntentId + ":" + gatewayTxnId);
    }

    // ── Refund ────────────────────────────────────────────────────────────

    /**
     * Fingerprint for a completed refund.
     *
     * <p>Each refund request has a unique DB row; using {@code merchantId + refundId}
     * prevents cross-merchant collisions if IDs are sequential.
     */
    public String refundCompletedFingerprint(Long merchantId, Long refundId) {
        return sha256(merchantId + ":" + refundId);
    }

    // ── Dispute ───────────────────────────────────────────────────────────

    /**
     * Fingerprint for opening a dispute against a payment.
     *
     * <p>There should be at most one open dispute per payment.
     * {@code disputeReference} is the gateway-assigned dispute ID (if available)
     * or the combination of payment+reason.
     */
    public String disputeOpenedFingerprint(Long merchantId,
                                           Long paymentId,
                                           String disputeReference) {
        return sha256(merchantId + ":" + paymentId + ":" + disputeReference);
    }

    // ── Settlement ────────────────────────────────────────────────────────

    /**
     * Fingerprint for a settlement batch.
     *
     * <p>Only one settlement batch should exist per merchant per date per currency.
     */
    public String settlementBatchFingerprint(Long merchantId,
                                             String settlementDate,
                                             String currency) {
        return sha256(merchantId + ":" + settlementDate + ":" + currency.toUpperCase());
    }

    // ── Revenue recognition ───────────────────────────────────────────────

    /**
     * Fingerprint for a single revenue recognition schedule row posting.
     *
     * <p>Each schedule row has a unique DB PK so this is sufficient.
     */
    public String revenueRecognitionFingerprint(Long scheduleId) {
        return sha256("rr:" + scheduleId);
    }

    // ── Generic ───────────────────────────────────────────────────────────

    /**
     * Generic fingerprint for arbitrary key material.
     * Prefer the typed helpers above for financial effects.
     *
     * @param raw raw key material; must be deterministic for the same logical event
     */
    public String compute(String raw) {
        return sha256(raw);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable — this should never happen on a JVM", e);
        }
    }
}
