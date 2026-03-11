package com.firstclub.reporting.projections.controller;

import com.firstclub.reporting.projections.dto.CustomerPaymentSummaryProjectionDTO;
import com.firstclub.reporting.projections.dto.LedgerBalanceProjectionDTO;
import com.firstclub.reporting.projections.dto.MerchantRevenueProjectionDTO;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjection;
import com.firstclub.reporting.projections.entity.LedgerBalanceProjection;
import com.firstclub.reporting.projections.entity.MerchantRevenueProjection;
import com.firstclub.reporting.projections.repository.CustomerPaymentSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.LedgerBalanceProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantRevenueProjectionRepository;
import com.firstclub.reporting.projections.service.ProjectionRebuildService;
import com.firstclub.membership.exception.MembershipException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Public-facing reporting endpoints for the Phase 19 denormalized read models.
 *
 * <p>These endpoints serve pre-computed projection data for operational
 * dashboards and integrations. The data is updated asynchronously from domain
 * events and may lag by a few seconds.
 */
@RestController
@RequestMapping("/reporting")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Reporting", description = "Phase 19 denormalized read-model endpoints")
public class ReportingController {

    private final CustomerPaymentSummaryProjectionRepository customerPaymentRepo;
    private final LedgerBalanceProjectionRepository          ledgerBalanceRepo;
    private final MerchantRevenueProjectionRepository        merchantRevenueRepo;
    private final ProjectionRebuildService                   rebuildService;

    // ── Customer payment summary ─────────────────────────────────────────────

    @Operation(summary = "Get customer payment summary",
               description = "Returns the denormalized payment outcome summary for a specific customer.")
    @GetMapping("/customer-payment-summary/{customerId}")
    public ResponseEntity<CustomerPaymentSummaryProjectionDTO> getCustomerPaymentSummary(
            @Parameter(description = "Merchant scoping ID") @RequestParam Long merchantId,
            @Parameter(description = "Customer ID") @PathVariable Long customerId) {

        return customerPaymentRepo.findByMerchantIdAndCustomerId(merchantId, customerId)
                .map(CustomerPaymentSummaryProjectionDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List customer payment summaries for a merchant",
               description = "Paginated list of customer payment summaries scoped to a merchant.")
    @GetMapping("/customer-payment-summary")
    public ResponseEntity<Page<CustomerPaymentSummaryProjectionDTO>> listCustomerPaymentSummaries(
            @Parameter(description = "Merchant scoping ID") @RequestParam Long merchantId,
            @PageableDefault(size = 50) Pageable pageable) {

        return ResponseEntity.ok(
                customerPaymentRepo.findByMerchantId(merchantId, pageable)
                        .map(CustomerPaymentSummaryProjectionDTO::from));
    }

    // ── Ledger balance ────────────────────────────────────────────────────────

    @Operation(summary = "Get ledger balance for a user",
               description = "Returns the running ledger balance projection for a specific user.")
    @GetMapping("/ledger-balance/{userId}")
    public ResponseEntity<LedgerBalanceProjectionDTO> getLedgerBalance(
            @Parameter(description = "Merchant scoping ID") @RequestParam Long merchantId,
            @Parameter(description = "User ID") @PathVariable Long userId) {

        return ledgerBalanceRepo.findByMerchantIdAndUserId(merchantId, userId)
                .map(LedgerBalanceProjectionDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Merchant revenue ──────────────────────────────────────────────────────

    @Operation(summary = "Get merchant all-time revenue projection",
               description = "Returns the all-time revenue and churn counters for a specific merchant.")
    @GetMapping("/merchant-revenue/{merchantId}")
    public ResponseEntity<MerchantRevenueProjectionDTO> getMerchantRevenue(
            @Parameter(description = "Merchant ID") @PathVariable Long merchantId) {

        return merchantRevenueRepo.findById(merchantId)
                .map(MerchantRevenueProjectionDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Rebuild ───────────────────────────────────────────────────────────────

    @Operation(summary = "Rebuild a Phase 19 projection from the event log",
               description = "Destructive rebuild for customer_payment_summary, ledger_balance, or merchant_revenue.")
    @PostMapping("/projections/rebuild")
    public ResponseEntity<RebuildResponseDTO> rebuild(
            @Parameter(description = "Projection name: customer_payment_summary | ledger_balance | merchant_revenue")
            @RequestParam String projectionName) {

        RebuildResponseDTO result = switch (projectionName) {
            case "customer_payment_summary" ->
                    rebuildService.rebuildCustomerPaymentSummaryProjection();
            case "ledger_balance" ->
                    rebuildService.rebuildLedgerBalanceProjection();
            case "merchant_revenue" ->
                    rebuildService.rebuildMerchantRevenueProjection();
            default -> throw new MembershipException(
                    "Unsupported projection for /reporting rebuild: '" + projectionName
                    + "'. Use /api/v2/admin/projections/rebuild for other projections.",
                    "UNSUPPORTED_PROJECTION");
        };

        return ResponseEntity.ok(result);
    }
}
