package com.firstclub.platform.context;

import java.util.Optional;

/**
 * Immutable per-request identity and tracing context.
 *
 * <p>Populated from HTTP headers by {@link RequestContextFilter} at the
 * outermost layer of the request lifecycle and stored in a thread-local via
 * {@link RequestContextHolder} for the duration of the request.
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@code requestId} — globally unique ID for this HTTP request.
 *       Set by client as {@code X-Request-Id}, or generated at the edge if
 *       absent. Echoed back as a response header.</li>
 *   <li>{@code correlationId} — logical correlation ID linking multiple
 *       requests within a single user flow (e.g. a checkout session).
 *       Defaults to {@code requestId} if not provided.</li>
 *   <li>{@code merchantId} — tenant ID, populated by the security filter
 *       chain after JWT/API-key authentication. Null for platform-admin
 *       requests.</li>
 *   <li>{@code actorId} — authenticated user or service identity. Populated
 *       by the security layer post-authentication.</li>
 *   <li>{@code apiVersion} — string from {@code X-API-Version} header, used
 *       by {@link com.firstclub.platform.version.ApiVersionContext} to select
 *       behaviour variants.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // In a service method:
 *   RequestContextHolder.current().ifPresent(ctx ->
 *       log.info("Processing for merchant={} request={}", ctx.getMerchantId(), ctx.getRequestId()));
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * Immutable. Safe to read from multiple threads if the same instance is
 * shared, but note that {@link RequestContextHolder} is thread-local —
 * async contexts must re-bind the context if they switch threads.
 */
public final class RequestContext {

    private final String requestId;
    private final String correlationId;
    private final Long   merchantId;
    private final String actorId;
    private final String apiVersion;

    private RequestContext(Builder builder) {
        this.requestId     = builder.requestId;
        this.correlationId = builder.correlationId;
        this.merchantId    = builder.merchantId;
        this.actorId       = builder.actorId;
        this.apiVersion    = builder.apiVersion;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Unique ID for this HTTP request. Never null after filter binding. */
    public String getRequestId()     { return requestId; }

    /** Logical correlation ID. Never null after filter binding. */
    public String getCorrelationId() { return correlationId; }

    /** Tenant merchant ID. Present only in merchant-scoped requests. */
    public Long   getMerchantId()    { return merchantId; }

    /** Authenticated actor (user id or service name). May be null for unauthenticated requests. */
    public String getActorId()       { return actorId; }

    /** Raw API version string from {@code X-API-Version} header. */
    public String getApiVersion()    { return apiVersion; }

    public Optional<Long>   merchantIdOpt()  { return Optional.ofNullable(merchantId); }
    public Optional<String> actorIdOpt()     { return Optional.ofNullable(actorId); }
    public Optional<String> apiVersionOpt()  { return Optional.ofNullable(apiVersion); }

    /**
     * Returns a copy of this context with the merchantId and actorId
     * populated from the security layer (called after JWT authentication).
     */
    public RequestContext withSecurityContext(Long merchantId, String actorId) {
        return new Builder()
                .requestId(this.requestId)
                .correlationId(this.correlationId)
                .merchantId(merchantId)
                .actorId(actorId)
                .apiVersion(this.apiVersion)
                .build();
    }

    @Override
    public String toString() {
        return "RequestContext{requestId=" + requestId
                + ", correlationId=" + correlationId
                + ", merchantId=" + merchantId
                + ", actorId=" + actorId
                + ", apiVersion=" + apiVersion + "}";
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String requestId;
        private String correlationId;
        private Long   merchantId;
        private String actorId;
        private String apiVersion;

        public Builder requestId(String requestId)         { this.requestId = requestId; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder merchantId(Long merchantId)         { this.merchantId = merchantId; return this; }
        public Builder actorId(String actorId)             { this.actorId = actorId; return this; }
        public Builder apiVersion(String apiVersion)       { this.apiVersion = apiVersion; return this; }

        public RequestContext build() {
            return new RequestContext(this);
        }
    }
}
