package com.firstclub.reporting.projections.dto;

import lombok.*;

import java.time.LocalDateTime;

/** Returned by {@code ProjectionRebuildService} after a projection rebuild. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RebuildResponseDTO {

    /** The projection that was rebuilt. */
    private String projectionName;

    /** Number of domain events scanned during rebuild. */
    private int eventsProcessed;

    /** Number of projection rows after rebuild. */
    private long recordsInProjection;

    /** When the rebuild completed. */
    private LocalDateTime rebuiltAt;
}
