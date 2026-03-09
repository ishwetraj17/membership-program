package com.firstclub.outbox.handler;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.platform.dedup.BusinessEffectDedupService;
import com.firstclub.platform.dedup.DedupResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Abstract base class for outbox handlers that apply idempotent business effects.
 *
 * <h3>Deduplication contract</h3>
 * Before delegating to the subclass's {@link #applyEffect}, this class calls
 * {@link BusinessEffectDedupService#checkAndRecord} using the effect type and
 * fingerprint computed by the subclass.  If the fingerprint has already been
 * recorded (either in the 24-h Redis window or in {@code business_effect_fingerprints}),
 * the event is silently skipped and counted as a no-op — no exception is thrown
 * so the outbox poller marks it {@code DONE} and does not retry.
 *
 * <h3>How to extend</h3>
 * <pre>{@code
 * @Component
 * @Slf4j
 * public class RefundCompletedHandler extends DedupAwareOutboxHandler {
 *
 *     @Override public String getEventType() { return DomainEventTypes.REFUND_COMPLETED; }
 *     @Override public String getEffectType() { return BusinessEffectType.REFUND_COMPLETED; }
 *
 *     @Override
 *     protected String computeFingerprint(OutboxEvent event) throws Exception {
 *         JsonNode node = mapper.readTree(event.getPayload());
 *         return fingerprintService.refundCompletedFingerprint(
 *             node.get("merchantId").asLong(), node.get("refundId").asLong());
 *     }
 *
 *     @Override
 *     protected void applyEffect(OutboxEvent event) throws Exception {
 *         // perform external call or state mutation
 *     }
 * }
 * }</pre>
 *
 * <h3>Reference IDs</h3>
 * Subclasses may override {@link #referenceType()} and {@link #referenceId(OutboxEvent)}
 * to provide structured metadata recorded alongside the fingerprint row for
 * tracing / admin queries. Defaults to {@code "OUTBOX_EVENT"} and the event's
 * own primary key.
 *
 * <h3>Thread safety</h3>
 * The {@code handle} method is stateless — it is safe to call from multiple
 * threads with different {@link OutboxEvent} instances concurrently.
 */
@Slf4j
public abstract class DedupAwareOutboxHandler implements OutboxEventHandler {

    @Autowired
    private BusinessEffectDedupService dedupService;

    // ── Abstract contract ─────────────────────────────────────────────────

    /**
     * Returns the business-effect type string used as the dedup scope.
     * Must return one of the constants in
     * {@link com.firstclub.platform.dedup.BusinessEffectType}.
     */
    protected abstract String getEffectType();

    /**
     * Computes the deterministic fingerprint for the business effect encoded
     * in the event payload.  The fingerprint must depend only on the
     * business-meaningful fields that uniquely identify the effect (not on
     * timestamps or generated IDs that may differ between retries).
     *
     * @param event the outbox event row
     * @return SHA-256 hex string (typically produced by {@link
     *         com.firstclub.platform.dedup.BusinessFingerprintService})
     * @throws Exception if the payload is malformed — causes the outbox poller
     *                   to retry rather than silently skip
     */
    protected abstract String computeFingerprint(OutboxEvent event) throws Exception;

    /**
     * Applies the actual business side effect.  Only called when the dedup
     * check returns {@link DedupResult#NEW}.
     *
     * @param event the outbox event row
     * @throws Exception propagated to the outbox poller for retry scheduling
     */
    protected abstract void applyEffect(OutboxEvent event) throws Exception;

    // ── Optional overrides ────────────────────────────────────────────────

    /**
     * Entity type label stored in the fingerprint row for admin visibility.
     * Override to provide a more meaningful label.
     */
    protected String referenceType() {
        return "OUTBOX_EVENT";
    }

    /**
     * Entity ID stored in the fingerprint row.  Defaults to the outbox event
     * primary key.
     */
    protected Long referenceId(OutboxEvent event) {
        return event.getId();
    }

    // ── OutboxEventHandler implementation ─────────────────────────────────

    @Override
    public final void handle(OutboxEvent event) throws Exception {
        String effectType   = getEffectType();
        String fingerprint  = computeFingerprint(event);

        DedupResult result = dedupService.checkAndRecord(
                effectType, fingerprint, referenceType(), referenceId(event));

        if (result == DedupResult.DUPLICATE) {
            log.info("[DEDUP] Skipping duplicate {} effect for outbox event {} (fp={})",
                    effectType, event.getId(), abbreviate(fingerprint));
            return;   // treated as success — outbox marks DONE, no retry
        }

        log.debug("[DEDUP] Applying new {} effect for outbox event {} (fp={})",
                effectType, event.getId(), abbreviate(fingerprint));
        applyEffect(event);
    }

    private static String abbreviate(String fp) {
        return fp == null ? "null" : (fp.length() > 16 ? fp.substring(0, 16) + "…" : fp);
    }
}
