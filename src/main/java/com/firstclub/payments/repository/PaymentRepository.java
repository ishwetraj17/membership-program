package com.firstclub.payments.repository;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByPaymentIntentId(Long paymentIntentId);

    boolean existsByGatewayTxnId(String gatewayTxnId);

    /** Captured payments settled within [start, end) — used by reconciliation. */
    List<Payment> findByStatusAndCapturedAtBetween(PaymentStatus status,
                                                   LocalDateTime start,
                                                   LocalDateTime end);

    /** Load a payment row for mutation with a pessimistic write lock — prevents concurrent over-refund. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    List<Payment> findByMerchantId(Long merchantId);

    /** Captured payments for a specific merchant within [start, end) — used by settlement batch service. */
    List<Payment> findByMerchantIdAndStatusAndCapturedAtBetween(Long merchantId,
                                                                 PaymentStatus status,
                                                                 LocalDateTime start,
                                                                 LocalDateTime end);

    // ── Phase 11: integrity-check queries ────────────────────────────────────
    /** All payments with the given status — used by integrity checkers. */
    List<Payment> findByStatus(PaymentStatus status);

    /**
     * Payments where the capacity invariant is violated at the application level:
     * refunded_amount + disputed_amount > captured_amount.
     * (The DB-level CHECK constraint prevents this post Phase 9, but this query is
     * a defence-in-depth cross-check.)
     */
    @Query("SELECT p FROM Payment p WHERE p.refundedAmount + p.disputedAmount > p.capturedAmount")
    List<Payment> findCapacityViolations();

    // ── Phase 13: admin search ────────────────────────────────────────────
    /** Tenant-safe lookup by gateway transaction ID — prevents cross-merchant leakage. */
    Optional<Payment> findByGatewayTxnIdAndMerchantId(String gatewayTxnId, Long merchantId);
}
