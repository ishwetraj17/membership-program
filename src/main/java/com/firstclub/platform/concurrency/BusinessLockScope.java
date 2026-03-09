package com.firstclub.platform.concurrency;

/**
 * Documents every named lock scope in the system, together with the locking
 * strategy, the invariant being protected, and the failure mode.
 *
 * <p>This enum is a living registry — add a constant any time you introduce a
 * new concurrency guard so the concurrency model stays complete and auditable.
 *
 * <p>Usage example:
 * <pre>{@code
 *   // Self-documenting intent at the call-site:
 *   Payment payment = paymentRepository.findByIdForUpdate(paymentId)
 *       .orElseThrow(...);
 *   // Guard: BusinessLockScope.REFUND_CEILING_CHECK
 * }</pre>
 */
public enum BusinessLockScope {

    // ── Subscription domain ──────────────────────────────────────────────────

    /**
     * Protects subscription state machine transitions (cancel / pause / resume).
     *
     * <p><b>Strategy:</b> JPA {@code @Version} optimistic locking on
     * {@code SubscriptionV2.version}.
     * <br><b>Invariant:</b> Only one concurrent write can commit a state change.
     * <br><b>Failure mode:</b> Second writer gets
     * {@code ObjectOptimisticLockingFailureException} → translated to 409.
     * <br><b>Client action:</b> Re-read state and retry if still applicable.
     */
    SUBSCRIPTION_STATE_TRANSITION,

    /**
     * Guards duplicate active subscription creation for same customer + product.
     *
     * <p><b>Strategy:</b> Application-level check via
     * {@code SubscriptionV2Repository.existsByMerchantIdAndCustomerIdAndProductIdAndStatusIn}
     * + DB unique constraint on {@code (merchant_id, customer_id, product_id)} for
     * non-terminal statuses (enforced via idempotency key or business logic check).
     * <br><b>Invariant:</b> At most one ACTIVE/TRIAL subscription per customer+product.
     * <br><b>Failure mode:</b> Duplicate create throws 409.
     */
    SUBSCRIPTION_DUPLICATE_CREATE,

    // ── Payment domain ───────────────────────────────────────────────────────

    /**
     * Serializes concurrent confirm calls on the same payment intent.
     *
     * <p><b>Strategy:</b> JPA {@code @Version} on {@code PaymentIntentV2.version}
     * + DB unique constraint on {@code (payment_intent_id, attempt_number)}.
     * <br><b>Invariant:</b> A SUCCEEDED or CANCELLED intent cannot transition again.
     * <br><b>Failure mode:</b> Second confirm on SUCCEEDED → idempotent 200;
     * concurrent confirm races → @Version conflict → 409.
     */
    PAYMENT_INTENT_CONFIRM,

    /**
     * Ensures payment attempt numbers are monotonically increasing without gaps.
     *
     * <p><b>Strategy:</b> {@code SELECT MAX(attempt_number)} + DB unique constraint
     * on {@code (payment_intent_id, attempt_number)}.
     * <br><b>Invariant:</b> attempt_number is unique per intent.
     * <br><b>Failure mode:</b> Genuine race produces DB constraint violation → 409.
     * This is acceptable because concurrent confirms on the same intent should not
     * both succeed; the @Version guard on the intent usually catches it first.
     */
    PAYMENT_ATTEMPT_NUMBERING,

    // ── Refund domain ────────────────────────────────────────────────────────

    /**
     * Prevents over-refund by serializing all refund writes against the same payment.
     *
     * <p><b>Strategy:</b> {@code SELECT FOR UPDATE} (PESSIMISTIC_WRITE) on the
     * {@code Payment} row via {@code PaymentRepository.findByIdForUpdate()}.
     * <br><b>Invariant:</b> Sum of all refunds ≤ capturedAmount.
     * <br><b>Failure mode:</b> Concurrent refunds queue on the row lock; each
     * validates its ceiling after acquiring the lock.
     */
    REFUND_CEILING_CHECK,

