package com.firstclub.payments.repository;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    List<PaymentAttempt> findByPaymentIntentIdOrderByAttemptNumberAsc(Long paymentIntentId);

    Optional<PaymentAttempt> findTopByPaymentIntentIdOrderByAttemptNumberDesc(Long paymentIntentId);

    Optional<PaymentAttempt> findByIdAndPaymentIntentId(Long id, Long paymentIntentId);

    int countByPaymentIntentId(Long paymentIntentId);

    /**
     * Returns the current maximum attempt number for the given intent, or 0 if no attempts exist.
     *
     * <p><b>Guard:</b> BusinessLockScope.PAYMENT_ATTEMPT_NUMBERING
     * <p>Using {@code MAX} rather than {@code COUNT} is more correct when attempts exist with
     * non-contiguous numbers (e.g. a failed attempt was retried with the same slot reused).
     * The next attempt number is {@code MAX + 1}.
     *
     * <p><b>Residual race:</b> Two concurrent confirm calls can both read the same MAX value and
     * both try to insert an attempt with the same attempt_number.  The unique constraint on
     * {@code (payment_intent_id, attempt_number)} catches the second insert and produces a
     * {@code DataIntegrityViolationException} that is translated to a 409 by
     * {@code GlobalExceptionHandler}.  The primary guard against concurrent confirms is the
     * {@code @Version} lock on {@code PaymentIntentV2} — it will cause the second confirm to
     * fail with an optimistic lock exception before attempt numbering is ever reached.
     */
    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM PaymentAttempt a WHERE a.paymentIntent.id = :intentId")
    int findMaxAttemptNumberByPaymentIntentId(@Param("intentId") Long intentId);

    // ── Phase 8: UNKNOWN / reconciliation queries ─────────────────────────────────

    /**
     * Returns all UNKNOWN attempts for the given intent (for manual reconcile trigger).
     */
    List<PaymentAttempt> findByPaymentIntentIdAndStatus(
            Long paymentIntentId, PaymentAttemptStatus status);

    /**
     * Returns all UNKNOWN attempts whose {@code started_at} is before the given threshold.
     * Used by the recovery scheduler to find timed-out attempts eligible for polling.
     */
    @Query("SELECT a FROM PaymentAttempt a WHERE a.status = :status AND a.startedAt < :threshold")
    List<PaymentAttempt> findByStatusAndStartedAtBefore(
            @Param("status") PaymentAttemptStatus status,
            @Param("threshold") LocalDateTime threshold);

    /**
     * Returns the count of attempts with a given status for the given intent.
     * Used to enforce the exactly-one-successful-attempt invariant.
     */
    int countByPaymentIntentIdAndStatus(Long paymentIntentId, PaymentAttemptStatus status);

    // ── Phase 14: Orphan gateway payment detection ────────────────────────────

    /**
     * Returns all SUCCEEDED attempts where:
     * <ul>
     *   <li>A {@code gateway_transaction_id} is set (gateway confirmed the charge).</li>
     *   <li>The linked {@code PaymentIntentV2.invoiceId} is {@code null} (no billing anchor).</li>
     * </ul>
     * These are "orphaned" gateway payments: money received by the gateway but
     * not tied to any invoice in the FirstClub billing system.
     */
    @Query("SELECT a FROM PaymentAttempt a " +
           "WHERE a.status = com.firstclub.payments.entity.PaymentAttemptStatus.SUCCEEDED " +
           "AND a.gatewayTransactionId IS NOT NULL " +
           "AND a.paymentIntent.invoiceId IS NULL")
    List<PaymentAttempt> findSucceededWithGatewayTxnAndNoInvoice();
}
