package com.firstclub.events.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Enriched replay result returned by the ReplayService (V29).
 * Extends the simple {@link ReplayReportDTO} with per-type event counts,
 * aggregate filter echoes, and merchant scoping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayResponseDTO {

    // ── Request echo ─────────────────────────────────────────────────────────
    private LocalDateTime from;
    private LocalDateTime to;
    private String        mode;
    private Long          merchantId;
    private String        aggregateType;
    private String        aggregateId;

    // ── Summary ───────────────────────────────────────────────────────────────
    private int     eventsScanned;
    private boolean valid;

    /** Findings / violations discovered (empty when valid = true). */
    private List<String> findings;

    /** Event counts broken down by event type. */
    private Map<String, Long> countByType;

    /** Name of projection rebuilt (non-null only when mode = REBUILD_PROJECTION). */
    private String projectionRebuilt;
}
