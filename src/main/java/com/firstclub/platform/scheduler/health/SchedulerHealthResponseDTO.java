package com.firstclub.platform.scheduler.health;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * Response DTO for {@code GET /ops/schedulers/health} — describes the health
 * status of a single scheduler, including its last run timing and diagnosis.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerHealthResponseDTO {

    /** Logical scheduler name (e.g. {@code "subscription-renewal"}). */
    String schedulerName;

    /**
     * Health classification: {@code HEALTHY}, {@code STALE}, or {@code NEVER_RAN}.
     */
    String health;

    /** ISO-8601 timestamp of the most recent successful completion. */
    Instant lastSuccessAt;

    /** ISO-8601 timestamp of the most recent run start (any status). */
    Instant lastRunAt;

    /** Status of the most recent run (SUCCESS, FAILED, SKIPPED, RUNNING). */
    String lastRunStatus;

    /** Expected maximum interval between successful runs (ISO-8601 duration). */
    String expectedInterval;

    /** Human-readable diagnosis — only populated for STALE or NEVER_RAN. */
    String diagnosis;

    public static SchedulerHealthResponseDTO from(SchedulerHealthMonitor.SchedulerStatusSnapshot snapshot) {
        String diagnosis = buildDiagnosis(snapshot);
        return SchedulerHealthResponseDTO.builder()
                .schedulerName(snapshot.schedulerName())
                .health(snapshot.health().name())
                .lastSuccessAt(snapshot.lastSuccessAt())
                .lastRunAt(snapshot.lastRunAt())
                .lastRunStatus(snapshot.lastRunStatus())
                .expectedInterval(snapshot.expectedInterval().toString())
                .diagnosis(diagnosis)
                .build();
    }

    private static String buildDiagnosis(SchedulerHealthMonitor.SchedulerStatusSnapshot s) {
        return switch (s.health()) {
            case HEALTHY  -> null;
            case NEVER_RAN -> "No successful execution on record. Check scheduler configuration and advisory lock acquisition logs.";
            case STALE -> {
                if (s.lastSuccessAt() == null) yield "No recent success — scheduler may be permanently stuck.";
                Duration overdue = Duration.between(s.lastSuccessAt().plus(s.expectedInterval()), Instant.now());
                yield String.format("Overdue by %ds. Last success: %s. Investigate advisory lock leaks or node failures.",
                        overdue.toSeconds(), s.lastSuccessAt());
            }
        };
    }
}
