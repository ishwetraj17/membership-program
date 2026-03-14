package com.firstclub.events.controller;

import com.firstclub.events.dto.EventListResponseDTO;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
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

import java.time.LocalDateTime;

/**
 * Admin endpoint for querying the domain event log with rich filters.
 *
 * <p>All operations require ADMIN role.
 * Base path: {@code /api/v2/admin/events}
 */
@RestController
@RequestMapping("/api/v2/admin/events")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Event Admin (V2)", description = "Query the domain event log with merchant and aggregate filters")
public class EventAdminController {

    private final DomainEventRepository domainEventRepository;

    /**
     * GET /api/v2/admin/events
     *
     * <p>All filters are optional and can be combined freely.
     * Results are ordered by {@code createdAt DESC} by default.
     */
    @Operation(summary = "List domain events with optional filters")
    @GetMapping
    public ResponseEntity<Page<EventListResponseDTO>> list(
            @Parameter(description = "Filter by merchant ID")
            @RequestParam(required = false) Long merchantId,

            @Parameter(description = "Filter by exact event type, e.g. INVOICE_CREATED")
            @RequestParam(required = false) String eventType,

            @Parameter(description = "Filter by aggregate type, e.g. Subscription")
            @RequestParam(required = false) String aggregateType,

            @Parameter(description = "Filter by aggregate ID")
            @RequestParam(required = false) String aggregateId,

            @Parameter(description = "Window start (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,

            @Parameter(description = "Window end (ISO-8601)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,

            @PageableDefault(size = 50, sort = "created_at", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<DomainEvent> page = domainEventRepository.findWithFilters(
                merchantId, eventType, aggregateType, aggregateId, from, to, pageable);

        return ResponseEntity.ok(page.map(EventListResponseDTO::from));
    }
}
