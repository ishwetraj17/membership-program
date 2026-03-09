package com.firstclub.platform.integrity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.platform.integrity.entity.IntegrityCheckFinding;
import com.firstclub.platform.integrity.entity.IntegrityCheckRun;
import com.firstclub.platform.integrity.repository.IntegrityCheckFindingRepository;
import com.firstclub.platform.integrity.repository.IntegrityCheckRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates integrity check runs.
 *
 * <p>Supports two entry points:
 * <ul>
 *   <li>{@link #runAll} — execute every registered checker and persist a run record.</li>
 *   <li>{@link #runSingle} — execute one checker by invariant key and persist a run record.</li>
 * </ul>
 *
 * <p>Run records ({@link IntegrityCheckRun}) and per-checker findings
 * ({@link IntegrityCheckFinding}) are persisted in the {@code integrity_check_runs}
 * and {@code integrity_check_findings} tables respectively.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityRunService {

    private final IntegrityCheckRegistry     registry;
    private final IntegrityCheckRunRepository    runRepository;
    private final IntegrityCheckFindingRepository findingRepository;
    private final ObjectMapper objectMapper;

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Runs all registered checkers.
     *
     * @param merchantId optional merchant scope; null = platform-wide
     * @param initiatedByUserId optional admin user ID for audit
     * @return the persisted {@link IntegrityCheckRun} with status populated
     */
    @Transactional
    public IntegrityCheckRun runAll(@Nullable Long merchantId,
                                    @Nullable Long initiatedByUserId) {
        List<IntegrityChecker> checkers = registry.getAll();
        IntegrityCheckRun run = createRun(null, merchantId, initiatedByUserId);
        return executeCheckers(run, checkers, merchantId);
    }

    /**
     * Runs a single checker identified by {@code invariantKey}.
     *
     * @param invariantKey the checker key (see {@link IntegrityChecker#getInvariantKey()})
     * @param merchantId   optional merchant scope
     * @param initiatedByUserId optional admin user ID
     * @return the persisted run record
     * @throws IllegalArgumentException if no checker is registered for {@code invariantKey}
     */
    @Transactional
    public IntegrityCheckRun runSingle(String invariantKey,
                                       @Nullable Long merchantId,
                                       @Nullable Long initiatedByUserId) {
        IntegrityChecker checker = registry.findByKey(invariantKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No integrity checker registered for key: " + invariantKey));

        IntegrityCheckRun run = createRun(invariantKey, merchantId, initiatedByUserId);
        return executeCheckers(run, List.of(checker), merchantId);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private IntegrityCheckRun createRun(@Nullable String invariantKey,
                                         @Nullable Long merchantId,
                                         @Nullable Long initiatedByUserId) {
        IntegrityCheckRun run = IntegrityCheckRun.builder()
                .startedAt(LocalDateTime.now())
                .status("RUNNING")
                .invariantKey(invariantKey)
                .merchantId(merchantId)
                .initiatedByUserId(initiatedByUserId)
                .totalChecks(0)
                .failedChecks(0)
                .build();
        return runRepository.save(run);
    }

    private IntegrityCheckRun executeCheckers(IntegrityCheckRun run,
                                               List<IntegrityChecker> checkers,
                                               @Nullable Long merchantId) {
        List<Map<String, Object>> summaryEntries = new ArrayList<>();
        int failedChecks = 0;
        boolean hadError = false;

        for (IntegrityChecker checker : checkers) {
            IntegrityCheckFinding finding = runOneChecker(checker, run.getId(), merchantId);
            findingRepository.save(finding);

            if ("FAIL".equals(finding.getStatus())) {
                failedChecks++;
            } else if ("ERROR".equals(finding.getStatus())) {
                hadError = true;
                failedChecks++;
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("key",             finding.getInvariantKey());
            summary.put("status",          finding.getStatus());
            summary.put("severity",        finding.getSeverity());
            summary.put("violationCount",  finding.getViolationCount());
            summaryEntries.add(summary);
        }

        String finalStatus;
        if (failedChecks == 0) {
            finalStatus = "COMPLETED";
        } else if (failedChecks < checkers.size()) {
            finalStatus = hadError ? "PARTIAL_FAILURE" : "PARTIAL_FAILURE";
        } else {
            finalStatus = "PARTIAL_FAILURE";
        }

        run.setStatus(finalStatus);
        run.setTotalChecks(checkers.size());
        run.setFailedChecks(failedChecks);
        run.setFinishedAt(LocalDateTime.now());
        run.setSummaryJson(toJson(summaryEntries));

        return runRepository.save(run);
    }

    private IntegrityCheckFinding runOneChecker(IntegrityChecker checker,
                                                  Long runId,
                                                  @Nullable Long merchantId) {
        String key = checker.getInvariantKey();
        log.info("[INTEGRITY] Running checker: {}", key);
        try {
            IntegrityCheckResult result = checker.run(merchantId);
            String status = result.isPassed() ? "PASS" : "FAIL";
            log.info("[INTEGRITY] Checker {} → {} (violations={})", key, status, result.getViolationCount());

            return IntegrityCheckFinding.builder()
                    .runId(runId)
                    .invariantKey(key)
                    .severity(result.getSeverity().name())
                    .status(status)
                    .violationCount(result.getViolationCount())
                    .detailsJson(buildDetailsJson(result))
                    .suggestedRepairKey(result.getSuggestedRepairKey())
                    .build();

        } catch (Exception ex) {
            log.error("[INTEGRITY] Checker {} threw an exception: {}", key, ex.getMessage(), ex);
            return IntegrityCheckFinding.builder()
                    .runId(runId)
                    .invariantKey(key)
                    .severity(checker.getSeverity().name())
                    .status("ERROR")
                    .violationCount(0)
                    .detailsJson("{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}")
                    .build();
        }
    }

    private String buildDetailsJson(IntegrityCheckResult result) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("details",    result.getDetails());
            map.put("violations", result.getViolations());
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"details\":\"" + escapeJson(result.getDetails()) + "\"}";
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
