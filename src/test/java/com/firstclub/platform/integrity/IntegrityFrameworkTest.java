package com.firstclub.platform.integrity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.platform.integrity.entity.IntegrityCheckFinding;
import com.firstclub.platform.integrity.entity.IntegrityCheckRun;
import com.firstclub.platform.integrity.repository.IntegrityCheckFindingRepository;
import com.firstclub.platform.integrity.repository.IntegrityCheckRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntegrityCheckRegistry} and {@link IntegrityRunService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Integrity Framework — Unit Tests")
class IntegrityFrameworkTest {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static IntegrityChecker stubChecker(String key, IntegrityCheckSeverity severity) {
        IntegrityChecker c = mock(IntegrityChecker.class);
        when(c.getInvariantKey()).thenReturn(key);
        when(c.getSeverity()).thenReturn(severity);
        when(c.run(any())).thenReturn(passResult(key, severity));
        return c;
    }

    private static IntegrityCheckResult passResult(String key, IntegrityCheckSeverity severity) {
        return IntegrityCheckResult.builder()
                .invariantKey(key)
                .severity(severity)
                .passed(true)
                .violationCount(0)
                .violations(List.of())
                .checkedAt(LocalDateTime.now())
                .build();
    }

    private static IntegrityCheckResult failResult(String key, IntegrityCheckSeverity severity,
                                                     IntegrityViolation violation) {
        return IntegrityCheckResult.builder()
                .invariantKey(key)
                .severity(severity)
                .passed(false)
                .violationCount(1)
                .violations(List.of(violation))
                .suggestedRepairKey("some.repair")
                .checkedAt(LocalDateTime.now())
                .build();
    }

    // ── Registry tests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("IntegrityCheckRegistry")
    class RegistryTest {

        private IntegrityCheckRegistry registry;

        @BeforeEach
        void setUp() {
            IntegrityChecker c1 = stubChecker("billing.total", IntegrityCheckSeverity.CRITICAL);
            IntegrityChecker c2 = stubChecker("payments.refund", IntegrityCheckSeverity.HIGH);
            IntegrityChecker c3 = stubChecker("ledger.balanced", IntegrityCheckSeverity.CRITICAL);
            registry = new IntegrityCheckRegistry(List.of(c1, c2, c3));
        }

        @Test
        @DisplayName("getAll() returns all registered checkers")
        void getAll_returnsAllCheckers() {
            assertThat(registry.getAll()).hasSize(3);
        }

        @Test
        @DisplayName("size() matches registered count")
        void size_matchesCount() {
            assertThat(registry.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("findByKey() finds existing checker")
        void findByKey_findsExistingChecker() {
            Optional<IntegrityChecker> result = registry.findByKey("payments.refund");
            assertThat(result).isPresent();
            assertThat(result.get().getInvariantKey()).isEqualTo("payments.refund");
        }

        @Test
        @DisplayName("findByKey() returns empty for unknown key")
        void findByKey_returnsEmpty_forUnknownKey() {
            assertThat(registry.findByKey("nonexistent.key")).isEmpty();
        }

        @Test
        @DisplayName("getSeverityMap() maps each key to correct severity")
        void getSeverityMap_containsAllEntries() {
            Map<String, IntegrityCheckSeverity> map = registry.getSeverityMap();
            assertThat(map).containsEntry("billing.total", IntegrityCheckSeverity.CRITICAL);
            assertThat(map).containsEntry("payments.refund", IntegrityCheckSeverity.HIGH);
            assertThat(map).hasSize(3);
        }

        @Test
        @DisplayName("getAll() returns an unmodifiable list")
        void getAll_returnsUnmodifiableList() {
            List<IntegrityChecker> list = registry.getAll();
            assertThatThrownBy(() -> list.add(mock(IntegrityChecker.class)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── RunService tests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("IntegrityRunService")
    class RunServiceTest {

        @Mock private IntegrityCheckRegistry registry;
        @Mock private IntegrityCheckRunRepository runRepository;
        @Mock private IntegrityCheckFindingRepository findingRepository;
        @Mock private ObjectMapper objectMapper;

        private IntegrityRunService service;

        @BeforeEach
        void setUp() throws JsonProcessingException {
            service = new IntegrityRunService(registry, runRepository, findingRepository, objectMapper);
            when(objectMapper.writeValueAsString(any())).thenReturn("[]");
            when(runRepository.save(any(IntegrityCheckRun.class))).thenAnswer(inv -> {
                IntegrityCheckRun run = inv.getArgument(0);
                if (run.getId() == null) run.setId(100L);
                return run;
            });
            when(findingRepository.save(any(IntegrityCheckFinding.class))).thenAnswer(inv -> {
                IntegrityCheckFinding f = inv.getArgument(0);
                f.setId(99L);
                return f;
            });
        }

        @Test
        @DisplayName("runAll() persists a run with COMPLETED status when all pass")
        void runAll_persistsCompletedRun_whenAllCheckerPass() {
            IntegrityChecker c1 = stubChecker("a.b", IntegrityCheckSeverity.HIGH);
            IntegrityChecker c2 = stubChecker("c.d", IntegrityCheckSeverity.MEDIUM);
            when(registry.getAll()).thenReturn(List.of(c1, c2));

            IntegrityCheckRun run = service.runAll(null, null);

            assertThat(run.getStatus()).isEqualTo("COMPLETED");
            assertThat(run.getTotalChecks()).isEqualTo(2);
            assertThat(run.getFailedChecks()).isEqualTo(0);
            verify(findingRepository, times(2)).save(any(IntegrityCheckFinding.class));
        }

        @Test
        @DisplayName("runAll() with failing checker produces PARTIAL_FAILURE status")
        void runAll_producesPartialFailure_whenCheckerFails() {
            IntegrityChecker passing = stubChecker("a.ok", IntegrityCheckSeverity.LOW);
            IntegrityChecker failing = mock(IntegrityChecker.class);
            when(failing.getInvariantKey()).thenReturn("a.fail");
            when(failing.getSeverity()).thenReturn(IntegrityCheckSeverity.CRITICAL);
            when(failing.run(any())).thenReturn(failResult("a.fail", IntegrityCheckSeverity.CRITICAL,
                    IntegrityViolation.builder().entityType("X").entityId(1L).details("bad").build()));
            when(registry.getAll()).thenReturn(List.of(passing, failing));

            IntegrityCheckRun run = service.runAll(42L, 7L);

            assertThat(run.getFailedChecks()).isEqualTo(1);
            assertThat(run.getMerchantId()).isEqualTo(42L);
            assertThat(run.getInitiatedByUserId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("runAll() records ERROR finding when checker throws")
        void runAll_recordsErrorFinding_whenCheckerThrows() {
            IntegrityChecker bad = mock(IntegrityChecker.class);
            when(bad.getInvariantKey()).thenReturn("x.crash");
            when(bad.getSeverity()).thenReturn(IntegrityCheckSeverity.CRITICAL);
            when(bad.run(any())).thenThrow(new RuntimeException("DB down"));
            when(registry.getAll()).thenReturn(List.of(bad));

            IntegrityCheckRun run = service.runAll(null, null);

            assertThat(run.getFailedChecks()).isEqualTo(1);
            verify(findingRepository).save(argThat(f -> "ERROR".equals(f.getStatus())));
        }

        @Test
        @DisplayName("runSingle() creates run scoped to one invariant key")
        void runSingle_createsRunForSingleKey() {
            IntegrityChecker checker = stubChecker("billing.total", IntegrityCheckSeverity.CRITICAL);
            when(registry.findByKey("billing.total")).thenReturn(Optional.of(checker));

            IntegrityCheckRun run = service.runSingle("billing.total", null, null);

            assertThat(run.getInvariantKey()).isEqualTo("billing.total");
            assertThat(run.getTotalChecks()).isEqualTo(1);
            verify(findingRepository, times(1)).save(any(IntegrityCheckFinding.class));
        }

        @Test
        @DisplayName("runSingle() throws IllegalArgumentException for unknown key")
        void runSingle_throwsIllegalArgumentException_forUnknownKey() {
            when(registry.findByKey("bad.key")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.runSingle("bad.key", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bad.key");
        }
    }
}
