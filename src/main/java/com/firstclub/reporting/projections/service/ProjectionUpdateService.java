package com.firstclub.reporting.projections.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.reporting.projections.entity.CustomerBillingSummaryProjection;
import com.firstclub.reporting.projections.entity.MerchantDailyKpiProjection;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjection;
import com.firstclub.reporting.projections.entity.LedgerBalanceProjection;
import com.firstclub.reporting.projections.entity.MerchantRevenueProjection;
import com.firstclub.reporting.projections.repository.CustomerPaymentSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.LedgerBalanceProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantRevenueProjectionRepository;
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
    private final CustomerPaymentSummaryProjectionRepository customerPaymentRepo;
    private final LedgerBalanceProjectionRepository          ledgerBalanceRepo;
    private final MerchantRevenueProjectionRepository        merchantRevenueRepo;

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

    // ── Customer payment summary (Phase 19) ──────────────────────────────────

    /**
     * Update the per-customer payment summary projection from a domain event.
     *
     * <p>Tracks successful/failed payment counts and minor-unit charged/refunded
     * totals. Requires {@code customerId} in the event payload.
     */
    @Transactional
    public void applyEventToCustomerPaymentSummary(DomainEvent event) {
        Long merchantId = event.getMerchantId();
        Long customerId = extractLong(event, "customerId");

        if (merchantId == null || customerId == null) {
            log.debug("Skipping customer-payment-summary for {} — missing merchantId/customerId", event.getEventType());
            return;
        }

        CustomerPaymentSummaryProjection proj = customerPaymentRepo
                .findByMerchantIdAndCustomerId(merchantId, customerId)
                .orElseGet(() -> CustomerPaymentSummaryProjection.builder()
                        .merchantId(merchantId)
                        .customerId(customerId)
                        .build());

        boolean changed = true;
        switch (event.getEventType()) {
            case DomainEventTypes.PAYMENT_SUCCEEDED -> {
                long amountMinor = extractAmountMinor(event);
                proj.setTotalChargedMinor(proj.getTotalChargedMinor() + amountMinor);
                proj.setSuccessfulPayments(proj.getSuccessfulPayments() + 1);
                proj.setLastPaymentAt(event.getCreatedAt());
            }
            case DomainEventTypes.PAYMENT_ATTEMPT_FAILED ->
                    proj.setFailedPayments(proj.getFailedPayments() + 1);
            case DomainEventTypes.REFUND_COMPLETED, DomainEventTypes.REFUND_ISSUED -> {
                long amountMinor = extractAmountMinor(event);
                proj.setTotalRefundedMinor(proj.getTotalRefundedMinor() + amountMinor);
            }
            default -> { changed = false; }
        }

        if (changed) {
            customerPaymentRepo.save(proj);
            log.debug("CustomerPaymentSummary updated: merchant={} customer={} event={}",
                    merchantId, customerId, event.getEventType());
        }
    }

    // ── Ledger balance projection (Phase 19) ─────────────────────────────────

    /**
     * Update the per-user ledger balance projection from a domain event.
     *
     * <p>Uses {@code PAYMENT_SUCCEEDED} as a credit and
     * {@code REFUND_COMPLETED}/{@code REFUND_ISSUED} as debits.
     * Requires a {@code userId} or {@code customerId} field in the payload.
     */
    @Transactional
    public void applyEventToLedgerBalance(DomainEvent event) {
        Long merchantId = event.getMerchantId();
        Long userId = extractLong(event, "userId");
        if (userId == null) {
            userId = extractLong(event, "customerId"); // fallback
        }

        if (merchantId == null || userId == null) {
            log.debug("Skipping ledger-balance for {} — missing merchantId/userId", event.getEventType());
            return;
        }

        final Long resolvedUserId = userId;
        LedgerBalanceProjection proj = ledgerBalanceRepo
                .findByMerchantIdAndUserId(merchantId, userId)
                .orElseGet(() -> LedgerBalanceProjection.builder()
                        .merchantId(merchantId)
                        .userId(resolvedUserId)
                        .build());

        boolean changed = true;
        switch (event.getEventType()) {
            case DomainEventTypes.PAYMENT_SUCCEEDED -> {
                long amountMinor = extractAmountMinor(event);
                proj.setTotalCreditsMinor(proj.getTotalCreditsMinor() + amountMinor);
                proj.setNetBalanceMinor(proj.getTotalCreditsMinor() - proj.getTotalDebitsMinor());
                proj.setEntryCount(proj.getEntryCount() + 1);
                proj.setLastEntryAt(event.getCreatedAt());
            }
            case DomainEventTypes.REFUND_COMPLETED, DomainEventTypes.REFUND_ISSUED -> {
                long amountMinor = extractAmountMinor(event);
                proj.setTotalDebitsMinor(proj.getTotalDebitsMinor() + amountMinor);
                proj.setNetBalanceMinor(proj.getTotalCreditsMinor() - proj.getTotalDebitsMinor());
                proj.setEntryCount(proj.getEntryCount() + 1);
                proj.setLastEntryAt(event.getCreatedAt());
            }
            default -> { changed = false; }
        }

        if (changed) {
            ledgerBalanceRepo.save(proj);
            log.debug("LedgerBalance updated: merchant={} user={} event={}",
                    merchantId, resolvedUserId, event.getEventType());
        }
    }

    // ── Merchant revenue projection (Phase 19) ───────────────────────────────

    /**
     * Update the per-merchant all-time revenue projection from a domain event.
     */
    @Transactional
    public void applyEventToMerchantRevenue(DomainEvent event) {
        Long merchantId = event.getMerchantId();
        if (merchantId == null) {
            log.debug("Skipping merchant-revenue for {} — no merchantId", event.getEventType());
            return;
        }

        MerchantRevenueProjection proj = merchantRevenueRepo.findById(merchantId)
                .orElseGet(() -> MerchantRevenueProjection.builder()
                        .merchantId(merchantId)
                        .build());

        boolean changed = true;
        switch (event.getEventType()) {
            case DomainEventTypes.PAYMENT_SUCCEEDED -> {
                long amountMinor = extractAmountMinor(event);
                proj.setTotalRevenueMinor(proj.getTotalRevenueMinor() + amountMinor);
                proj.setNetRevenueMinor(proj.getTotalRevenueMinor() - proj.getTotalRefundsMinor());
            }
            case DomainEventTypes.REFUND_COMPLETED, DomainEventTypes.REFUND_ISSUED -> {
                long amountMinor = extractAmountMinor(event);
                proj.setTotalRefundsMinor(proj.getTotalRefundsMinor() + amountMinor);
                proj.setNetRevenueMinor(proj.getTotalRevenueMinor() - proj.getTotalRefundsMinor());
            }
            case DomainEventTypes.SUBSCRIPTION_ACTIVATED ->
                    proj.setActiveSubscriptions(proj.getActiveSubscriptions() + 1);
            case DomainEventTypes.SUBSCRIPTION_CANCELLED, DomainEventTypes.SUBSCRIPTION_SUSPENDED -> {
                if (proj.getActiveSubscriptions() > 0) {
                    proj.setActiveSubscriptions(proj.getActiveSubscriptions() - 1);
                }
                proj.setChurnedSubscriptions(proj.getChurnedSubscriptions() + 1);
            }
            default -> { changed = false; }
        }

        if (changed) {
            merchantRevenueRepo.save(proj);
            log.debug("MerchantRevenue updated: merchant={} event={}", merchantId, event.getEventType());
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

    /**
     * Extract {@code amountMinor} from the event payload; falls back to
     * {@code amount * 100} if only the major-unit field is present.
     */
    private long extractAmountMinor(DomainEvent event) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            JsonNode minor = node.get("amountMinor");
            if (minor != null && !minor.isNull()) {
                return minor.asLong();
            }
            JsonNode major = node.get("amount");
            if (major != null && !major.isNull()) {
                return new BigDecimal(major.asText()).multiply(BigDecimal.valueOf(100)).longValue();
            }
        } catch (Exception ex) {
            log.warn("Could not parse amount from event type={} id={}", event.getEventType(), event.getId(), ex);
        }
        return 0L;
    }
}
