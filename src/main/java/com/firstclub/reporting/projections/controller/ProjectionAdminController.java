package com.firstclub.reporting.projections.controller;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.reporting.ops.dto.InvoiceSummaryProjectionDTO;
import com.firstclub.reporting.ops.dto.PaymentSummaryProjectionDTO;
import com.firstclub.reporting.ops.dto.ReconDashboardProjectionDTO;
import com.firstclub.reporting.ops.dto.SubscriptionStatusProjectionDTO;
import com.firstclub.reporting.ops.entity.InvoiceSummaryProjection;
import com.firstclub.reporting.ops.entity.PaymentSummaryProjection;
import com.firstclub.reporting.ops.entity.ReconDashboardProjection;
import com.firstclub.reporting.ops.entity.SubscriptionStatusProjection;
import com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository;
import com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository;
import com.firstclub.reporting.projections.dto.ConsistencyReport;
import com.firstclub.reporting.projections.dto.CustomerBillingSummaryProjectionDTO;
import com.firstclub.reporting.projections.dto.MerchantDailyKpiProjectionDTO;
import com.firstclub.reporting.projections.dto.ProjectionLagReport;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.entity.CustomerBillingSummaryProjection;
import com.firstclub.reporting.projections.entity.MerchantDailyKpiProjection;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import com.firstclub.reporting.projections.service.ProjectionConsistencyChecker;
import com.firstclub.reporting.projections.service.ProjectionLagMonitor;
import com.firstclub.reporting.projections.service.ProjectionRebuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Admin reads against the pre-computed projection tables.
 *
 * <p>All endpoints are secured with {@code ADMIN} role. Results are sourced
 * from the projection tables — never from the hot transactional tables — so
 * these reads are fast even at high subscription/invoice volumes.
 *
 * <p>Base path: {@code /api/v2/admin/projections}
 */
@RestController
@RequestMapping("/api/v2/admin/projections")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Projection Admin (V2)", description = "Fast reads from pre-computed projection tables; trigger rebuilds")
public class ProjectionAdminController {

    private final CustomerBillingSummaryProjectionRepository  billingSummaryRepo;
    private final MerchantDailyKpiProjectionRepository        kpiRepo;
    private final ProjectionRebuildService                    projectionRebuildService;
    private final ProjectionLagMonitor                        lagMonitor;
    private final ProjectionConsistencyChecker                consistencyChecker;

    private final SubscriptionStatusProjectionRepository   subStatusRepo;
    private final InvoiceSummaryProjectionRepository       invoiceSummaryRepo;
    private final PaymentSummaryProjectionRepository       paymentSummaryRepo;
    private final ReconDashboardProjectionRepository       reconDashboardRepo;

    // ── Customer billing summary ─────────────────────────────────────────────

