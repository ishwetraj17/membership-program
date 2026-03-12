package com.firstclub.platform.observability;

import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Factory for building consistent Micrometer {@link Tags} across the platform.
 *
 * <p>Use this bean wherever metrics are emitted to ensure uniform tag naming
 * (snake_case keys to match Prometheus label conventions) and avoid magic
 * string duplication in service code.
 *
 * <h3>Tag naming conventions</h3>
 * <pre>
 *   merchant_id  — the owning merchant (long as string; "system" for platform-initiated ops)
 *   outcome      — high-level result: "success", "failure", "unknown"
 *   gateway      — payment gateway identifier: "stripe", "razorpay", "unknown"
 *   currency     — ISO 4217 code: "INR", "USD"
 *   event_type   — outbox / domain event type name
 * </pre>
 */
@Component
public class MetricsTagFactory {

    /** Tag for the owning merchant; falls back to {@code "system"} for non-merchant operations. */
    public Tags merchantTag(Long merchantId) {
        return Tags.of("merchant_id", merchantId == null ? "system" : String.valueOf(merchantId));
    }

    /** Tag capturing the high-level outcome of an operation. */
    public Tags outcomeTag(String outcome) {
        return Tags.of("outcome", outcome == null ? "unknown" : outcome.toLowerCase());
    }

    /** Tag capturing which payment gateway handled the request. */
    public Tags gatewayTag(String gateway) {
        return Tags.of("gateway", gateway == null ? "unknown" : gateway.toLowerCase());
    }

    /** Tag capturing the ISO 4217 currency code (uppercased for consistency). */
    public Tags currencyTag(String currency) {
        return Tags.of("currency", currency == null ? "unknown" : currency.toUpperCase());
    }

    /** Tag capturing the outbox or domain event type name. */
    public Tags eventTypeTag(String eventType) {
        return Tags.of("event_type", eventType == null ? "unknown" : eventType);
    }

    /** Combined {@code merchant_id + outcome} tags — the most common combination for payment metrics. */
    public Tags merchantAndOutcome(Long merchantId, String outcome) {
        return Tags.of(
                "merchant_id", merchantId == null ? "system" : String.valueOf(merchantId),
                "outcome", outcome == null ? "unknown" : outcome.toLowerCase());
    }

    /** Combined {@code merchant_id + currency} tags — for financial value metrics. */
    public Tags merchantAndCurrency(Long merchantId, String currency) {
        return Tags.of(
                "merchant_id", merchantId == null ? "system" : String.valueOf(merchantId),
                "currency", currency == null ? "unknown" : currency.toUpperCase());
    }

    /** Combined {@code merchant_id + gateway + outcome} tags — for payment attempt metrics. */
    public Tags paymentAttemptTags(Long merchantId, String gateway, String outcome) {
        return Tags.of(
                "merchant_id", merchantId == null ? "system" : String.valueOf(merchantId),
                "gateway", gateway == null ? "unknown" : gateway.toLowerCase(),
                "outcome", outcome == null ? "unknown" : outcome.toLowerCase());
    }
}
