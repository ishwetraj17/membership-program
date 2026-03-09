package com.firstclub.payments.gateway;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves the status of a {@code UNKNOWN} payment attempt by polling the gateway.
 *
 * <h3>When is this used?</h3>
 * After a gateway call times out (or returns an ambiguous response), the attempt is
 * marked {@code UNKNOWN}.  The
 * {@link com.firstclub.payments.recovery.GatewayTimeoutRecoveryScheduler} periodically
 * calls {@link #resolveStatus} for each outstanding {@code UNKNOWN} attempt.
 *
 * <h3>Resolution outcomes</h3>
 * <ul>
 *   <li>{@link GatewayResultStatus#SUCCEEDED} \u2014 gateway confirms the payment was processed.</li>
 *   <li>{@link GatewayResultStatus#FAILED} \u2014 gateway confirms the payment was not processed.</li>
 *   <li>{@link GatewayResultStatus#UNKNOWN} \u2014 gateway is still unable to confirm; retry later.</li>
 * </ul>
 *
 * <h3>Idempotency key usage</h3>
 * The {@code gatewayIdempotencyKey} stored on the attempt is used as the lookup key.
 * This is safe to resend to the gateway: if the original payment was processed, the
 * gateway returns the original response; if it was not, it can be retried safely.
 *
 * <h3>Simulated implementation</h3>
 * The default {@link #queryGatewayStatus} stub returns {@code SUCCEEDED} for any
 * attempt number \u2264 5 and {@code FAILED} otherwise.  Override this method in tests
 * or replace with a real HTTP client for production.
 */
@Service
@Slf4j
public class GatewayStatusResolver {

    /**
     * Polls the gateway for the status of a previously submitted attempt.
     *
     * @param attempt a {@code PaymentAttempt} whose {@code status} is {@code UNKNOWN}
     * @return a {@link GatewayResult} reflecting the gateway's current knowledge
     */
    public GatewayResult resolveStatus(PaymentAttempt attempt) {
        log.info("[GATEWAY-RESOLVER] Polling gateway status: attemptId={} intentId={} "
                        + "idempotencyKey={}",
                attempt.getId(),
                attempt.getPaymentIntent() != null ? attempt.getPaymentIntent().getId() : "null",
                attempt.getGatewayIdempotencyKey());

        GatewayResult result = queryGatewayStatus(attempt);

        log.info("[GATEWAY-RESOLVER] Resolved: attemptId={} status={}",
                attempt.getId(), result.status());
        return result;
    }

    // ── Simulation ────────────────────────────────────────────────────────────

    /**
     * Simulated gateway status query.  Override or replace for real HTTP polling.
     *
     * <p>Default: attempts with attempt number \u2264 5 are confirmed SUCCEEDED;
     * all others are confirmed FAILED.  If the idempotency key is null (pre-Phase-8
     * attempt), returns UNKNOWN to indicate unresolvable.
     */
    protected GatewayResult queryGatewayStatus(PaymentAttempt attempt) {
        if (attempt.getGatewayIdempotencyKey() == null) {
            log.warn("[GATEWAY-RESOLVER] No idempotency key on attempt {}; cannot resolve",
                    attempt.getId());
            return GatewayResult.unknown(null, null, null);
        }

        if (attempt.getAttemptNumber() <= 5) {
            String txnId = (attempt.getGatewayName() != null
                    ? attempt.getGatewayName().toUpperCase() : "GW")
                    + "-RECONCILED-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            return GatewayResult.succeeded(txnId, "RECONCILED_SUCCESS", null);
        } else {
            return GatewayResult.failed(
                    FailureCategory.GATEWAY_ERROR,
                    "Gateway confirmed payment was not processed",
                    "GATEWAY_NOT_PROCESSED",
                    null
            );
        }
    }
}