    /**
     * GET /api/v2/admin/projections/customer-billing-summary
     *
     * <p>Returns one row per (merchant, customer) pair. All parameters are optional.
     * If {@code customerId} is provided, {@code merchantId} must also be supplied.
     */
    @Operation(summary = "List customer billing summary projections",
               description = "Pre-computed per-customer billing totals. Faster than aggregating raw invoices.")
    @GetMapping("/customer-billing-summary")
    public ResponseEntity<Page<CustomerBillingSummaryProjectionDTO>> getCustomerBillingSummary(

            @Parameter(description = "Filter by merchant ID")
            @RequestParam(required = false) Long merchantId,

            @Parameter(description = "Filter by customer ID (requires merchantId)")
            @RequestParam(required = false) Long customerId,

            @PageableDefault(size = 50, sort = "updatedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<CustomerBillingSummaryProjection> page;
        if (merchantId != null && customerId != null) {
            // Wrap single-item result in a page
            page = billingSummaryRepo
                    .findByMerchantIdAndCustomerId(merchantId, customerId)
                    .map(proj -> (Page<CustomerBillingSummaryProjection>)
                            new org.springframework.data.domain.PageImpl<>(
                                    java.util.List.of(proj), pageable, 1L))
                    .orElse(org.springframework.data.domain.Page.empty(pageable));
        } else if (merchantId != null) {
            page = billingSummaryRepo.findByMerchantId(merchantId, pageable);
        } else {
            page = billingSummaryRepo.findAll(pageable);
        }

        return ResponseEntity.ok(page.map(CustomerBillingSummaryProjectionDTO::from));
    }

    // ── Merchant daily KPIs ──────────────────────────────────────────────────

    /**
     * GET /api/v2/admin/projections/merchant-kpis/daily
     *
     * <p>Returns daily KPI rows for one or all merchants over an optional date window.
     */
    @Operation(summary = "List merchant daily KPI projections",
               description = "Pre-computed daily KPI counters per merchant.")
    @GetMapping("/merchant-kpis/daily")
    public ResponseEntity<Page<MerchantDailyKpiProjectionDTO>> getMerchantDailyKpis(

            @Parameter(description = "Filter by merchant ID")
            @RequestParam(required = false) Long merchantId,

            @Parameter(description = "Window start date (inclusive, ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Window end date (inclusive, ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @PageableDefault(size = 50, sort = "businessDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<MerchantDailyKpiProjection> page;
        if (merchantId != null && from != null && to != null) {
            page = kpiRepo.findByMerchantIdAndBusinessDateBetween(merchantId, from, to, pageable);
        } else if (merchantId != null) {
            page = kpiRepo.findByMerchantId(merchantId, pageable);
        } else if (from != null && to != null) {
            page = kpiRepo.findByBusinessDateBetween(from, to, pageable);
        } else {
            page = kpiRepo.findAll(pageable);
        }

        return ResponseEntity.ok(page.map(MerchantDailyKpiProjectionDTO::from));
    }

    // ── Subscription status projection ───────────────────────────────────────

    @Operation(summary = "List subscription status projections",
               description = "Denormalized subscription state with unpaid invoice count, dunning state and last payment status.")
    @GetMapping("/subscriptions")
    public ResponseEntity<Page<SubscriptionStatusProjectionDTO>> getSubscriptionStatuses(

            @Parameter(description = "Filter by merchant ID") @RequestParam(required = false) Long merchantId,
            @Parameter(description = "Filter by status (e.g. ACTIVE, PAST_DUE)") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by customer ID (requires merchantId)") @RequestParam(required = false) Long customerId,

            @PageableDefault(size = 50, sort = "subscriptionId", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<SubscriptionStatusProjection> page;
        if (merchantId != null && customerId != null) {
            page = subStatusRepo.findByMerchantIdAndCustomerId(merchantId, customerId, pageable);
        } else if (merchantId != null && status != null) {
            page = subStatusRepo.findByMerchantIdAndStatus(merchantId, status, pageable);
        } else if (merchantId != null) {
            page = subStatusRepo.findByMerchantId(merchantId, pageable);
        } else {
            page = subStatusRepo.findAll(pageable);
        }
        return ResponseEntity.ok(page.map(SubscriptionStatusProjectionDTO::from));
    }

    // ── Invoice summary projection ────────────────────────────────────────────

    @Operation(summary = "List invoice summary projections",
               description = "Pre-computed invoice totals, paid_at timestamp and overdue flag.")
    @GetMapping("/invoices")
    public ResponseEntity<Page<InvoiceSummaryProjectionDTO>> getInvoiceSummaries(

            @Parameter(description = "Filter by merchant ID") @RequestParam(required = false) Long merchantId,
            @Parameter(description = "Filter by status (e.g. OPEN, PAID)") @RequestParam(required = false) String status,
            @Parameter(description = "Return only overdue invoices") @RequestParam(required = false) Boolean overdueOnly,
            @Parameter(description = "Filter by customer ID (requires merchantId)") @RequestParam(required = false) Long customerId,

            @PageableDefault(size = 50, sort = "invoiceId", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<InvoiceSummaryProjection> page;
        if (Boolean.TRUE.equals(overdueOnly) && merchantId != null) {
            page = invoiceSummaryRepo.findByMerchantIdAndOverdueFlagTrue(merchantId, pageable);
        } else if (merchantId != null && customerId != null) {
            page = invoiceSummaryRepo.findByMerchantIdAndCustomerId(merchantId, customerId, pageable);
        } else if (merchantId != null && status != null) {
            page = invoiceSummaryRepo.findByMerchantIdAndStatus(merchantId, status, pageable);
        } else if (merchantId != null) {
            page = invoiceSummaryRepo.findByMerchantId(merchantId, pageable);
        } else {
            page = invoiceSummaryRepo.findAll(pageable);
        }
        return ResponseEntity.ok(page.map(InvoiceSummaryProjectionDTO::from));
    }

    // ── Payment summary projection ────────────────────────────────────────────

    @Operation(summary = "List payment summary projections",
               description = "Pre-computed per-payment-intent summary with attempt count, gateway, refund/dispute totals.")
    @GetMapping("/payments")
    public ResponseEntity<Page<PaymentSummaryProjectionDTO>> getPaymentSummaries(

            @Parameter(description = "Filter by merchant ID") @RequestParam(required = false) Long merchantId,
            @Parameter(description = "Filter by status (e.g. SUCCEEDED, FAILED)") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by customer ID (requires merchantId)") @RequestParam(required = false) Long customerId,

            @PageableDefault(size = 50, sort = "paymentIntentId", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<PaymentSummaryProjection> page;
        if (merchantId != null && customerId != null) {
            page = paymentSummaryRepo.findByMerchantIdAndCustomerId(merchantId, customerId, pageable);
        } else if (merchantId != null && status != null) {
            page = paymentSummaryRepo.findByMerchantIdAndStatus(merchantId, status, pageable);
        } else if (merchantId != null) {
            page = paymentSummaryRepo.findByMerchantId(merchantId, pageable);
        } else {
            page = paymentSummaryRepo.findAll(pageable);
        }
        return ResponseEntity.ok(page.map(PaymentSummaryProjectionDTO::from));
    }

    // ── Recon dashboard projection ────────────────────────────────────────────

    @Operation(summary = "List recon dashboard projections",
               description = "Aggregated layer-2/3/4 open mismatch counts and unresolved amounts per business date.")
    @GetMapping("/recon-dashboard")
    public ResponseEntity<Page<ReconDashboardProjectionDTO>> getReconDashboard(

            @Parameter(description = "Filter by merchant ID (omit for platform aggregate)") @RequestParam(required = false) Long merchantId,
            @Parameter(description = "Window start date (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Window end date (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @PageableDefault(size = 50, sort = "businessDate", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<ReconDashboardProjection> page;
        if (from != null && to != null) {
            page = reconDashboardRepo.findByBusinessDateBetween(from, to, pageable);
        } else {
            page = reconDashboardRepo.findWithFilters(merchantId, pageable);
        }
        return ResponseEntity.ok(page.map(ReconDashboardProjectionDTO::from));
    }

    // ── Rebuild ──────────────────────────────────────────────────────────────

    /**
     * POST /api/v2/admin/projections/rebuild/{projectionName}
     *
     * <p>Deletes and re-derives the named projection by replaying all domain events
     * or scanning source tables directly. Supported values are listed in
     * {@link ProjectionRebuildService#SUPPORTED_PROJECTIONS}.
     *
     * <p><strong>Warning:</strong> this is a destructive, synchronous operation.
     * All existing rows in the target table are deleted before rebuilding.
     */
    @Operation(summary = "Rebuild a projection from the event log",
               description = "Destructive: deletes all rows and replays all events. Supported: customer_billing_summary, merchant_daily_kpi, subscription_status, invoice_summary, payment_summary, recon_dashboard.")
    @PostMapping("/rebuild/{projectionName}")
    public ResponseEntity<RebuildResponseDTO> rebuild(
            @Parameter(description = "Name of the projection to rebuild")
            @PathVariable String projectionName) {

        if (!ProjectionRebuildService.SUPPORTED_PROJECTIONS.contains(projectionName)) {
            throw new MembershipException(
                    "Unsupported projection: '" + projectionName
                    + "'. Supported values: " + ProjectionRebuildService.SUPPORTED_PROJECTIONS,
                    "UNSUPPORTED_PROJECTION"
            );
        }

        RebuildResponseDTO result = switch (projectionName) {
            case "customer_billing_summary" ->
                    projectionRebuildService.rebuildCustomerBillingSummaryProjection();
            case "merchant_daily_kpi" ->
                    projectionRebuildService.rebuildMerchantDailyKpiProjection();
            case "subscription_status" ->
                    projectionRebuildService.rebuildSubscriptionStatusProjection();
            case "invoice_summary" ->
                    projectionRebuildService.rebuildInvoiceSummaryProjection();
            case "payment_summary" ->
                    projectionRebuildService.rebuildPaymentSummaryProjection();
            case "recon_dashboard" ->
                    projectionRebuildService.rebuildReconDashboardProjection();
            case "customer_payment_summary" ->
                    projectionRebuildService.rebuildCustomerPaymentSummaryProjection();
            case "ledger_balance" ->
                    projectionRebuildService.rebuildLedgerBalanceProjection();
            case "merchant_revenue" ->
                    projectionRebuildService.rebuildMerchantRevenueProjection();
            default -> throw new MembershipException(
                    "Unsupported projection: " + projectionName, "UNSUPPORTED_PROJECTION");
        };

        return ResponseEntity.ok(result);
    }

    // ── Lag monitoring (Phase 19) ─────────────────────────────────────────────

    @Operation(summary = "Get staleness lag for all projections",
               description = "Returns the oldest updated_at and lag in seconds for each known projection table.")
    @GetMapping("/lag")
    public ResponseEntity<Map<String, ProjectionLagReport>> getAllLag() {
        return ResponseEntity.ok(lagMonitor.checkAllProjections());
    }

    @Operation(summary = "Get staleness lag for a single projection",
               description = "Returns lag info for the named projection.")
    @GetMapping("/lag/{projectionName}")
    public ResponseEntity<ProjectionLagReport> getLag(
            @Parameter(description = "Projection name") @PathVariable String projectionName) {
        return ResponseEntity.ok(lagMonitor.getLag(projectionName));
    }

    // ── Consistency checks (Phase 19) ─────────────────────────────────────────

    @Operation(summary = "Run consistency check for customer_payment_summary",
               description = "Compares successfulPayments counter in projection vs live PaymentIntentV2 count.")
    @GetMapping("/consistency/customer-payment-summary")
    public ResponseEntity<ConsistencyReport> checkCustomerPaymentSummary(
            @Parameter(description = "Merchant ID") @RequestParam Long merchantId,
            @Parameter(description = "Customer ID") @RequestParam Long customerId) {
        return ResponseEntity.ok(consistencyChecker.checkCustomerPaymentSummary(merchantId, customerId));
    }

    @Operation(summary = "Run consistency check for merchant_revenue",
               description = "Compares activeSubscriptions counter in projection vs live SubscriptionV2 count.")
    @GetMapping("/consistency/merchant-revenue")
    public ResponseEntity<ConsistencyReport> checkMerchantRevenue(
            @Parameter(description = "Merchant ID") @RequestParam Long merchantId) {
        return ResponseEntity.ok(consistencyChecker.checkMerchantRevenue(merchantId));
    }
}
