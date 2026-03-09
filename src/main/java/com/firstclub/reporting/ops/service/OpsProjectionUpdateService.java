package com.firstclub.reporting.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.ReconReportRepository;
import com.firstclub.reporting.ops.entity.InvoiceSummaryProjection;
import com.firstclub.reporting.ops.entity.PaymentSummaryProjection;
import com.firstclub.reporting.ops.entity.ReconDashboardProjection;
import com.firstclub.reporting.ops.entity.SubscriptionStatusProjection;
import com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository;
import com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Applies domain events to the four ops/summary projection tables.
 *
 * <p>Each {@code applyEventTo*} method takes a full-re-read approach: on any
 * relevant event it reads the current state from the source-of-truth tables and
 * rewrites the projection row.  This avoids incremental-math drift and keeps
 * projections trivially rebuildable.
 *
 * <p>Unknown event types are silently skipped — the switch expression returns
 * without touching any projection.  All methods are {@link Transactional} so
 * they work both inside the async {@code ProjectionEventListener} and the
 * synchronous {@code ProjectionRebuildService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpsProjectionUpdateService {

    private final SubscriptionV2Repository                subscriptionRepo;
    private final InvoiceRepository                       invoiceRepo;
    private final PaymentIntentV2Repository               intentRepo;
    private final PaymentAttemptRepository                attemptRepo;
    private final PaymentRepository                       paymentRepo;
    private final DunningAttemptRepository                dunningAttemptRepo;
    private final ReconReportRepository                   reconReportRepo;
    private final ReconMismatchRepository                 reconMismatchRepo;

    private final SubscriptionStatusProjectionRepository  subStatusRepo;
    private final InvoiceSummaryProjectionRepository      invoiceSummaryRepo;
    private final PaymentSummaryProjectionRepository      paymentSummaryRepo;
    private final ReconDashboardProjectionRepository      reconDashboardRepo;

    private final ObjectMapper objectMapper;

    // ── Subscription status projection ───────────────────────────────────

    /**
     * Update the subscription status projection row touched by this event.
     * Handles: all SUBSCRIPTION_* lifecycle events, INVOICE_CREATED,
     * PAYMENT_SUCCEEDED, PAYMENT_ATTEMPT_FAILED.
     */
    @Transactional
    public void applyEventToSubscriptionStatusProjection(DomainEvent event) {
        Long subscriptionId = resolveSubscriptionId(event);
        if (subscriptionId == null) {
            log.debug("SubStatusProj: skipping {} — no subscriptionId", event.getEventType());
            return;
        }
        subscriptionRepo.findById(subscriptionId).ifPresentOrElse(
            sub -> upsertSubStatusProjection(sub),
            () -> log.debug("SubStatusProj: subscription {} not found for event {}", subscriptionId, event.getEventType())
        );
    }

    private void upsertSubStatusProjection(SubscriptionV2 sub) {
        Long merchantId      = sub.getMerchant().getId();
        Long subscriptionId  = sub.getId();
        Long customerId      = sub.getCustomer().getId();

        // Count OPEN invoices for this subscription
        long unpaidCount = invoiceRepo.findBySubscriptionId(subscriptionId).stream()
            .filter(i -> i.getStatus() == InvoiceStatus.OPEN)
            .count();

        // Latest dunning attempt status
        String dunningState = dunningAttemptRepo.findBySubscriptionId(subscriptionId).stream()
            .max(Comparator.comparing(DunningAttempt::getId))
            .map(a -> a.getStatus().name())
            .orElse(null);

        // Latest payment intent status for this subscription
        String lastPaymentStatus = intentRepo.findBySubscriptionId(subscriptionId).stream()
            .max(Comparator.comparing(PaymentIntentV2::getId))
            .map(pi -> pi.getStatus().name())
            .orElse(null);

        SubscriptionStatusProjection proj = subStatusRepo
            .findByMerchantIdAndSubscriptionId(merchantId, subscriptionId)
            .orElseGet(() -> SubscriptionStatusProjection.builder()
                .merchantId(merchantId)
                .subscriptionId(subscriptionId)
                .build());

        proj.setCustomerId(customerId);
        proj.setStatus(sub.getStatus().name());
        proj.setNextBillingAt(sub.getNextBillingAt());
        proj.setDunningState(dunningState);
        proj.setUnpaidInvoiceCount((int) unpaidCount);
        proj.setLastPaymentStatus(lastPaymentStatus);

        subStatusRepo.save(proj);
        log.debug("SubStatusProj: upserted merchant={} sub={} status={}", merchantId, subscriptionId, proj.getStatus());
    }

    // ── Invoice summary projection ────────────────────────────────────────

    /**
     * Update the invoice summary projection row for the invoice referenced by
     * this event.  Handles: INVOICE_CREATED, PAYMENT_SUCCEEDED.
     */
    @Transactional
    public void applyEventToInvoiceSummaryProjection(DomainEvent event) {
        Long invoiceId = extractLong(event, "invoiceId");
        if (invoiceId == null) {
            log.debug("InvoiceSummaryProj: skipping {} — no invoiceId", event.getEventType());
            return;
        }
        invoiceRepo.findById(invoiceId).ifPresentOrElse(
            this::upsertInvoiceSummaryProjection,
            () -> log.debug("InvoiceSummaryProj: invoice {} not found for event {}", invoiceId, event.getEventType())
        );
    }

    private void upsertInvoiceSummaryProjection(Invoice invoice) {
        Long merchantId = invoice.getMerchantId();
        if (merchantId == null) {
            log.debug("InvoiceSummaryProj: skipping invoice {} — no merchantId", invoice.getId());
            return;
        }
        Long invoiceId = invoice.getId();

        // Determine paid_at: use updatedAt when status is PAID
        LocalDateTime paidAt = null;
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            paidAt = invoice.getUpdatedAt();
        }

        // Overdue: OPEN past due date
        boolean overdue = invoice.getStatus() == InvoiceStatus.OPEN
            && invoice.getDueDate() != null
            && invoice.getDueDate().isBefore(LocalDateTime.now());

        InvoiceSummaryProjection proj = invoiceSummaryRepo
            .findByMerchantIdAndInvoiceId(merchantId, invoiceId)
            .orElseGet(() -> InvoiceSummaryProjection.builder()
                .merchantId(merchantId)
                .invoiceId(invoiceId)
                .build());

        proj.setInvoiceNumber(invoice.getInvoiceNumber());
        proj.setCustomerId(invoice.getUserId());
        proj.setStatus(invoice.getStatus().name());
        proj.setSubtotal(invoice.getSubtotal());
        proj.setTaxTotal(invoice.getTaxTotal());
        proj.setGrandTotal(invoice.getGrandTotal());
        proj.setPaidAt(paidAt);
        proj.setOverdueFlag(overdue);

        invoiceSummaryRepo.save(proj);
        log.debug("InvoiceSummaryProj: upserted merchant={} invoice={} status={}", merchantId, invoiceId, proj.getStatus());
    }

    // ── Payment summary projection ────────────────────────────────────────

    /**
     * Update the payment summary projection row for the payment intent referenced
     * by this event. Handles: PAYMENT_INTENT_CREATED, PAYMENT_ATTEMPT_STARTED,
     * PAYMENT_ATTEMPT_FAILED, PAYMENT_SUCCEEDED, REFUND_ISSUED, REFUND_COMPLETED,
     * DISPUTE_OPENED.
     */
    @Transactional
    public void applyEventToPaymentSummaryProjection(DomainEvent event) {
        Long paymentIntentId = extractLong(event, "paymentIntentId");
        if (paymentIntentId == null) {
            // Some payment events carry invoiceId instead — look up intent from invoice
            Long invoiceId = extractLong(event, "invoiceId");
            if (invoiceId != null) {
                List<PaymentIntentV2> intents = intentRepo.findByInvoiceId(invoiceId);
                if (!intents.isEmpty()) {
                    // rebuild projection for every intent linked to this invoice
                    intents.forEach(intent -> upsertPaymentSummaryProjection(intent));
                    return;
                }
            }
            log.debug("PaymentSummaryProj: skipping {} — no paymentIntentId", event.getEventType());
            return;
        }
        intentRepo.findById(paymentIntentId).ifPresentOrElse(
            this::upsertPaymentSummaryProjection,
            () -> log.debug("PaymentSummaryProj: intent {} not found for event {}", paymentIntentId, event.getEventType())
        );
    }

    private void upsertPaymentSummaryProjection(PaymentIntentV2 intent) {
        Long merchantId      = intent.getMerchant().getId();
        Long paymentIntentId = intent.getId();

        // Captured / refunded / disputed amounts from the Payment row
        BigDecimal capturedAmount  = BigDecimal.ZERO;
        BigDecimal refundedAmount  = BigDecimal.ZERO;
        BigDecimal disputedAmount  = BigDecimal.ZERO;
        List<Payment> payments = paymentRepo.findByPaymentIntentId(paymentIntentId);
        for (Payment p : payments) {
            capturedAmount  = capturedAmount.add(p.getCapturedAmount());
            refundedAmount  = refundedAmount.add(p.getRefundedAmount());
            disputedAmount  = disputedAmount.add(p.getDisputedAmount());
        }

        // Attempt count and last gateway / failure category
        int attemptCount = attemptRepo.countByPaymentIntentId(paymentIntentId);
        List<PaymentAttempt> attempts = attemptRepo.findByPaymentIntentIdOrderByAttemptNumberAsc(paymentIntentId);

        String lastGateway         = null;
        String lastFailureCategory = null;
        if (!attempts.isEmpty()) {
            PaymentAttempt last = attempts.get(attempts.size() - 1);
            lastGateway = last.getGatewayName();
            if (last.getFailureCategory() != null) {
                lastFailureCategory = last.getFailureCategory().name();
            }
        }

        PaymentSummaryProjection proj = paymentSummaryRepo
            .findByMerchantIdAndPaymentIntentId(merchantId, paymentIntentId)
            .orElseGet(() -> PaymentSummaryProjection.builder()
                .merchantId(merchantId)
                .paymentIntentId(paymentIntentId)
                .build());

        proj.setCustomerId(intent.getCustomer().getId());
        proj.setInvoiceId(intent.getInvoiceId());
        proj.setStatus(intent.getStatus().name());
        proj.setCapturedAmount(capturedAmount);
        proj.setRefundedAmount(refundedAmount);
        proj.setDisputedAmount(disputedAmount);
        proj.setAttemptCount(attemptCount);
        proj.setLastGateway(lastGateway);
        proj.setLastFailureCategory(lastFailureCategory);

        paymentSummaryRepo.save(proj);
        log.debug("PaymentSummaryProj: upserted merchant={} intent={} status={}", merchantId, paymentIntentId, proj.getStatus());
    }

    // ── Recon dashboard projection ────────────────────────────────────────

    /**
     * Update the recon dashboard projection for the date carried by a
     * RECON_COMPLETED event.
     */
    @Transactional
    public void applyEventToReconDashboardProjection(DomainEvent event) {
        String dateStr = extractString(event, "reportDate");
        LocalDate date = null;
        if (dateStr != null) {
            try { date = LocalDate.parse(dateStr); } catch (Exception ignored) {}
        }
        if (date == null) {
            // fallback: re-read latest report row
            log.debug("ReconDashProj: no reportDate in payload for event {}", event.getEventType());
            return;
        }
        upsertReconDashboardProjection(date);
    }

    /**
     * Rebuild the recon dashboard projection row for a specific date.
     * Called both from event listener and rebuild service.
     */
    @Transactional
    public void upsertReconDashboardProjection(LocalDate date) {
        Optional<ReconReport> reportOpt = reconReportRepo.findByReportDate(date);
        if (reportOpt.isEmpty()) {
            log.debug("ReconDashProj: no ReconReport found for date={}", date);
            return;
        }
        ReconReport report = reportOpt.get();
        List<ReconMismatch> mismatches = reconMismatchRepo.findByReportId(report.getId());

        long layer2Open = mismatches.stream()
            .filter(m -> m.getType() == MismatchType.PAYMENT_LEDGER_VARIANCE && m.getStatus() == ReconMismatchStatus.OPEN)
            .count();
        long layer3Open = mismatches.stream()
            .filter(m -> m.getType() == MismatchType.LEDGER_BATCH_VARIANCE && m.getStatus() == ReconMismatchStatus.OPEN)
            .count();
        long layer4Open = mismatches.stream()
            .filter(m -> m.getType() == MismatchType.BATCH_STATEMENT_VARIANCE && m.getStatus() == ReconMismatchStatus.OPEN)
            .count();
        long resolvedCount = mismatches.stream()
            .filter(m -> m.getStatus() == ReconMismatchStatus.RESOLVED)
            .count();

        BigDecimal unresolvedAmount = BigDecimal.ZERO;
        if (report.getExpectedTotal() != null && report.getActualTotal() != null) {
            unresolvedAmount = report.getExpectedTotal().subtract(report.getActualTotal()).abs();
        }

        ReconDashboardProjection proj = reconDashboardRepo
            .findByMerchantIdIsNullAndBusinessDate(date)
            .orElseGet(() -> ReconDashboardProjection.builder()
                .merchantId(null)
                .businessDate(date)
                .build());

        proj.setLayer2Open((int) layer2Open);
        proj.setLayer3Open((int) layer3Open);
        proj.setLayer4Open((int) layer4Open);
        proj.setResolvedCount((int) resolvedCount);
        proj.setUnresolvedAmount(unresolvedAmount);

        reconDashboardRepo.save(proj);
        log.debug("ReconDashProj: upserted date={} layer2={} layer3={} layer4={}", date, layer2Open, layer3Open, layer4Open);
    }

    // ── Direct upsert entry points (for rebuild only) ─────────────────────

    /** Called by rebuild to process every SubscriptionV2 row directly. */
    @Transactional
    public void upsertSubStatusFromSource(SubscriptionV2 sub) {
        upsertSubStatusProjection(sub);
    }

    /** Called by rebuild to process every Invoice row directly. */
    @Transactional
    public void upsertInvoiceSummaryFromSource(Invoice invoice) {
        upsertInvoiceSummaryProjection(invoice);
    }

    /** Called by rebuild to process every PaymentIntentV2 row directly. */
    @Transactional
    public void upsertPaymentSummaryFromSource(PaymentIntentV2 intent) {
        upsertPaymentSummaryProjection(intent);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Resolves the subscription ID from the event. Tries the payload
     * field first, then falls back to the aggregate ID when aggregate type
     * is SUBSCRIPTION.
     */
    private Long resolveSubscriptionId(DomainEvent event) {
        // Subscription lifecycle events carry subscriptionId in the payload
        Long fromPayload = extractLong(event, "subscriptionId");
        if (fromPayload != null) return fromPayload;

        // Some events (e.g. INVOICE_CREATED) carry subscriptionId separately
        Long fromInvoice = extractLong(event, "invoiceId");
        if (fromInvoice != null) {
            return invoiceRepo.findById(fromInvoice)
                .map(Invoice::getSubscriptionId)
                .orElse(null);
        }
        return null;
    }

    private Long extractLong(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asLong() : null;
        } catch (Exception ex) {
            log.warn("OpsProjection: failed to extract '{}' from event {}", field, event.getEventType(), ex);
            return null;
        }
    }

    private String extractString(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asText() : null;
        } catch (Exception ex) {
            log.warn("OpsProjection: failed to extract '{}' from event {}", field, event.getEventType(), ex);
            return null;
        }
    }
}
