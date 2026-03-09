package com.firstclub.reporting.projections.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconReportRepository;
import com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository;
import com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository;
import com.firstclub.reporting.ops.service.OpsProjectionUpdateService;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds a named projection from scratch by replaying all domain events.
 *
 * <p>Each rebuild is destructive — all existing rows in the target table are
 * deleted before re-processing. For large deployments, consider switching to a
 * paginated event scan; the current {@code findAll()} approach is sufficient
 * for moderate event-log sizes.
 *
 * <p>Supported projection names are exposed via {@link #SUPPORTED_PROJECTIONS}
 * so that callers (e.g. the admin controller) can validate input before calling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectionRebuildService {

    public static final Set<String> SUPPORTED_PROJECTIONS = Set.of(
            "customer_billing_summary",
            "merchant_daily_kpi",
            "subscription_status",
            "invoice_summary",
            "payment_summary",
            "recon_dashboard"
    );

    private final DomainEventRepository                       domainEventRepository;
    private final CustomerBillingSummaryProjectionRepository  billingSummaryRepo;
    private final MerchantDailyKpiProjectionRepository        kpiRepo;
    private final ProjectionUpdateService                     projectionUpdateService;

    private final SubscriptionV2Repository              subscriptionRepo;
    private final InvoiceRepository                     invoiceRepo;
    private final PaymentIntentV2Repository             intentRepo;
    private final ReconReportRepository                 reconReportRepo;
    private final SubscriptionStatusProjectionRepository subStatusRepo;
    private final InvoiceSummaryProjectionRepository    invoiceSummaryRepo;
    private final PaymentSummaryProjectionRepository    paymentSummaryRepo;
    private final ReconDashboardProjectionRepository    reconDashboardRepo;
    private final OpsProjectionUpdateService            opsProjectionUpdateService;

    /**
     * Truncate and rebuild the {@code customer_billing_summary_projection} table.
     *
     * @return stats describing the outcome of the rebuild
     */
    @Transactional
    public RebuildResponseDTO rebuildCustomerBillingSummaryProjection() {
        log.info("Starting rebuild of customer_billing_summary_projection");

        billingSummaryRepo.deleteAllInBatch();

        List<DomainEvent> events = domainEventRepository.findAll();
        for (DomainEvent event : events) {
            projectionUpdateService.applyEventToCustomerBillingProjection(event);
        }

        long records = billingSummaryRepo.count();
        log.info("customer_billing_summary_projection rebuild complete: {} events processed, {} records",
                events.size(), records);

        return RebuildResponseDTO.builder()
                .projectionName("customer_billing_summary")
                .eventsProcessed(events.size())
                .recordsInProjection(records)
                .rebuiltAt(LocalDateTime.now())
                .build();
    }

    /**
     * Truncate and rebuild the {@code merchant_daily_kpis_projection} table.
     *
     * @return stats describing the outcome of the rebuild
     */
    @Transactional
    public RebuildResponseDTO rebuildMerchantDailyKpiProjection() {
        log.info("Starting rebuild of merchant_daily_kpis_projection");

        kpiRepo.deleteAllInBatch();

        List<DomainEvent> events = domainEventRepository.findAll();
        for (DomainEvent event : events) {
            projectionUpdateService.applyEventToMerchantDailyKpi(event);
        }

        long records = kpiRepo.count();
        log.info("merchant_daily_kpis_projection rebuild complete: {} events processed, {} records",
                events.size(), records);

        return RebuildResponseDTO.builder()
                .projectionName("merchant_daily_kpi")
                .eventsProcessed(events.size())
                .recordsInProjection(records)
                .rebuiltAt(LocalDateTime.now())
                .build();
    }

    // ── Ops projections ───────────────────────────────────────────────────

    /**
     * Truncate and rebuild the {@code subscription_status_projection} table
     * directly from source SubscriptionV2 rows (no event replay needed).
     */
    @Transactional
    public RebuildResponseDTO rebuildSubscriptionStatusProjection() {
        log.info("Starting rebuild of subscription_status_projection");
        subStatusRepo.deleteAllInBatch();
        List<SubscriptionV2> subs = subscriptionRepo.findAll();
        subs.forEach(opsProjectionUpdateService::upsertSubStatusFromSource);
        long records = subStatusRepo.count();
        log.info("subscription_status_projection rebuild complete: {} sources, {} records", subs.size(), records);
        return RebuildResponseDTO.builder()
                .projectionName("subscription_status")
                .eventsProcessed(subs.size())
                .recordsInProjection(records)
                .rebuiltAt(LocalDateTime.now())
                .build();
    }

    /**
     * Truncate and rebuild the {@code invoice_summary_projection} table
     * directly from source Invoice rows.
     */
    @Transactional
    public RebuildResponseDTO rebuildInvoiceSummaryProjection() {
        log.info("Starting rebuild of invoice_summary_projection");
        invoiceSummaryRepo.deleteAllInBatch();
        List<Invoice> invoices = invoiceRepo.findAll();
        invoices.forEach(opsProjectionUpdateService::upsertInvoiceSummaryFromSource);
        long records = invoiceSummaryRepo.count();
        log.info("invoice_summary_projection rebuild complete: {} sources, {} records", invoices.size(), records);
        return RebuildResponseDTO.builder()
                .projectionName("invoice_summary")
                .eventsProcessed(invoices.size())
                .recordsInProjection(records)
                .rebuiltAt(LocalDateTime.now())
                .build();
    }

    /**
     * Truncate and rebuild the {@code payment_summary_projection} table
     * directly from source PaymentIntentV2 rows.
     */
    @Transactional
    public RebuildResponseDTO rebuildPaymentSummaryProjection() {
        log.info("Starting rebuild of payment_summary_projection");
        paymentSummaryRepo.deleteAllInBatch();
        List<PaymentIntentV2> intents = intentRepo.findAll();
        intents.forEach(opsProjectionUpdateService::upsertPaymentSummaryFromSource);
        long records = paymentSummaryRepo.count();
        log.info("payment_summary_projection rebuild complete: {} sources, {} records", intents.size(), records);
        return RebuildResponseDTO.builder()
                .projectionName("payment_summary")
                .eventsProcessed(intents.size())
                .recordsInProjection(records)
                .rebuiltAt(LocalDateTime.now())
                .build();
    }

    /**
     * Truncate and rebuild the {@code recon_dashboard_projection} table
     * from all existing ReconReport rows.
     */
    @Transactional
    public RebuildResponseDTO rebuildReconDashboardProjection() {
        log.info("Starting rebuild of recon_dashboard_projection");
        reconDashboardRepo.deleteAllInBatch();
        List<ReconReport> reports = reconReportRepo.findAll();
        reports.forEach(r -> opsProjectionUpdateService.upsertReconDashboardProjection(r.getReportDate()));
        long records = reconDashboardRepo.count();
        log.info("recon_dashboard_projection rebuild complete: {} sources, {} records", reports.size(), records);
        return RebuildResponseDTO.builder()
                .projectionName("recon_dashboard")
                .eventsProcessed(reports.size())
                .recordsInProjection(records)
                .rebuiltAt(LocalDateTime.now())
                .build();
    }
}
