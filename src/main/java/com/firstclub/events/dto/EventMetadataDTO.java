package com.firstclub.events.dto;

import lombok.*;

/**
 * Metadata attached to every versioned domain event (V29+).
 * Used both as a parameter when recording events and as part of query responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventMetadataDTO {

    /** Monotonically increasing version for this event type. Default: 1. */
    @Builder.Default
    private int eventVersion = 1;

    /** Version of the JSON schema used for the payload. Default: 1. */
    @Builder.Default
    private int schemaVersion = 1;

    /**
     * Distributed trace / request correlation ID.
     * Typically the MDC {@code requestId} or an X-Correlation-Id header value.
     */
    private String correlationId;

    /** ID of the causally preceding event (for event chains). */
    private String causationId;

    /** DDD aggregate type label, e.g. "Subscription", "Invoice", "Payment". */
    private String aggregateType;

    /** Aggregate primary-key as a string. */
    private String aggregateId;

    /** Owning merchant — used for tenant-scoped replay and queries. */
    private Long merchantId;
}
