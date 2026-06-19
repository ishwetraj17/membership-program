package com.firstclub.membership.service;

/**
 * Marks a payment failure as <em>transient</em> — a network timeout, connection reset, or provider
 * 5xx where the charge's true outcome is unknown. Only these are safe to retry, and only because
 * every charge carries a deterministic {@link ChargeRequest#idempotencyKey()} that lets the PSP
 * dedupe a retry against the original attempt.
 *
 * <p>A business decline (insufficient funds, blocked card) is NOT transient: it returns a
 * {@link PaymentGateway.PaymentResult} with {@code FAILED} status rather than throwing, so it is
 * never retried and never trips the circuit breaker.
 *
 * <p>A future PSP adapter throws this for retryable transport failures; everything else propagates
 * as a normal exception (retried-never, circuit-counted only if configured).
 */
public class PaymentTransientException extends RuntimeException {

    public PaymentTransientException(String message) {
        super(message);
    }

    public PaymentTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
