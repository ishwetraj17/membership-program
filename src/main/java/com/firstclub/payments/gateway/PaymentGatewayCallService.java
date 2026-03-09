package com.firstclub.payments.gateway;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Encapsulates the mechanics of submitting a payment request to a gateway.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>Generate a deterministic {@code gateway_idempotency_key} in the format
 *       {@code firstclub:{intentId}:{attemptNumber}}.</li>
 *   <li>Stamp the attempt with {@code gatewayIdempotencyKey}, {@code startedAt},
 *       and {@code processorNodeId} before dispatch.</li>
 *   <li>Simulate or delegate the actual gateway call and return a
 *       {@link GatewayResult}.</li>
 *   <li>Return {@link GatewayResult#timeout} for timed-out calls — NOT a failure.</li>
 * </ol>
 *
 * <h3>UNKNOWN vs FAILURE</h3>
 * A timeout from the gateway is <strong>not</strong> equivalent to a declined transaction.
 * The gateway may have processed the payment after the client deadline elapsed.  Returning
 * {@link GatewayResultStatus#TIMEOUT} signals that the outcome is undetermined; the
 * async recovery scheduler will poll the gateway for the true status.
 *
 * <h3>Testability</h3>
 * The inner simulation can be replaced by overriding {@link #simulateGatewayCall}.
 */
@Service
@Slf4j
public class PaymentGatewayCallService {

    /** Timeout threshold in milliseconds (configurable; default 5 000 ms). */
    @Value("${payments.gateway.timeout-ms:5000}")
    private long gatewayTimeoutMs;

    /**
     * Fraction of simulated calls that will produce a {@code TIMEOUT/UNKNOWN} outcome
     * (configurable; default 0 \u2192 no simulated timeouts in production simulation).
     */
    @Value("${payments.gateway.simulated-timeout-rate:0}")
    private int simulatedTimeoutRate;   // 0-100 (percent)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submits a payment attempt to the gateway.
     *
     * <p>Side effects on {@code attempt} (caller must save after this returns):
     * <ul>
     *   <li>{@code gatewayIdempotencyKey} set to {@code firstclub:{intentId}:{attemptNumber}}</li>
     *   <li>{@code startedAt} set to current JVM time</li>
     *   <li>{@code processorNodeId} set to this node's hostname identifier</li>
     * </ul>
     *
     * @param attempt the INITIATED/STARTED attempt to dispatch
     * @param intent  the parent payment intent (for amount, currency, idempotency context)
     * @return normalised {@link GatewayResult}; never null
     */
    public GatewayResult submitPayment(PaymentAttempt attempt, PaymentIntentV2 intent) {
        String idempotencyKey = buildGatewayIdempotencyKey(
                intent.getId(), attempt.getAttemptNumber());

        attempt.setGatewayIdempotencyKey(idempotencyKey);
        attempt.setStartedAt(LocalDateTime.now());
        attempt.setProcessorNodeId(resolveProcessorNodeId());

        log.info("[GATEWAY] Submitting payment: intentId={} attemptId={} attemptNumber={} "
                        + "gateway={} idempotencyKey={}",
                intent.getId(), attempt.getId(), attempt.getAttemptNumber(),
                attempt.getGatewayName(), idempotencyKey);

        long startNanos = System.nanoTime();
        GatewayResult result = simulateGatewayCall(attempt, intent);
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;

        log.info("[GATEWAY] Result: intentId={} attemptId={} status={} latencyMs={}",
                intent.getId(), attempt.getId(), result.status(), elapsed);

        return result;
    }

    /**
     * Builds the deterministic gateway idempotency key.
     *
     * <p>Format: {@code firstclub:{intentId}:{attemptNumber}}.
     * This is safe to re-submit: the gateway will recognise the key and return
     * the same response without processing the payment again.
     *
     * @param intentId      payment intent ID
     * @param attemptNumber 1-based attempt sequence number
     * @return idempotency key string
     */
    public String buildGatewayIdempotencyKey(Long intentId, int attemptNumber) {
        return "firstclub:" + intentId + ":" + attemptNumber;
    }

    // ── Simulation \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /**
     * Simulated gateway call.  Override in tests to inject controlled outcomes.
     *
     * <p>Default behaviour:
     * <ul>
     *   <li>If {@code simulatedTimeoutRate > 0} and the random seed hits it \u2192 TIMEOUT.</li>
     *   <li>First attempt \u2192 SUCCEEDED.</li>
     *   <li>Otherwise \u2192 SUCCEEDED.</li>
     * </ul>
     */
    protected GatewayResult simulateGatewayCall(PaymentAttempt attempt, PaymentIntentV2 intent) {
        // Simulated timeout injection for integration tests / demos
        if (simulatedTimeoutRate > 0) {
            int roll = (int) (Math.random() * 100);
            if (roll < simulatedTimeoutRate) {
                log.debug("[GATEWAY-SIM] Injecting simulated TIMEOUT for attempt {}",
                        attempt.getId());
                return GatewayResult.timeout(gatewayTimeoutMs);
            }
        }

        String txnId = attempt.getGatewayName().toUpperCase()
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return GatewayResult.succeeded(txnId, "SUCCESS", 0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String resolveProcessorNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName()
                    + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } catch (Exception e) {
            return "unknown-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
    }
}
