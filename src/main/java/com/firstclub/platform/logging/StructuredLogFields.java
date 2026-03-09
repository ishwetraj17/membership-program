package com.firstclub.platform.logging;

/**
 * Canonical MDC key names used across the entire platform.
 *
 * <h3>Naming convention</h3>
 * All keys use camelCase to be consistent with the existing
 * {@code requestId} / {@code correlationId} naming established by
 * {@link com.firstclub.platform.context.RequestContextFilter}.
 *
 * <h3>Lifecycle</h3>
 * Keys are populated by the dedicated filters in
 * {@link com.firstclub.platform.web} at request start and removed in
 * each filter's {@code finally} block to prevent thread-local leaks.
 *
 * <h3>Business-entity keys</h3>
 * Domain-level keys ({@code merchantId}, {@code subscriptionId}, etc.) are
 * populated by service code via {@link MdcUtil#set(String, Object)} and
 * removed after the operation completes.  They are NOT set by the filter
 * chain because they are not available from HTTP headers.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   MdcUtil.set(StructuredLogFields.MERCHANT_ID, merchantId);
 *   try {
 *       processPayment(merchantId, ...);
 *   } finally {
 *       MdcUtil.remove(StructuredLogFields.MERCHANT_ID);
 *   }
 * }</pre>
 */
public final class StructuredLogFields {

    private StructuredLogFields() { /* constants-only */ }

    // ── Tracing fields (set by web filters) ──────────────────────────────────

    /** Unique ID for this HTTP request. Set by {@link com.firstclub.platform.web.RequestIdFilter}. */
    public static final String REQUEST_ID     = "requestId";

    /** Logical correlation ID linking a business flow. Set by {@link com.firstclub.platform.web.CorrelationIdFilter}. */
    public static final String CORRELATION_ID = "correlationId";

    /** Client-declared API version string. Set by {@link com.firstclub.platform.web.ApiVersionFilter}. */
    public static final String API_VERSION    = "apiVersion";

    // ── Business-entity fields (set by service/security code) ────────────────

    /** Authenticated merchant tenant ID. Set by the security layer after API-key / JWT validation. */
    public static final String MERCHANT_ID    = "merchantId";

    /** Authenticated actor: service account name or user ID string. */
    public static final String ACTOR_ID       = "actorId";

    /** Customer entity ID for the current operation. */
    public static final String CUSTOMER_ID    = "customerId";

    /** Subscription entity ID for the current operation. */
    public static final String SUBSCRIPTION_ID = "subscriptionId";

    /** Payment intent entity ID for the current operation. */
    public static final String PAYMENT_INTENT_ID = "paymentIntentId";

    /** Invoice entity ID for the current operation. */
    public static final String INVOICE_ID     = "invoiceId";

    /** Domain event ID for the current operation. */
    public static final String EVENT_ID       = "eventId";

    // ── HTTP access log fields (set by RequestLoggingFilter) ─────────────────

    /** HTTP method of the incoming request (GET, POST, etc.). */
    public static final String HTTP_METHOD    = "method";

    /** URL path of the incoming request. */
    public static final String HTTP_PATH      = "path";

    /** HTTP response status code. */
    public static final String HTTP_STATUS    = "status";

    /** Total request duration in milliseconds. */
    public static final String DURATION_MS    = "durationMs";
}
