package com.firstclub.membership.service;

/**
 * Port for charging members. Membership owns billing intent (amounts, timing, idempotency); the
 * actual money movement belongs to a payment provider behind this interface — swap the adapter to
 * integrate any PSP (Stripe/Razorpay/Cashfree/Juspay/…) without touching subscription logic.
 *
 * <p>The contract is deliberately PSP-agnostic and built for production safety:
 * <ul>
 *   <li>requests carry a deterministic {@code idempotencyKey} so a retry can never double-charge;</li>
 *   <li>requests carry a {@code correlationId} for end-to-end tracing;</li>
 *   <li>{@link PaymentResult} models an explicit {@link PaymentResult.Status} — including
 *       {@code PENDING} for providers that confirm asynchronously via webhook;</li>
 *   <li>a business decline is a {@code FAILED} result, while an unknown-outcome transport failure is
 *       a thrown {@link PaymentTransientException} — the two are handled very differently by the
 *       resilience layer.</li>
 * </ul>
 */
public interface PaymentGateway {

    PaymentResult charge(ChargeRequest request);

    PaymentResult refund(RefundRequest request);

    /**
     * Outcome of a payment operation.
     *
     * @param reference     Provider reference for the charge/refund (for later refunds, reconciliation,
     *                      and webhook correlation). May be null for a synchronous decline.
     * @param status        Explicit outcome — see {@link Status}.
     * @param failureReason Provider/decline reason when {@code status == FAILED}; null otherwise.
     */
    record PaymentResult(String reference, Status status, String failureReason) {

        public enum Status {
            /** Money moved (or the refund was accepted). */
            SUCCEEDED,
            /** Declined by the provider (insufficient funds, blocked card, …). Not retried. */
            FAILED,
            /** Accepted but not yet confirmed — a webhook will finalize it. */
            PENDING
        }

        /** Compatibility/convenience constructor: {@code true} → SUCCEEDED, {@code false} → FAILED. */
        public PaymentResult(String reference, boolean success) {
            this(reference, success ? Status.SUCCEEDED : Status.FAILED, null);
        }

        public static PaymentResult succeeded(String reference) {
            return new PaymentResult(reference, Status.SUCCEEDED, null);
        }

        public static PaymentResult failed(String reference, String reason) {
            return new PaymentResult(reference, Status.FAILED, reason);
        }

        public static PaymentResult pending(String reference) {
            return new PaymentResult(reference, Status.PENDING, null);
        }

        /** True only when the charge fully succeeded — existing callers keep their semantics. */
        public boolean success() {
            return status == Status.SUCCEEDED;
        }

        public boolean pending() {
            return status == Status.PENDING;
        }
    }
}
