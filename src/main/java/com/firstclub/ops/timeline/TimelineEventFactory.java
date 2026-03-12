package com.firstclub.ops.timeline;

import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Factory that builds {@link TimelineEvent} instances for ops/repair operations
 * that do not originate from the domain-event stream.
 *
 * <p>The {@link com.firstclub.reporting.ops.timeline.service.TimelineMapper}
 * already handles automatic projection of domain events onto the timeline.
 * This factory covers the <em>manual</em> half: repair actions, DLQ operations,
 * mismatch acknowledgements, and support case lifecycle transitions that are
 * initiated by platform operators rather than triggered by business events.
 *
 * <p>All factory methods produce events with {@code sourceEventId = null}
 * (i.e., manual entries) so they bypass the deduplication constraint on the
 * {@code ops_timeline_events} table.
 */
@Component
public class TimelineEventFactory {

    // ── Timeline event-type constant strings ─────────────────────────────────

    public static final String CUSTOMER_CREATED         = "customer_created";
    public static final String SUBSCRIPTION_CREATED     = "subscription_created";
    public static final String INVOICE_FINALIZED        = "invoice_finalized";
    public static final String PAYMENT_ATTEMPT_STARTED  = "payment_attempt_started";
    public static final String PAYMENT_SUCCEEDED        = "payment_succeeded";
    public static final String PAYMENT_FAILED           = "payment_failed";
    public static final String PAYMENT_UNKNOWN          = "payment_unknown";
    public static final String REFUND_CREATED           = "refund_created";
    public static final String DISPUTE_OPENED           = "dispute_opened";
    public static final String DUNNING_SCHEDULED        = "dunning_scheduled";
    public static final String DUNNING_ATTEMPTED        = "dunning_attempted";
    public static final String WEBHOOK_DELIVERED        = "webhook_delivered";
    public static final String WEBHOOK_FAILED           = "webhook_failed";
    public static final String OUTBOX_REQUEUED          = "outbox_requeued";
    public static final String RECON_MISMATCH_CREATED   = "recon_mismatch_created";
    public static final String RECON_MISMATCH_RESOLVED  = "recon_mismatch_resolved";

    /** Repair event type written for each manual ops action. */
    public static final String REPAIR_OUTBOX_RETRY      = "repair.outbox_retry";
    public static final String REPAIR_WEBHOOK_RETRY     = "repair.webhook_retry";
    public static final String REPAIR_RECON_RERUN       = "repair.recon_rerun";
    public static final String REPAIR_INVOICE_REBUILD   = "repair.invoice_rebuild";
    public static final String REPAIR_LEDGER_REBUILD    = "repair.ledger_rebuild";
    public static final String REPAIR_DLQ_REQUEUE       = "repair.dlq_requeue";
    public static final String REPAIR_MISMATCH_ACK      = "repair.mismatch_ack";

    // ── Factory methods ───────────────────────────────────────────────────────

    /** Timeline event for manually retrying a stuck outbox event. */
    public TimelineEvent forOutboxRetry(Long eventId, Long merchantId, String actorContext) {
        return build(merchantId, "OUTBOX_EVENT", eventId, REPAIR_OUTBOX_RETRY,
                "Outbox event #" + eventId + " requeued",
                "Event manually reset to NEW for reprocessing" + actor(actorContext));
    }

    /** Timeline event for manually retrying a failed webhook delivery. */
    public TimelineEvent forWebhookRetry(Long deliveryId, Long merchantId, String actorContext) {
        return build(merchantId, "WEBHOOK_DELIVERY", deliveryId, REPAIR_WEBHOOK_RETRY,
                "Webhook delivery #" + deliveryId + " retry scheduled",
                "Delivery manually reset to PENDING for retry" + actor(actorContext));
    }

    /** Timeline event for a manually triggered reconciliation run. */
    public TimelineEvent forReconRerun(LocalDate date, Long merchantId, String actorContext) {
        return build(merchantId, "RECON_REPORT", null, REPAIR_RECON_RERUN,
                "Reconciliation re-run for " + date,
                "Manual reconciliation triggered for date=" + date + actor(actorContext));
    }

    /** Timeline event for rebuilding a single invoice's totals. */
    public TimelineEvent forInvoiceRebuild(Long invoiceId, Long merchantId, String actorContext) {
        return build(merchantId, "INVOICE", invoiceId, REPAIR_INVOICE_REBUILD,
                "Invoice #" + invoiceId + " totals rebuilt",
                "Grand total recomputed from line items" + actor(actorContext));
    }

    /** Timeline event for rebuilding the ledger balance snapshot. */
    public TimelineEvent forLedgerRebuild(Long merchantId, String dateStr, String actorContext) {
        return build(merchantId, "LEDGER_SNAPSHOT", null, REPAIR_LEDGER_REBUILD,
                "Ledger snapshot rebuilt for " + dateStr,
                "Balance snapshot regenerated for date=" + dateStr + actor(actorContext));
    }

    /** Timeline event for re-enqueuing a dead-letter message. */
    public TimelineEvent forDlqRequeue(Long dlqId, Long merchantId, String actorContext) {
        return build(merchantId, "DLQ_MESSAGE", dlqId, REPAIR_DLQ_REQUEUE,
                "DLQ message #" + dlqId + " requeued",
                "Re-enqueued from dead-letter queue to outbox" + actor(actorContext));
    }

    /** Timeline event for acknowledging a reconciliation mismatch. */
    public TimelineEvent forMismatchAcknowledge(Long mismatchId, Long merchantId, String actorContext) {
        return build(merchantId, "RECON_MISMATCH", mismatchId, REPAIR_MISMATCH_ACK,
                "Recon mismatch #" + mismatchId + " acknowledged",
                "Status set to ACKNOWLEDGED" + actor(actorContext));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private TimelineEvent build(Long merchantId, String entityType, Long entityId,
                                String eventType, String title, String summary) {
        return TimelineEvent.builder()
                .merchantId(merchantId)
                .entityType(entityType)
                .entityId(entityId != null ? entityId : 0L)
                .eventType(eventType)
                .eventTime(LocalDateTime.now())
                .title(title)
                .summary(summary)
                .build();
    }

    private String actor(String actorContext) {
        return actorContext != null ? " [actor=" + actorContext + "]" : "";
    }
}
