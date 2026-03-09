package com.firstclub.integrity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.integrity.api.dto.IntegrityCheckResultResponseDTO;
import com.firstclub.integrity.api.dto.IntegrityCheckRunResponseDTO;
import com.firstclub.integrity.entity.IntegrityCheckResult;
import com.firstclub.integrity.entity.IntegrityCheckRun;
import com.firstclub.integrity.entity.IntegrityCheckRunStatus;
import com.firstclub.integrity.repository.IntegrityCheckResultRepository;
import com.firstclub.integrity.repository.IntegrityCheckRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orchestrates a full integrity-check run:
 * <ol>
 *   <li>Persists a {@code RUNNING} {@link IntegrityCheckRun} row.</li>
 *   <li>Delegates to {@link InvariantEngine#runAll()} to execute all registered checkers.</li>
 *   <li>Persists one {@link IntegrityCheckResult} row per checker.</li>
 *   <li>Updates the run status to {@code COMPLETED} (all pass) or {@code FAILED} (any violation/error).</li>
 * </ol>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class IntegrityCheckService {

    private final InvariantEngine                engine;
    private final IntegrityCheckRunRepository    runRepository;
    private final IntegrityCheckResultRepository resultRepository;
    private final IntegrityRepairSuggestionService repairSuggestionService;
    private final ObjectMapper                   objectMapper;

    /**
     * Executes all registered invariant checkers and persists results.
     *
     * @param triggeredBy  free-form identifier of the caller (admin userId, "scheduler", etc.)
     * @param requestId    optional inbound HTTP request-id (may be null)
     * @param correlationId optional correlation id from upstream services (may be null)
     */
    public IntegrityCheckRunResponseDTO runCheck(String triggeredBy,
                                                 String requestId,
                                                 String correlationId) {
        // 1. Create the RUNNING record
        IntegrityCheckRun run = IntegrityCheckRun.builder()
                .startedAt(LocalDateTime.now())
                .status(IntegrityCheckRunStatus.RUNNING)
                .triggeredBy(triggeredBy)
                .requestId(requestId)
                .correlationId(correlationId)
                .build();
        run = runRepository.save(run);
        final Long runId = run.getId();

        log.info("integrity-check run={} started by={}", runId, triggeredBy);

        // 2. Run all checkers
        List<InvariantResult> results = engine.runAll();

        // 3. Persist each result
        long failedOrError = 0;
        for (InvariantResult result : results) {
            String detailsJson = serializeViolations(result);
            String repair = determineRepairAction(result);

            IntegrityCheckResult row = IntegrityCheckResult.builder()
                    .runId(runId)
                    .invariantName(result.getInvariantName())
                    .status(result.getStatus().name())
                    .violationCount(result.getViolationCount())
                    .severity(result.getSeverity().name())
                    .detailsJson(detailsJson)
                    .suggestedRepairAction(repair)
                    .build();

            resultRepository.save(row);

            if (!result.isPassed()) {
                failedOrError++;
            }
        }

        // 4. Update run to terminal status
        IntegrityCheckRunStatus finalStatus =
                failedOrError == 0 ? IntegrityCheckRunStatus.COMPLETED : IntegrityCheckRunStatus.FAILED;
        run.setCompletedAt(LocalDateTime.now());
        run.setStatus(finalStatus);
        run = runRepository.save(run);

        log.info("integrity-check run={} finished status={} checkers={} failed={}",
                runId, finalStatus, results.size(), failedOrError);

        return buildResponse(run, results);
    }

    /**
     * Retrieves a persisted run and its results by ID.
     */
    @Transactional(readOnly = true)
    public IntegrityCheckRunResponseDTO getRunById(Long id) {
        IntegrityCheckRun run = runRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("IntegrityCheckRun not found: " + id));

        List<IntegrityCheckResult> dbResults = resultRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        List<InvariantResult> results = dbResults.stream()
                .map(this::recreateResult)
                .toList();

        return buildResponse(run, results);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String serializeViolations(InvariantResult result) {
        try {
            return objectMapper.writeValueAsString(result.getViolations());
        } catch (Exception e) {
            log.warn("Failed to serialize violations for {}: {}", result.getInvariantName(), e.getMessage());
            return "[]";
        }
    }

    private String determineRepairAction(InvariantResult result) {
        if (!result.getViolations().isEmpty()) {
            // Use the suggestion from the first violation if present
            String fromViolation = result.getViolations().get(0).getSuggestedRepairAction();
            if (fromViolation != null && !fromViolation.isBlank()) {
                return fromViolation;
            }
        }
        return repairSuggestionService.getSuggestionForInvariant(result.getInvariantName());
    }

    private IntegrityCheckRunResponseDTO buildResponse(IntegrityCheckRun run,
                                                       List<InvariantResult> results) {
        long failedCount = results.stream().filter(r -> !r.isPassed() && !r.isError()).count();
        long errorCount  = results.stream().filter(InvariantResult::isError).count();

        List<IntegrityCheckResultResponseDTO> resultDTOs = results.stream()
                .map(r -> IntegrityCheckResultResponseDTO.builder()
                        .invariantName(r.getInvariantName())
                        .status(r.getStatus().name())
                        .violationCount(r.getViolationCount())
                        .severity(r.getSeverity().name())
                        .affectedEntities(r.getViolations().stream()
                                .map(InvariantViolation::toAffectedEntityString)
                                .toList())
                        .suggestedRepairAction(
                                r.getViolations().isEmpty() ? null
                                : r.getViolations().get(0).getSuggestedRepairAction())
                        .build())
                .toList();

        return IntegrityCheckRunResponseDTO.builder()
                .id(run.getId())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .status(run.getStatus().name())
                .triggeredBy(run.getTriggeredBy())
                .requestId(run.getRequestId())
                .correlationId(run.getCorrelationId())
                .totalCheckers(results.size())
                .failedCheckers((int) failedCount)
                .errorCheckers((int) errorCount)
                .results(resultDTOs)
                .build();
    }

    private InvariantResult recreateResult(IntegrityCheckResult row) {
        InvariantStatus status = InvariantStatus.valueOf(row.getStatus());
        InvariantSeverity severity = InvariantSeverity.valueOf(row.getSeverity());
        return switch (status) {
            case PASS  -> InvariantResult.pass(row.getInvariantName(), severity);
            case FAIL  -> InvariantResult.fail(row.getInvariantName(), severity,
                          deserializeViolations(row.getDetailsJson(), row.getSuggestedRepairAction()));
            case ERROR -> InvariantResult.error(row.getInvariantName(), severity,
                          row.getSuggestedRepairAction());
        };
    }

    private List<InvariantViolation> deserializeViolations(String json, String fallbackRepair) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, InvariantViolation.class));
        } catch (Exception e) {
            log.warn("Failed to deserialize violations: {}", e.getMessage());
            // Return a placeholder violation so violationCount shows correctly
            return List.of(InvariantViolation.builder()
                    .entityType("unknown")
                    .entityId("unknown")
                    .description("Violation details unavailable (deserialization error)")
                    .suggestedRepairAction(fallbackRepair)
                    .build());
        }
    }
}
