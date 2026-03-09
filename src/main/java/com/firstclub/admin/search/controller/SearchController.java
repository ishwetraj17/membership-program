package com.firstclub.admin.search.controller;

import com.firstclub.admin.search.SearchResultDTO;
import com.firstclub.admin.search.SearchService;
import com.firstclub.admin.search.cache.SearchCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Unified admin search API — lets support and ops locate any entity by a
 * single identifier without knowing which table to look in.
 *
 * <p>All endpoints are secured with {@code ADMIN} role.
 * Results are always tenant-scoped: {@code merchantId} is required on every
 * request to prevent cross-merchant data leakage.
 *
 * <h3>Supported search dimensions</h3>
 * <ul>
 *   <li>Invoice number (e.g. {@code INV-2024-000001})</li>
 *   <li>Gateway transaction ID or refund reference</li>
 *   <li>Correlation ID — traces a single user action across entity boundaries</li>
 *   <li>Customer email address</li>
 *   <li>Subscription ID, payment-intent ID, event ID (numeric)</li>
 * </ul>
 *
 * <p>Base path: {@code /api/v2/admin/search}
 */
@RestController
@RequestMapping("/api/v2/admin/search")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Search (V2)", description = "Unified cross-entity search for support and ops investigation")
public class SearchController {

    private final SearchService      searchService;
    private final SearchCacheService cacheService;

    // ── Aggregated generic search ─────────────────────────────────────────

    /**
     * GET /api/v2/admin/search?q=...&merchantId=...
     *
     * <p>Aggregated search across all entity dimensions.  The service
     * auto-detects the query type (email, numeric ID, invoice number,
     * gateway ref, correlation ID) and fans out to all relevant tables.
     *
     * <p>Results are cached for {@value SearchCacheService#TTL_SECONDS} seconds.
     */
    @Operation(
            summary     = "Unified entity search",
            description = "Search across invoices, payments, refunds, customers, subscriptions, "
                        + "and domain events simultaneously. Auto-detects query type. "
                        + "All results are scoped to the supplied merchantId."
    )
    @GetMapping
    public ResponseEntity<List<SearchResultDTO>> search(
            @Parameter(description = "Owning merchant (required for tenant isolation)", required = true)
            @RequestParam Long merchantId,
            @Parameter(description = "Search term: invoice number, gateway ref, email, numeric entity ID, or correlation ID", required = true)
            @RequestParam String q) {

        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return cacheService.get(merchantId, "q", q)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    List<SearchResultDTO> results = searchService.search(merchantId, q);
                    cacheService.put(merchantId, "q", q, results);
                    return ResponseEntity.ok(results);
                });
    }

    // ── Dimension-specific endpoints ──────────────────────────────────────

    /**
     * GET /api/v2/admin/search/by-correlation/{id}?merchantId=...
     *
     * <p>Retrieves all domain events sharing a correlation ID for the given merchant.
     * Use this to trace a complete causal chain (e.g. a single checkout action
     * that triggered subscription creation, invoice generation, and payment).
     */
    @Operation(
            summary     = "Search domain events by correlation ID",
            description = "Returns all DomainEvents that share a correlation ID for the given merchant, "
                        + "ordered chronologically. Essential for incident root-cause analysis."
    )
    @GetMapping("/by-correlation/{id}")
    public ResponseEntity<List<SearchResultDTO>> byCorrelationId(
            @Parameter(description = "Owning merchant (required for tenant isolation)", required = true)
            @RequestParam Long merchantId,
            @Parameter(description = "Correlation ID to trace", required = true)
            @PathVariable String id) {

        return cacheService.get(merchantId, "correlation", id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    List<SearchResultDTO> results = searchService.searchByCorrelationId(merchantId, id);
                    cacheService.put(merchantId, "correlation", id, results);
                    return ResponseEntity.ok(results);
                });
    }

    /**
     * GET /api/v2/admin/search/by-invoice-number/{invoiceNumber}?merchantId=...
     *
     * <p>Exact-match lookup of an invoice by its human-readable invoice number.
     */
    @Operation(
            summary     = "Search by invoice number",
            description = "Exact-match lookup of an invoice by its human-readable invoice number "
                        + "(e.g. INV-2024-000001). The merchantId predicate prevents cross-tenant exposure."
    )
    @GetMapping("/by-invoice-number/{invoiceNumber}")
    public ResponseEntity<List<SearchResultDTO>> byInvoiceNumber(
            @Parameter(description = "Owning merchant (required for tenant isolation)", required = true)
            @RequestParam Long merchantId,
            @Parameter(description = "Exact invoice number", required = true)
            @PathVariable String invoiceNumber) {

        return cacheService.get(merchantId, "invoice", invoiceNumber)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    List<SearchResultDTO> results = searchService.searchByInvoiceNumber(merchantId, invoiceNumber);
                    cacheService.put(merchantId, "invoice", invoiceNumber, results);
                    return ResponseEntity.ok(results);
                });
    }

    /**
     * GET /api/v2/admin/search/by-gateway-ref/{reference}?merchantId=...
     *
     * <p>Searches both the {@code payments} table (by {@code gateway_txn_id}) and the
     * {@code refunds_v2} table (by {@code refund_reference}) for the given reference string.
     * Returns up to two results if both a payment and a refund match.
     */
    @Operation(
            summary     = "Search by gateway reference",
            description = "Looks up a payment by gateway transaction ID "
                        + "and/or a refund by gateway-assigned refund reference. "
                        + "Returns all matching rows scoped to the given merchant."
    )
    @GetMapping("/by-gateway-ref/{reference}")
    public ResponseEntity<List<SearchResultDTO>> byGatewayRef(
            @Parameter(description = "Owning merchant (required for tenant isolation)", required = true)
            @RequestParam Long merchantId,
            @Parameter(description = "Gateway transaction ID or refund reference", required = true)
            @PathVariable String reference) {

        return cacheService.get(merchantId, "gateway", reference)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    List<SearchResultDTO> results = searchService.searchByGatewayRef(merchantId, reference);
                    cacheService.put(merchantId, "gateway", reference, results);
                    return ResponseEntity.ok(results);
                });
    }
}
