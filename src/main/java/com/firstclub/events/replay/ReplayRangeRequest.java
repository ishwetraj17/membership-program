package com.firstclub.events.replay;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Request body for {@code POST /api/v2/admin/events/replay-range}.
 *
 * <p>Only {@link #from} and {@link #to} are required; all other fields are
 * optional narrow filters.  Filtering is applied server-side via
 * {@link com.firstclub.events.repository.DomainEventRepository#findWithFilters}.
 *
 * @param from        window start (inclusive)
 * @param to          window end (inclusive)
 * @param eventType   optional event-type filter (e.g. {@code "INVOICE_CREATED"})
 * @param merchantId  optional merchant-scoped filter
 */
public record ReplayRangeRequest(
        @NotNull LocalDateTime from,
        @NotNull LocalDateTime to,
        String eventType,
        Long merchantId) {}
