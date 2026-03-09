package com.firstclub.reporting.ops.timeline.controller;

import com.firstclub.reporting.ops.timeline.dto.TimelineEventDTO;
import com.firstclub.reporting.ops.timeline.entity.TimelineEntityTypes;
import com.firstclub.reporting.ops.timeline.service.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only API surface for the unified ops timeline.
 *
 * <p>All endpoints are secured with {@code ADMIN} role.
 * Results are sourced from {@code ops_timeline_events} (append-only)
 * with a 60-second Redis hot-cache in front.
 *
 * <p>Base path: {@code /api/v2/admin/timeline}
 *
 * <h3>Entity convenience paths</h3>
 * <ul>
 *   <li>{@code /customer/{id}}    — key subscription lifecycle events for a customer</li>
 *   <li>{@code /subscription/{id}} — full subscription event history</li>
 *   <li>{@code /invoice/{id}}     — invoice-scoped events (creation, payment, refund)</li>
 *   <li>{@code /payment/{id}}     — payment-intent event history</li>
 * </ul>
 *
 * <h3>Generic paths</h3>
 * <ul>
 *   <li>{@code /} with {@code entityType} + {@code entityId} — paginated generic query</li>
 *   <li>{@code /by-correlation/{id}} — trace a single user action across entity boundaries</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/admin/timeline")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ops Timeline (V2)", description = "Unified append-only entity timeline for support and ops investigation")
public class TimelineController {

    private final TimelineService timelineService;

    // ── Generic query ────────────────────────────────────────────────────────

    /**
     * GET /api/v2/admin/timeline
     *
     * <p>Paginated timeline for any entity type.  All three parameters are required
     * to maintain tenant isolation.
     */
    @Operation(
            summary = "Paginated timeline for any entity",
            description = "Returns timeline events for the given entity type and ID, newest first.")
    @GetMapping
    public ResponseEntity<Page<TimelineEventDTO>> getTimeline(

            @Parameter(description = "Merchant ID (required for tenant isolation)", required = true)
            @RequestParam Long merchantId,

            @Parameter(description = "Entity type: CUSTOMER, SUBSCRIPTION, INVOICE, PAYMENT_INTENT, REFUND, DISPUTE",
                       required = true)
            @RequestParam String entityType,

            @Parameter(description = "Entity ID", required = true)
            @RequestParam Long entityId,

            @PageableDefault(size = 50, sort = "eventTime", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<TimelineEventDTO> page = timelineService.getTimelineForEntityPaged(
                merchantId, entityType.toUpperCase(), entityId, pageable);
        return ResponseEntity.ok(page);
    }

    // ── Customer convenience ──────────────────────────────────────────────────

    /**
     * GET /api/v2/admin/timeline/customer/{customerId}
     *
     * <p>Returns key subscription lifecycle events for the given customer.
     * The mapper ensures that SUBSCRIPTION_CREATED, SUBSCRIPTION_ACTIVATED,
     * SUBSCRIPTION_PAST_DUE, and SUBSCRIPTION_CANCELLED emit a CUSTOMER-typed
     * row, so this query works without a subscription join.
     */
    @Operation(
            summary = "Customer timeline",
            description = "All timeline events scoped to a customer entity (subscription lifecycle etc).")
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<TimelineEventDTO>> getCustomerTimeline(

            @Parameter(description = "Merchant ID", required = true)
            @RequestParam Long merchantId,

            @Parameter(description = "Customer ID") @PathVariable Long customerId) {

        return ResponseEntity.ok(
                timelineService.getTimelineForEntity(merchantId, TimelineEntityTypes.CUSTOMER, customerId));
    }

    // ── Subscription convenience ──────────────────────────────────────────────

    /**
     * GET /api/v2/admin/timeline/subscription/{subscriptionId}
     */
    @Operation(
            summary = "Subscription timeline",
            description = "Full subscription event history including billing events.")
    @GetMapping("/subscription/{subscriptionId}")
    public ResponseEntity<List<TimelineEventDTO>> getSubscriptionTimeline(

            @Parameter(description = "Merchant ID", required = true)
            @RequestParam Long merchantId,

            @Parameter(description = "Subscription ID") @PathVariable Long subscriptionId) {

        return ResponseEntity.ok(
                timelineService.getTimelineForEntity(merchantId, TimelineEntityTypes.SUBSCRIPTION, subscriptionId));
    }

    // ── Invoice convenience ───────────────────────────────────────────────────

    /**
     * GET /api/v2/admin/timeline/invoice/{invoiceId}
     */
    @Operation(
            summary = "Invoice timeline",
            description = "Invoice-scoped events: creation, payment attempt, payment success, refund.")
    @GetMapping("/invoice/{invoiceId}")
    public ResponseEntity<List<TimelineEventDTO>> getInvoiceTimeline(

            @Parameter(description = "Merchant ID", required = true)
            @RequestParam Long merchantId,

            @Parameter(description = "Invoice ID") @PathVariable Long invoiceId) {

        return ResponseEntity.ok(
                timelineService.getTimelineForEntity(merchantId, TimelineEntityTypes.INVOICE, invoiceId));
    }

    // ── Payment convenience ───────────────────────────────────────────────────

    /**
     * GET /api/v2/admin/timeline/payment/{paymentIntentId}
     */
    @Operation(
            summary = "Payment intent timeline",
            description = "Full history of a payment intent: creation, attempts, success or failure, risk decision.")
    @GetMapping("/payment/{paymentIntentId}")
    public ResponseEntity<List<TimelineEventDTO>> getPaymentTimeline(

            @Parameter(description = "Merchant ID", required = true)
            @RequestParam Long merchantId,

            @Parameter(description = "Payment intent ID") @PathVariable Long paymentIntentId) {

        return ResponseEntity.ok(
                timelineService.getTimelineForEntity(merchantId, TimelineEntityTypes.PAYMENT_INTENT, paymentIntentId));
    }

    // ── Correlation trace ─────────────────────────────────────────────────────

    /**
     * GET /api/v2/admin/timeline/by-correlation/{correlationId}
     *
     * <p>Returns all timeline rows that share the given correlation ID, sorted
     * newest-first.  Use this to trace a single user-initiated action (e.g.
     * a checkout flow) across SUBSCRIPTION → INVOICE → PAYMENT_INTENT.
     */
    @Operation(
            summary = "Timeline by correlation ID",
            description = "Fetch all entity events that share a correlation ID to trace a single user action.")
    @GetMapping("/by-correlation/{correlationId}")
    public ResponseEntity<Page<TimelineEventDTO>> getByCorrelation(

            @Parameter(description = "Merchant ID", required = true)
            @RequestParam Long merchantId,

            @Parameter(description = "Correlation ID") @PathVariable String correlationId,

            @PageableDefault(size = 50, sort = "eventTime", direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(
                timelineService.getTimelineByCorrelation(merchantId, correlationId, pageable));
    }
}
