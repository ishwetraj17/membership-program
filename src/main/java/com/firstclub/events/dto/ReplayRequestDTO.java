package com.firstclub.events.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * Request body / parameter set for replay and projection rebuild operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayRequestDTO {

    /** Replay window start (inclusive). */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    /** Replay window end (inclusive). */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;

    /**
     * Optional: restrict replay to a single merchant.
     * When null, all merchants are included.
     */
    private Long merchantId;

    /**
     * Optional: restrict replay to a single aggregate type, e.g. "Subscription".
     */
    private String aggregateType;

    /**
     * Optional: restrict replay to a specific aggregate id (requires aggregateType).
     */
    private String aggregateId;

    /**
     * Replay mode.
     * <br>{@code VALIDATE_ONLY} — inspect events without mutating state.
     * <br>{@code REBUILD_PROJECTION} — rebuild a named read-model projection.
     */
    @Builder.Default
    private String mode = "VALIDATE_ONLY";

    /**
     * For REBUILD_PROJECTION mode: the name of the projection to rebuild.
     * Supported values: {@code subscription_summary}, {@code invoice_ledger}.
     */
    private String projectionName;
}
