package com.firstclub.reporting.projections.service;

import com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository;
import com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository;
import com.firstclub.reporting.projections.dto.ProjectionLagReport;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.CustomerPaymentSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.LedgerBalanceProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantRevenueProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Measures the staleness lag for each read-model projection.
 *
 * <p>Lag is computed as the number of seconds between the oldest
 * {@code updated_at} timestamp in a projection table and the current system
 * time.  An empty table reports a lag of {@code -1}.
 *
 * <p>Callers can then decide whether a projection is acceptably fresh or
 * needs a rebuild, using {@link ProjectionLagReport#isStale(long)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectionLagMonitor {

    private final CustomerBillingSummaryProjectionRepository customerBillingRepo;
    private final MerchantDailyKpiProjectionRepository       kpiRepo;
    private final SubscriptionStatusProjectionRepository     subStatusRepo;
    private final InvoiceSummaryProjectionRepository         invoiceSummaryRepo;
    private final PaymentSummaryProjectionRepository         paymentSummaryRepo;
    private final ReconDashboardProjectionRepository         reconDashboardRepo;
    private final CustomerPaymentSummaryProjectionRepository customerPaymentRepo;
    private final LedgerBalanceProjectionRepository          ledgerBalanceRepo;
    private final MerchantRevenueProjectionRepository        merchantRevenueRepo;

    /**
     * Compute the staleness lag for all known projections.
     *
     * @return an ordered map of projection-name → {@link ProjectionLagReport}
     */
    public Map<String, ProjectionLagReport> checkAllProjections() {
        Map<String, ProjectionLagReport> result = new LinkedHashMap<>();
        result.put("customer_billing_summary",  lag("customer_billing_summary",  customerBillingRepo.findOldestUpdatedAt()));
        result.put("merchant_daily_kpi",         lag("merchant_daily_kpi",         kpiRepo.findOldestUpdatedAt()));
        result.put("subscription_status",        lag("subscription_status",        subStatusRepo.findOldestUpdatedAt()));
        result.put("invoice_summary",            lag("invoice_summary",            invoiceSummaryRepo.findOldestUpdatedAt()));
        result.put("payment_summary",            lag("payment_summary",            paymentSummaryRepo.findOldestUpdatedAt()));
        result.put("recon_dashboard",            lag("recon_dashboard",            reconDashboardRepo.findOldestUpdatedAt()));
        result.put("customer_payment_summary",   lag("customer_payment_summary",   customerPaymentRepo.findOldestUpdatedAt()));
        result.put("ledger_balance",             lag("ledger_balance",             ledgerBalanceRepo.findOldestUpdatedAt()));
        result.put("merchant_revenue",           lag("merchant_revenue",           merchantRevenueRepo.findOldestUpdatedAt()));
        log.debug("Projection lag check complete: {} projections evaluated", result.size());
        return result;
    }

    /**
     * Compute lag for a single named projection.
     *
     * @param projectionName logical projection name
     * @return lag report; {@code lagSeconds == -1} if the table is empty
     */
    public ProjectionLagReport getLag(String projectionName) {
        Optional<LocalDateTime> oldest = switch (projectionName) {
            case "customer_billing_summary" -> customerBillingRepo.findOldestUpdatedAt();
            case "merchant_daily_kpi"        -> kpiRepo.findOldestUpdatedAt();
            case "subscription_status"       -> subStatusRepo.findOldestUpdatedAt();
            case "invoice_summary"           -> invoiceSummaryRepo.findOldestUpdatedAt();
            case "payment_summary"           -> paymentSummaryRepo.findOldestUpdatedAt();
            case "recon_dashboard"           -> reconDashboardRepo.findOldestUpdatedAt();
            case "customer_payment_summary"  -> customerPaymentRepo.findOldestUpdatedAt();
            case "ledger_balance"            -> ledgerBalanceRepo.findOldestUpdatedAt();
            case "merchant_revenue"          -> merchantRevenueRepo.findOldestUpdatedAt();
            default -> {
                log.warn("Unknown projection name '{}' passed to getLag()", projectionName);
                yield Optional.empty();
            }
        };
        return lag(projectionName, oldest);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ProjectionLagReport lag(String name, Optional<LocalDateTime> oldest) {
        if (oldest.isEmpty()) {
            return ProjectionLagReport.builder()
                    .projectionName(name)
                    .oldestUpdatedAt(null)
                    .lagSeconds(-1L)
                    .build();
        }
        long lag = Duration.between(oldest.get(), LocalDateTime.now()).toSeconds();
        return ProjectionLagReport.builder()
                .projectionName(name)
                .oldestUpdatedAt(oldest.get())
                .lagSeconds(lag)
                .build();
    }
}
