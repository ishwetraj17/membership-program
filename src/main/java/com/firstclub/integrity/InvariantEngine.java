package com.firstclub.integrity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs all registered {@link InvariantChecker} instances and aggregates their results.
 *
 * <h3>Error isolation</h3>
 * If a checker throws any {@link Exception} it is caught here and converted to
 * {@link InvariantResult#error(String, InvariantSeverity, String)}.  The remaining
 * checkers still execute — a failure in one checker must never prevent the others
 * from running.
 *
 * <h3>Checker discovery</h3>
 * Spring auto-populates {@code List<InvariantChecker>} with all beans that implement
 * the interface.  Simply annotate a new checker with {@code @Component} and it is
 * registered automatically — no manual wiring required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvariantEngine {

    private final List<InvariantChecker> checkers;

    /**
     * Execute every registered checker in declaration order.
     *
     * @return one result per checker; never null, never throws
     */
    public List<InvariantResult> runAll() {
        log.info("InvariantEngine starting run — {} checkers registered", checkers.size());
        return checkers.stream()
                .map(this::runSafely)
                .toList();
    }

    private InvariantResult runSafely(InvariantChecker checker) {
        try {
            InvariantResult result = checker.check();
            log.debug("Checker [{}] → {} ({} violations)",
                    checker.getName(), result.getStatus(), result.getViolationCount());
            return result;
        } catch (Exception e) {
            log.error("Checker [{}] threw unexpectedly: {}", checker.getName(), e.getMessage(), e);
            return InvariantResult.error(checker.getName(), checker.getSeverity(),
                    "Checker threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
