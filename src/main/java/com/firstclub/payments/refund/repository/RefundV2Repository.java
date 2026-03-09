package com.firstclub.payments.refund.repository;

import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundV2Repository extends JpaRepository<RefundV2, Long> {

    List<RefundV2> findByPaymentId(Long paymentId);

    List<RefundV2> findByMerchantId(Long merchantId);

    Optional<RefundV2> findByMerchantIdAndId(Long merchantId, Long id);

    /** All refunds for a payment that belong to a specific merchant (tenant-safe lookup). */
    List<RefundV2> findByPaymentIdAndMerchantId(Long paymentId, Long merchantId);

    /** Sum of COMPLETED refunds for a payment — used as an independent check. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundV2 r " +
           "WHERE r.paymentId = :paymentId AND r.status = :status")
    BigDecimal sumAmountByPaymentIdAndStatus(
            @Param("paymentId") Long paymentId,
            @Param("status") RefundV2Status status);

    // ── Phase 13: admin search ────────────────────────────────────────────
    /** Tenant-safe lookup by gateway-assigned refund reference — prevents cross-merchant leakage. */
    Optional<RefundV2> findByRefundReferenceAndMerchantId(String refundReference, Long merchantId);

    // ── Phase 15: idempotency fingerprint ────────────────────────────────
    /** True if a refund with this fingerprint already exists (fast pre-check before DB lock). */
    boolean existsByRequestFingerprint(String requestFingerprint);

    /** Load the existing refund by fingerprint so it can be returned idempotently. */
    Optional<RefundV2> findByRequestFingerprint(String requestFingerprint);
}
