package com.firstclub.platform.dedup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BusinessFingerprintService}.
 *
 * Validates: determinism, cross-effect isolation, non-null output, and that
 * distinct inputs produce distinct fingerprints.
 */
@DisplayName("BusinessFingerprintService Unit Tests")
class BusinessFingerprintServiceTest {

    private final BusinessFingerprintService service = new BusinessFingerprintService();

    // ── Determinism ────────────────────────────────────────────────────────

    @Test
    @DisplayName("same inputs produce the same payment-capture fingerprint")
    void paymentCapture_isDeterministic() {
        String fp1 = service.paymentCaptureFingerprint(42L, 100L, "txn_abc123");
        String fp2 = service.paymentCaptureFingerprint(42L, 100L, "txn_abc123");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("same inputs produce the same refund-completed fingerprint")
    void refundCompleted_isDeterministic() {
        String fp1 = service.refundCompletedFingerprint(7L, 55L);
        String fp2 = service.refundCompletedFingerprint(7L, 55L);
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("same inputs produce the same dispute-opened fingerprint")
    void disputeOpened_isDeterministic() {
        String fp1 = service.disputeOpenedFingerprint(3L, 200L, "DISP-9876");
        String fp2 = service.disputeOpenedFingerprint(3L, 200L, "DISP-9876");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("same inputs produce the same settlement-batch fingerprint")
    void settlementBatch_isDeterministic() {
        String fp1 = service.settlementBatchFingerprint(10L, "2024-01-15", "INR");
        String fp2 = service.settlementBatchFingerprint(10L, "2024-01-15", "INR");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("currency comparison is case-insensitive in settlement fingerprint")
    void settlementBatch_currencyCaseInsensitive() {
        String fpUpper = service.settlementBatchFingerprint(10L, "2024-01-15", "INR");
        String fpLower = service.settlementBatchFingerprint(10L, "2024-01-15", "inr");
        assertThat(fpUpper).isEqualTo(fpLower);
    }

    @Test
    @DisplayName("same inputs produce the same revenue-recognition fingerprint")
    void revenueRecognition_isDeterministic() {
        String fp1 = service.revenueRecognitionFingerprint(999L);
        String fp2 = service.revenueRecognitionFingerprint(999L);
        assertThat(fp1).isEqualTo(fp2);
    }

    // ── Non-null and format ────────────────────────────────────────────────

    @Test
    @DisplayName("fingerprints are 64-char SHA-256 hex strings")
    void fingerprints_areSha256HexLength() {
        assertThat(service.paymentCaptureFingerprint(1L, 2L, "txn")).hasSize(64);
        assertThat(service.refundCompletedFingerprint(1L, 2L)).hasSize(64);
        assertThat(service.disputeOpenedFingerprint(1L, 2L, "D")).hasSize(64);
        assertThat(service.settlementBatchFingerprint(1L, "2024-01-01", "USD")).hasSize(64);
        assertThat(service.revenueRecognitionFingerprint(1L)).hasSize(64);
    }

    // ── Cross-effect isolation ─────────────────────────────────────────────

    @Test
    @DisplayName("different effect types with similar data produce different fingerprints")
    void crossEffect_noCollision() {
        // merchantId=1, refundId=2 vs merchantId=1, paymentIntentId=1, gatewayTxnId="2"
        String captureFingerprint = service.paymentCaptureFingerprint(1L, 1L, "2");
        String refundFingerprint  = service.refundCompletedFingerprint(1L, 2L);
        assertThat(captureFingerprint).isNotEqualTo(refundFingerprint);
    }

    @Test
    @DisplayName("different merchant IDs produce different fingerprints for same payment")
    void differentMerchants_differentFingerprints() {
        String fp1 = service.paymentCaptureFingerprint(1L,  100L, "txn");
        String fp2 = service.paymentCaptureFingerprint(99L, 100L, "txn");
        assertThat(fp1).isNotEqualTo(fp2);
    }
}