    // ── Dunning domain ───────────────────────────────────────────────────────

    /**
     * Prevents two scheduler workers from picking up the same due dunning attempt.
     *
     * <p><b>Strategy:</b> Native SQL {@code FOR UPDATE SKIP LOCKED} on the
     * {@code dunning_attempts} table via
     * {@code DunningAttemptRepository.findDueForProcessingWithSkipLocked()}.
     * <br><b>Invariant:</b> Each due attempt is processed by exactly one worker.
     * <br><b>Failure mode:</b> Locked rows are skipped by competing workers.
     */
    DUNNING_ATTEMPT_PROCESSING,

    // ── Revenue recognition ──────────────────────────────────────────────────

    /**
     * Ensures a revenue recognition schedule row is posted exactly once to the ledger.
     *
     * <p><b>Strategy:</b> {@code SELECT FOR UPDATE} (PESSIMISTIC_WRITE) on the
     * {@code RevenueRecognitionSchedule} row + {@code @Version} OCC backstop.
     * <br><b>Invariant:</b> Exactly one ledger entry per schedule row.
     * <br><b>Failure mode:</b> Second concurrent post session blocks on the lock;
     * after acquiring, it sees status=POSTED and exits (idempotent skip).
     */
    REVENUE_RECOGNITION_SINGLE_POST,

    // ── Reconciliation ───────────────────────────────────────────────────────

    /**
     * Prevents two concurrent recon runs from producing interleaved mismatch data.
     *
     * <p><b>Strategy:</b> {@code SELECT FOR UPDATE} (PESSIMISTIC_WRITE) on the
     * {@code ReconReport} row via
     * {@code ReconReportRepository.findByReportDateForUpdate()}.
     * <br><b>Invariant:</b> Mismatch rows for a given date are from exactly one run.
     * <br><b>Failure mode:</b> Second concurrent run blocks on the report lock
     * while the first run completes; then overwrites with its own result (acceptable
     * idempotent re-run scenario) or exits if the row was just written.
     */
    RECON_REPORT_UPSERT,

    // ── Webhook delivery ─────────────────────────────────────────────────────

    /**
     * Prevents two scheduler instances from dispatching the same webhook delivery.
     *
     * <p><b>Strategy:</b> Native SQL {@code FOR UPDATE SKIP LOCKED} on the
     * {@code merchant_webhook_deliveries} table via
     * {@code MerchantWebhookDeliveryRepository.findDueForProcessingWithSkipLocked()}.
     * <br><b>Invariant:</b> Each due delivery is dispatched by exactly one worker.
     * <br><b>Failure mode:</b> Locked rows are skipped; worker that holds the lock
     * either delivers or sets FAILED/GAVE_UP before releasing.
     */
    WEBHOOK_DELIVERY_PROCESSING,

    // ── Outbox ───────────────────────────────────────────────────────────────

    /**
     * Prevents multiple outbox workers from processing the same event.
     *
     * <p><b>Strategy:</b> Already implemented via native SQL {@code FOR UPDATE SKIP LOCKED}
     * in {@code OutboxEventRepository.findDueForProcessing()}.
     * <br><b>Invariant:</b> Each outbox event is dispatched by exactly one worker.
     * <br><b>Status:</b> Complete (no changes needed in Phase 9).
     */
    OUTBOX_EVENT_PROCESSING,

    // ── Invoice sequence ─────────────────────────────────────────────────────

    /**
     * Ensures invoice numbers are sequential and gapless per merchant.
     *
     * <p><b>Strategy:</b> {@code SELECT FOR UPDATE} (PESSIMISTIC_WRITE) on the
     * {@code InvoiceSequence} row via
     * {@code InvoiceSequenceRepository.findByMerchantIdWithLock()}.
     * <br><b>Status:</b> Complete (no changes needed in Phase 9).
     */
    INVOICE_SEQUENCE
}
