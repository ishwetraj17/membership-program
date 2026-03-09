package com.firstclub.events.controller;

import com.firstclub.events.replay.EventReplayService;
import com.firstclub.events.replay.ReplayRangeRequest;
import com.firstclub.events.replay.ReplayResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for schema-versioned, idempotency-guarded replay of domain events.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 *
 * <h3>Replay safety</h3>
 * Replay decisions are delegated to
 * {@link com.firstclub.events.replay.ReplaySafetyService}.  Event types registered
 * with {@code BLOCKED} policy (e.g. {@code REFUND_ISSUED}) are never re-dispatched.
 * Event types with {@code IDEMPOTENT_ONLY} policy may only be replayed once per
 * original event.
 *
 * <h3>Schema migration</h3>
 * When the stored {@code schema_version} of an event lags behind the current
 * version, the payload is automatically migrated through the registered
 * {@link com.firstclub.events.schema.PayloadMigrator} chain before the replay
 * event is persisted.
 */
@RestController
@RequestMapping("/api/v2/admin/events")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Event Schema Admin (V2)",
     description = "Schema-versioned, idempotency-guarded domain event replay")
public class EventSchemaAdminController {

    private final EventReplayService eventReplayService;

    /**
     * POST /api/v2/admin/events/{id}/replay
     *
     * <p>Replays a single domain event.  The stored payload is migrated to the
     * current schema version (if necessary) and a new replay event is persisted with
     * {@code replayed=true} and {@code original_event_id} pointing at the source.
     *
     * @param id primary key of the event to replay
     * @return replay outcome — includes the new replay event ID when successful,
     *         or a skip reason when blocked by policy
     */
    @Operation(summary = "Replay a single domain event by ID (ADMIN)")
    @PostMapping("/{id}/replay")
    public ResponseEntity<ReplayResult> replaySingle(
            @Parameter(description = "Primary key of the domain event to replay")
            @PathVariable Long id) {
        return ResponseEntity.ok(eventReplayService.replay(id));
    }

    /**
     * POST /api/v2/admin/events/replay-range
     *
     * <p>Replays all events matching the time window and optional event-type /
     * merchant filters.  Events that fail the safety check are included in the
     * response with {@code replayed=false} and a {@code skipReason}; they do not
     * cause the entire range to abort.
     *
     * @param request time window and optional narrow filters
     * @return one result per matched event
     */
    @Operation(summary = "Replay a time-bounded range of domain events (ADMIN)")
    @PostMapping("/replay-range")
    public ResponseEntity<List<ReplayResult>> replayRange(
            @Valid @RequestBody ReplayRangeRequest request) {
        return ResponseEntity.ok(eventReplayService.replayRange(request));
    }
}
