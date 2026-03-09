package com.firstclub.admin.search;

import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified admin search service that aggregates entity lookups across all
 * domain tables for a given merchant.
 *
 * <h3>Tenant isolation</h3>
 * Every repository call includes a {@code merchantId} predicate. Results
 * are only returned if they belong to the supplied merchant, preventing
 * cross-tenant data leakage.
 *
 * <h3>Security</h3>
 * No {@code clientSecret}, encrypted PII (phone, billing/shipping address),
 * or raw event {@code payload} fields are ever included in the returned DTOs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final InvoiceRepository          invoiceRepository;
    private final PaymentRepository          paymentRepository;
    private final PaymentIntentV2Repository  paymentIntentV2Repository;
    private final RefundV2Repository         refundV2Repository;
    private final CustomerRepository         customerRepository;
    private final SubscriptionV2Repository   subscriptionV2Repository;
    private final DomainEventRepository      domainEventRepository;

    // ═══════════════════════════════════════════════════════════════════════
    // Dimension-specific lookups (used by dedicated controller endpoints)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find an invoice by its human-readable invoice number.
     * Result is tenant-scoped: only returned when it belongs to {@code merchantId}.
     */
    public List<SearchResultDTO> searchByInvoiceNumber(Long merchantId, String invoiceNumber) {
        List<SearchResultDTO> results = new ArrayList<>();
        invoiceRepository.findByInvoiceNumberAndMerchantId(invoiceNumber, merchantId)
                .ifPresent(inv -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.INVOICE)
                        .primaryId(inv.getId())
                        .merchantId(inv.getMerchantId())
                        .displayLabel(inv.getInvoiceNumber())
                        .status(inv.getStatus().name())
                        .matchedField("invoiceNumber")
                        .matchedValue(invoiceNumber)
                        .apiPath("/api/v2/admin/timeline/invoice/" + inv.getId())
                        .createdAt(inv.getCreatedAt())
                        .build()));
        return results;
    }

    /**
     * Find a payment by its gateway transaction ID.
     * Result is tenant-scoped: only returned when it belongs to {@code merchantId}.
     */
    public List<SearchResultDTO> searchByGatewayRef(Long merchantId, String gatewayRef) {
        List<SearchResultDTO> results = new ArrayList<>();

        // Payment: gateway_txn_id
        paymentRepository.findByGatewayTxnIdAndMerchantId(gatewayRef, merchantId)
                .ifPresent(p -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.PAYMENT)
                        .primaryId(p.getId())
                        .merchantId(p.getMerchantId())
                        .displayLabel("Payment #" + p.getId())
                        .status(p.getStatus().name())
                        .matchedField("gatewayTxnId")
                        .matchedValue(gatewayRef)
                        .apiPath("/api/v2/admin/timeline/payment/" + p.getId())
                        .createdAt(p.getCreatedAt())
                        .build()));

        // RefundV2: refund_reference
        refundV2Repository.findByRefundReferenceAndMerchantId(gatewayRef, merchantId)
                .ifPresent(r -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.REFUND)
                        .primaryId(r.getId())
                        .merchantId(r.getMerchantId())
                        .displayLabel("Refund #" + r.getId())
                        .status(r.getStatus().name())
                        .matchedField("refundReference")
                        .matchedValue(gatewayRef)
                        .apiPath("/api/v2/admin/timeline/refund/" + r.getId())
                        .createdAt(r.getCreatedAt())
                        .build()));

        return results;
    }

    /**
     * Find all domain events sharing a correlation ID for a merchant.
     * Returns events ordered by {@code createdAt ASC} to show the causal chain.
     */
    public List<SearchResultDTO> searchByCorrelationId(Long merchantId, String correlationId) {
        return domainEventRepository
                .findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(correlationId, merchantId)
                .stream()
                .map(e -> SearchResultDTO.builder()
                        .resultType(SearchResultType.DOMAIN_EVENT)
                        .primaryId(e.getId())
                        .merchantId(e.getMerchantId())
                        .displayLabel(e.getEventType())
                        .status(e.getEventType())
                        .matchedField("correlationId")
                        .matchedValue(correlationId)
                        .apiPath("/api/v2/admin/events?correlationId=" + correlationId)
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Find a customer by email address (case-insensitive).
     * Result is tenant-scoped: only returned when it belongs to {@code merchantId}.
     */
    public List<SearchResultDTO> searchByCustomerEmail(Long merchantId, String email) {
        List<SearchResultDTO> results = new ArrayList<>();
        customerRepository.findByMerchantIdAndEmailIgnoreCase(merchantId, email)
                .ifPresent(c -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.CUSTOMER)
                        .primaryId(c.getId())
                        .merchantId(c.getMerchant().getId())
                        .displayLabel(c.getEmail())
                        .status(c.getStatus().name())
                        .matchedField("email")
                        .matchedValue(email)
                        .apiPath("/api/v2/admin/timeline/customer/" + c.getId())
                        .createdAt(c.getCreatedAt())
                        .build()));
        return results;
    }

    /**
     * Find a subscription by its numeric ID.
     * Result is tenant-scoped: only returned when it belongs to {@code merchantId}.
     */
    public List<SearchResultDTO> searchBySubscriptionId(Long merchantId, Long subscriptionId) {
        List<SearchResultDTO> results = new ArrayList<>();
        subscriptionV2Repository.findByMerchantIdAndId(merchantId, subscriptionId)
                .ifPresent(s -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.SUBSCRIPTION)
                        .primaryId(s.getId())
                        .merchantId(s.getMerchant().getId())
                        .displayLabel("Subscription #" + s.getId())
                        .status(s.getStatus().name())
                        .matchedField("id")
                        .matchedValue(String.valueOf(subscriptionId))
                        .apiPath("/api/v2/admin/timeline/subscription/" + s.getId())
                        .createdAt(s.getCreatedAt())
                        .build()));
        return results;
    }

    /**
     * Find a payment intent by its numeric ID.
     * Result is tenant-scoped: only returned when it belongs to {@code merchantId}.
     * Never includes {@code clientSecret}.
     */
    public List<SearchResultDTO> searchByPaymentIntentId(Long merchantId, Long paymentIntentId) {
        List<SearchResultDTO> results = new ArrayList<>();
        paymentIntentV2Repository.findByMerchantIdAndId(merchantId, paymentIntentId)
                .ifPresent(pi -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.PAYMENT_INTENT)
                        .primaryId(pi.getId())
                        .merchantId(pi.getMerchant().getId())
                        .displayLabel("PaymentIntent #" + pi.getId())
                        .status(pi.getStatus().name())
                        .matchedField("id")
                        .matchedValue(String.valueOf(paymentIntentId))
                        .apiPath("/api/v2/admin/timeline/payment-intent/" + pi.getId())
                        .createdAt(pi.getCreatedAt())
                        .build()));
        return results;
    }

    /**
     * Find a single domain event by its primary key.
     * Result is tenant-scoped: only returned when it belongs to {@code merchantId}.
     */
    public List<SearchResultDTO> searchByEventId(Long merchantId, Long eventId) {
        List<SearchResultDTO> results = new ArrayList<>();
        domainEventRepository.findByIdAndMerchantId(eventId, merchantId)
                .ifPresent(e -> results.add(SearchResultDTO.builder()
                        .resultType(SearchResultType.DOMAIN_EVENT)
                        .primaryId(e.getId())
                        .merchantId(e.getMerchantId())
                        .displayLabel(e.getEventType())
                        .status(e.getEventType())
                        .matchedField("id")
                        .matchedValue(String.valueOf(eventId))
                        .apiPath("/api/v2/admin/events?aggregateId=" + e.getAggregateId())
                        .createdAt(e.getCreatedAt())
                        .build()));
        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Unified "smart" search — detects query type and fans out
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Aggregated search across all entity dimensions.
     *
     * <p>Detection rules (applied in order, non-exclusive):
     * <ol>
     *   <li>If {@code q} contains {@code @} → search customers by email.</li>
     *   <li>If {@code q} is purely numeric → try as subscriptionId,
     *       paymentIntentId, eventId (all in parallel).</li>
     *   <li>Always try as invoiceNumber, gatewayRef (covers INV-… and
     *       gateway-assigned reference strings), and correlationId.</li>
     * </ol>
     *
     * <p>All queries are executed synchronously; results are aggregated and
     * the list is returned in insertion order (no global sort applied here —
     * the controller may page or sort at its discretion).
     *
     * @param merchantId required — prevents cross-tenant leakage
     * @param q          the raw query string (validated non-blank by the controller)
     */
    public List<SearchResultDTO> search(Long merchantId, String q) {
        List<SearchResultDTO> aggregated = new ArrayList<>();

        String trimmed = q.strip();

        // 1. Email detection
        if (trimmed.contains("@")) {
            try {
                aggregated.addAll(searchByCustomerEmail(merchantId, trimmed));
            } catch (Exception ex) {
                log.warn("Search: customer email lookup failed merchant={} q={}", merchantId, trimmed, ex);
            }
        }

        // 2. Numeric ID detection
        Long numericId = tryParsePositiveLong(trimmed);
        if (numericId != null) {
            try { aggregated.addAll(searchBySubscriptionId(merchantId, numericId));  } catch (Exception ex) { log.warn("Search: subscriptionId lookup failed", ex); }
            try { aggregated.addAll(searchByPaymentIntentId(merchantId, numericId)); } catch (Exception ex) { log.warn("Search: paymentIntentId lookup failed", ex); }
            try { aggregated.addAll(searchByEventId(merchantId, numericId));         } catch (Exception ex) { log.warn("Search: eventId lookup failed", ex); }
        }

        // 3. String lookups — always attempted
        try { aggregated.addAll(searchByInvoiceNumber(merchantId, trimmed)); } catch (Exception ex) { log.warn("Search: invoiceNumber lookup failed", ex); }
        try { aggregated.addAll(searchByGatewayRef(merchantId, trimmed));    } catch (Exception ex) { log.warn("Search: gatewayRef lookup failed", ex); }
        try { aggregated.addAll(searchByCorrelationId(merchantId, trimmed)); } catch (Exception ex) { log.warn("Search: correlationId lookup failed", ex); }

        log.debug("Search: merchant={} q='{}' hits={}", merchantId, trimmed, aggregated.size());
        return aggregated;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private static Long tryParsePositiveLong(String s) {
        try {
            long val = Long.parseLong(s);
            return val > 0 ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
