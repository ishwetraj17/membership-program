package com.firstclub.payments.disputes.controller;

import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.service.DisputeDueDateCheckerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase 15 admin API — dispute evidence deadline visibility.
 *
 * <p>Gives operations / risk teams a single endpoint to enumerate all disputes
 * approaching their evidence submission deadline so that submissions are never
 * missed through oversight.
 */
@RestController
@RequestMapping("/api/v2/admin/disputes")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Dispute Admin", description = "Admin APIs for dispute deadline management (Phase 15)")
public class DisputeAdminController {

    /** Default look-ahead window applied when the caller omits the query parameter. */
    private static final int DEFAULT_WITHIN_DAYS = 7;

    private final DisputeDueDateCheckerService dueDateCheckerService;

    @GetMapping("/due-soon")
    @Operation(
        summary = "List disputes with evidence deadlines approaching soon",
        description = "Returns all OPEN or UNDER_REVIEW disputes whose dueBy date falls within the " +
                      "specified number of days from now, ordered by dueBy ascending (most urgent first). " +
                      "Defaults to 7 days when withinDays is not supplied.")
    @ApiResponse(responseCode = "200", description = "List of near-deadline disputes (may be empty)")
    public ResponseEntity<List<DisputeResponseDTO>> getDueSoon(
            @Parameter(description = "Look-ahead window in days (default 7)")
            @RequestParam(defaultValue = "7") int withinDays) {

        log.debug("GET /api/v2/admin/disputes/due-soon withinDays={}", withinDays);
        return ResponseEntity.ok(dueDateCheckerService.findDueSoon(withinDays));
    }
}
