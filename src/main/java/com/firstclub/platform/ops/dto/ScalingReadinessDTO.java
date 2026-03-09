package com.firstclub.platform.ops.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Honest, descriptive report of the system's current architectural shape and
 * the documented multi-stage evolution path toward extreme scale.
 *
 * <p>This response reflects deployment-time architectural reality, not runtime
 * state. The values are generated from static configuration within the service
 * and should be updated when the architecture changes, not dynamically at
 * request time.
 *
 * <h3>Architecture shape values</h3>
 * <ul>
 *   <li>{@code MODULAR_MONOLITH} — current state: single deployable unit with
 *       logically separated packages and a shared PostgreSQL database.</li>
 * </ul>
 *
 * <h3>Evolution stages</h3>
 * Stage keys follow the pattern {@code stage_N} where N is 1–6.
 * Stage 1 is the current state; stage 6 is the long-term target.
 */
public record ScalingReadinessDTO(
        String              architectureShape,
        List<String>        currentBottlenecks,
        List<String>        projectionBackedSubsystems,
        List<String>        redisBackedSubsystems,
        List<String>        singleNodeRisks,
        List<String>        decompositionCandidates,
        Map<String, String> evolutionStages,
        LocalDateTime       generatedAt
) {}
