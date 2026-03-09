package com.firstclub.reporting.projections.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.reporting.projections.entity.CustomerBillingSummaryProjection;
import com.firstclub.reporting.projections.entity.MerchantDailyKpiProjection;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Applies individual domain events to the read-model projection tables.
 *
 * <p>Methods are synchronous and {@link Transactional} so that callers (such as
 * the async {@code ProjectionEventListener} or the synchronous
 * {@code ProjectionRebuildService}) can choose the appropriate execution model.
 *
 * <p>Unknown event types are silently skipped — projections update only for
 * the events they care about.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectionUpdateService {

    private final CustomerBillingSummaryProjectionRepository billingSummaryRepo;
    private final MerchantDailyKpiProjectionRepository       kpiRepo;
    private final ObjectMapper                               objectMapper;

    // ── Customer billing summary ─────────────────────────────────────────────

    /**
     * Update (or create) the customer billing summary for the merchant +
     * customer pair referenced by the given domain event.
     *
     * <p>Requires the payload JSON to contain a {@code customerId} field.
     * Events without a {@code merchantId} or {@code customerId} are skipped.
     */
    @Transactional
    public void applyEventToCustomerBillingProjection(DomainEvent event) {
        Long merchantId = event.getMerchantId();
        Long customerId = extractLong(event, "customerId");

        if (merchantId == null || customerId == null) {
            log.debug("Skipping {} — missing merchantId or customerId", event.getEventType());
            return;
        }

        CustomerBillingSummaryProjection proj = billingSummaryRepo
                .findByMerchantIdAndCustomerId(merchantId, customerId)
                .orElseGet(() -> CustomerBillingSummaryProjection.builder()
                        .merchantId(merchantId)
                        .customerId(customerId)
                        .build());

        boolean changed = true;
        switch (event.getEventType()) {
            case DomainEventTypes.INVOICE_CREATED ->
                    proj.setUnpaidInvoicesCount(proj.getUnpaidInvoicesCount() + 1);

            case DomainEventTypes.PAYMENT_SUCCEEDED -> {
                BigDecimal amount = extractDecimal(event, "amount");
                if (amount != null) {
                    proj.setTotalPaidAmount(proj.getTotalPaidAmount().add(amount));
                }
                if (proj.getUnpaidInvoicesCount() > 0) {
                    proj.setUnpaidInvoicesCount(proj.getUnpaidInvoicesCount() - 1);
                }
                proj.setLastPaymentAt(event.getCreatedAt());
            }

            case DomainEventTypes.REFUND_COMPLETED, DomainEventTypes.REFUND_ISSUED -> {
                BigDecimal amount = extractDecimal(event, "amount");
                if (amount != null) {
                    proj.setTotalRefundedAmount(proj.getTotalRefundedAmount().add(amount));
                }
            }

            case DomainEventTypes.SUBSCRIPTION_ACTIVATED ->
                    proj.setActiveSubscriptionsCount(proj.getActiveSubscriptionsCount() + 1);

            case DomainEventTypes.SUBSCRIPTION_CANCELLED, DomainEventTypes.SUBSCRIPTION_SUSPENDED -> {
                if (proj.getActiveSubscriptionsCount() > 0) {
                    proj.setActiveSubscriptionsCount(proj.getActiveSubscriptionsCount() - 1);
                }
            }

            default -> { changed = false; }
        }

        if (changed) {
            billingSummaryRepo.save(proj);
            log.debug("CustomerBillingSummary updated: merchant={} customer={} event={}",
                    merchantId, customerId, event.getEventType());
        }
    }

    // ── Merchant daily KPI ───────────────────────────────────────────────────

    /**
     * Increment the merchant's daily KPI counters for the business date derived
     * from the event's {@code createdAt} timestamp.
     *
     * <p>Events without a {@code merchantId} are skipped.
     */
    @Transactional
    public void applyEventToMerchantDailyKpi(DomainEvent event) {
        Long merchantId = event.getMerchantId();
        if (merchantId == null) {
            log.debug("Skipping KPI update for {} — no merchantId", event.getEventType());
            return;
        }

        LocalDate businessDate = event.getCreatedAt() != null
                ? event.getCreatedAt().toLocalDate()
                : LocalDate.now();

        MerchantDailyKpiProjection kpi = kpiRepo
                .findByMerchantIdAndBusinessDate(merchantId, businessDate)
                .orElseGet(() -> MerchantDailyKpiProjection.builder()
                        .merchantId(merchantId)
                        .businessDate(businessDate)
                        .build());

        boolean changed = true;
        switch (event.getEventType()) {
            case DomainEventTypes.INVOICE_CREATED ->
                    kpi.setInvoicesCreated(kpi.getInvoicesCreated() + 1);

            case DomainEventTypes.PAYMENT_SUCCEEDED -> {
                kpi.setInvoicesPaid(kpi.getInvoicesPaid() + 1);
                kpi.setPaymentsCaptured(kpi.getPaymentsCaptured() + 1);
                BigDecimal amount = extractDecimal(event, "amount");
                if (amount != null) {
                    kpi.setRevenueRecognized(kpi.getRevenueRecognized().add(amount));
                }
            }

            case DomainEventTypes.REFUND_COMPLETED, DomainEventTypes.REFUND_ISSUED ->
                    kpi.setRefundsCompleted(kpi.getRefundsCompleted() + 1);

            case DomainEventTypes.DISPUTE_OPENED ->
                    kpi.setDisputesOpened(kpi.getDisputesOpened() + 1);

            default -> { changed = false; }
        }

        if (changed) {
            kpiRepo.save(kpi);
            log.debug("MerchantDailyKpi updated: merchant={} date={} event={}",
                    merchantId, businessDate, event.getEventType());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long extractLong(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asLong() : null;
        } catch (Exception ex) {
            log.warn("Could not parse '{}' from event type={} id={}",
                    field, event.getEventType(), event.getId(), ex);
            return null;
        }
    }

    private BigDecimal extractDecimal(DomainEvent event, String field) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? new BigDecimal(val.asText()) : null;
        } catch (Exception ex) {
            log.warn("Could not parse decimal '{}' from event type={} id={}",
                    field, event.getEventType(), event.getId(), ex);
            return null;
        }
    }
}
