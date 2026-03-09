package com.firstclub.integrity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InvariantEngine}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Results from all registered checkers are aggregated.</li>
 *   <li>A checker that throws an exception produces {@code InvariantStatus.ERROR}, not a crash.</li>
 *   <li>Mixed pass/fail results are all returned.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InvariantEngineTest {

    @Test
    void runAll_aggregatesAllCheckerResults() {
        InvariantChecker passing = mock(InvariantChecker.class);
        when(passing.getName()).thenReturn("CHECKER_A");
        when(passing.check()).thenReturn(InvariantResult.pass("CHECKER_A", InvariantSeverity.LOW));

        InvariantChecker failing = mock(InvariantChecker.class);
        when(failing.getName()).thenReturn("CHECKER_B");
        when(failing.check()).thenReturn(InvariantResult.fail("CHECKER_B", InvariantSeverity.HIGH,
                List.of(InvariantViolation.builder()
                        .entityType("Foo")
                        .entityId("1")
                        .description("violation")
                        .build())));

        InvariantEngine engine = new InvariantEngine(List.of(passing, failing));
        List<InvariantResult> results = engine.runAll();

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(InvariantResult::isPassed).count()).isEqualTo(1);
        assertThat(results.stream().filter(InvariantResult::isFailed).count()).isEqualTo(1);
    }

    @Test
    void runAll_checkerException_producesErrorResultNotCrash() {
        InvariantChecker throwing = mock(InvariantChecker.class);
        when(throwing.getName()).thenReturn("THROWING_CHECKER");
        when(throwing.getSeverity()).thenReturn(InvariantSeverity.CRITICAL);
        when(throwing.check()).thenThrow(new RuntimeException("simulated DB error"));

        InvariantEngine engine = new InvariantEngine(List.of(throwing));
        List<InvariantResult> results = engine.runAll();

        assertThat(results).hasSize(1);
        InvariantResult r = results.get(0);
        assertThat(r.isError()).isTrue();
        assertThat(r.isPassed()).isFalse();
        assertThat(r.getViolationCount()).isZero();
    }

    @Test
    void runAll_emptyCheckerList_returnsEmptyResults() {
        InvariantEngine engine = new InvariantEngine(List.of());
        assertThat(engine.runAll()).isEmpty();
    }

    @Test
    void runAll_allPass_allResultsArePassed() {
        InvariantChecker c1 = mock(InvariantChecker.class);
        InvariantChecker c2 = mock(InvariantChecker.class);
        when(c1.getName()).thenReturn("C1");
        when(c1.check()).thenReturn(InvariantResult.pass("C1", InvariantSeverity.LOW));
        when(c2.getName()).thenReturn("C2");
        when(c2.check()).thenReturn(InvariantResult.pass("C2", InvariantSeverity.LOW));

        InvariantEngine engine = new InvariantEngine(List.of(c1, c2));
        List<InvariantResult> results = engine.runAll();

        assertThat(results).allMatch(InvariantResult::isPassed);
    }
}
