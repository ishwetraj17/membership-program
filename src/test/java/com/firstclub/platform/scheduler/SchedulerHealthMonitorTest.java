package com.firstclub.platform.scheduler;

import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import com.firstclub.platform.scheduler.health.SchedulerHealth;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor.SchedulerStatusSnapshot;
import com.firstclub.platform.scheduler.repository.SchedulerExecutionHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchedulerHealthMonitor}.
 *
 * <h3>Stale detection scenarios</h3>
 * <ul>
 *   <li>Scheduler that has never run → {@code NEVER_RAN}</li>
 *   <li>Last success within expected interval → {@code HEALTHY}</li>
 *   <li>Last success beyond expected interval → {@code STALE}</li>
 * </ul>
 *
 * <h3>Snapshot scenarios</h3>
 * <ul>
 *   <li>{@code getSnapshot} embeds health + last-run metadata</li>
 *   <li>{@code getAllSnapshots} queries all known scheduler names</li>
 *   <li>{@code findStaleRunningRecords} delegates threshold to the repo</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("SchedulerHealthMonitor — Unit Tests")
class SchedulerHealthMonitorTest {

    private static final String SCHEDULER_A = "subscription-renewal";
    private static final String SCHEDULER_B = "dunning-daily";
    private static final Duration INTERVAL_25H = Duration.ofHours(25);

    @Mock
    private SchedulerExecutionHistoryRepository historyRepo;

    private SchedulerHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new SchedulerHealthMonitor(historyRepo);
    }

    // ── checkHealth ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkHealth — enum result")
    class CheckHealth {

        @Test
        @DisplayName("checkHealth_neverRan_returnsNeverRan — no success record exists")
        void checkHealth_neverRan_returnsNeverRan() {
            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.empty());

            SchedulerHealth result = monitor.checkHealth(SCHEDULER_A, INTERVAL_25H);

            assertThat(result).isEqualTo(SchedulerHealth.NEVER_RAN);
        }

        @Test
        @DisplayName("checkHealth_recentSuccess_returnsHealthy — completedAt within interval")
        void checkHealth_recentSuccess_returnsHealthy() {
            // Last success 1 hour ago — well within 25-hour window
            SchedulerExecutionHistory recentSuccess = successRecord(SCHEDULER_A,
                    Instant.now().minus(Duration.ofHours(1)));
            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(recentSuccess));

            SchedulerHealth result = monitor.checkHealth(SCHEDULER_A, INTERVAL_25H);

            assertThat(result).isEqualTo(SchedulerHealth.HEALTHY);
        }

        @Test
        @DisplayName("checkHealth_staleSuccess_returnsStale — completedAt beyond interval")
        void checkHealth_staleSuccess_returnsStale() {
            // Last success 48 hours ago — exceeds 25-hour expected interval
            SchedulerExecutionHistory staleSuccess = successRecord(SCHEDULER_A,
                    Instant.now().minus(Duration.ofHours(48)));
            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(staleSuccess));

            SchedulerHealth result = monitor.checkHealth(SCHEDULER_A, INTERVAL_25H);

            assertThat(result).isEqualTo(SchedulerHealth.STALE);
        }

        @Test
        @DisplayName("checkHealth_exactlyAtIntervalBoundary_returnsHealthy — boundary is inclusive")
        void checkHealth_exactlyAtIntervalBoundary_returnsHealthy() {
            // Completed exactly at the boundary — isBefore is strict, so this is HEALTHY
            // (completedAt == staleThreshold → NOT stale)
            Instant exactBoundary = Instant.now().minus(INTERVAL_25H).plusMillis(500);
            SchedulerExecutionHistory boundarySuccess = successRecord(SCHEDULER_A, exactBoundary);
            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(boundarySuccess));

            SchedulerHealth result = monitor.checkHealth(SCHEDULER_A, INTERVAL_25H);

            assertThat(result).isEqualTo(SchedulerHealth.HEALTHY);
        }
    }

    // ── getSnapshot ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSnapshot — rich diagnostic object")
    class GetSnapshot {

        @Test
        @DisplayName("getSnapshot_neverRan_snapshotHasNullTimes")
        void getSnapshot_neverRan_snapshotHasNullTimes() {
            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.empty());
            when(historyRepo.findLatestBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.empty());

            SchedulerStatusSnapshot snapshot = monitor.getSnapshot(SCHEDULER_A, INTERVAL_25H);

            assertThat(snapshot.schedulerName()).isEqualTo(SCHEDULER_A);
            assertThat(snapshot.health()).isEqualTo(SchedulerHealth.NEVER_RAN);
            assertThat(snapshot.lastSuccessAt()).isNull();
            assertThat(snapshot.lastRunAt()).isNull();
            assertThat(snapshot.lastRunStatus()).isNull();
            assertThat(snapshot.expectedInterval()).isEqualTo(INTERVAL_25H);
        }

        @Test
        @DisplayName("getSnapshot_healthyScheduler_snapshotPopulated")
        void getSnapshot_healthyScheduler_snapshotPopulated() {
            Instant recentCompletedAt = Instant.now().minus(Duration.ofHours(2));
            Instant recentStartedAt   = recentCompletedAt.minusSeconds(30);

            SchedulerExecutionHistory successRow = successRecord(SCHEDULER_A, recentCompletedAt);
            successRow.setStartedAt(recentStartedAt);

            SchedulerExecutionHistory lastRunRow = successRecord(SCHEDULER_A, recentCompletedAt);
            lastRunRow.setStartedAt(recentStartedAt);

            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(successRow));
            when(historyRepo.findLatestBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(lastRunRow));

            SchedulerStatusSnapshot snapshot = monitor.getSnapshot(SCHEDULER_A, INTERVAL_25H);

            assertThat(snapshot.health()).isEqualTo(SchedulerHealth.HEALTHY);
            assertThat(snapshot.lastSuccessAt()).isEqualTo(recentCompletedAt);
            assertThat(snapshot.lastRunAt()).isEqualTo(recentStartedAt);
            assertThat(snapshot.lastRunStatus()).isEqualTo(SchedulerExecutionHistory.STATUS_SUCCESS);
        }

        @Test
        @DisplayName("getSnapshot_staleScheduler_healthIsStale")
        void getSnapshot_staleScheduler_healthIsStale() {
            Instant staleCompletedAt = Instant.now().minus(Duration.ofHours(50));
            SchedulerExecutionHistory staleRow = successRecord(SCHEDULER_A, staleCompletedAt);

            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(staleRow));
            when(historyRepo.findLatestBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(staleRow));

            SchedulerStatusSnapshot snapshot = monitor.getSnapshot(SCHEDULER_A, INTERVAL_25H);

            assertThat(snapshot.health()).isEqualTo(SchedulerHealth.STALE);
        }
    }

    // ── getAllSnapshots ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllSnapshots — aggregates all known schedulers")
    class GetAllSnapshots {

        @Test
        @DisplayName("getAllSnapshots_noSchedulers_returnsEmptyList")
        void getAllSnapshots_noSchedulers_returnsEmptyList() {
            when(historyRepo.findAllKnownSchedulerNames()).thenReturn(List.of());

            List<SchedulerStatusSnapshot> snapshots = monitor.getAllSnapshots(INTERVAL_25H);

            assertThat(snapshots).isEmpty();
        }

        @Test
        @DisplayName("getAllSnapshots_twoSchedulers_returnsBothSnapshots")
        void getAllSnapshots_twoSchedulers_returnsBothSnapshots() {
            when(historyRepo.findAllKnownSchedulerNames())
                    .thenReturn(List.of(SCHEDULER_A, SCHEDULER_B));

            Instant recentA = Instant.now().minus(Duration.ofHours(1));
            Instant staleB  = Instant.now().minus(Duration.ofHours(48));

            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(successRecord(SCHEDULER_A, recentA)));
            when(historyRepo.findLatestBySchedulerName(SCHEDULER_A))
                    .thenReturn(Optional.of(successRecord(SCHEDULER_A, recentA)));

            when(historyRepo.findLatestSuccessBySchedulerName(SCHEDULER_B))
                    .thenReturn(Optional.of(successRecord(SCHEDULER_B, staleB)));
            when(historyRepo.findLatestBySchedulerName(SCHEDULER_B))
                    .thenReturn(Optional.of(successRecord(SCHEDULER_B, staleB)));

            List<SchedulerStatusSnapshot> snapshots = monitor.getAllSnapshots(INTERVAL_25H);

            assertThat(snapshots).hasSize(2);
            assertThat(snapshots).extracting(SchedulerStatusSnapshot::schedulerName)
                    .containsExactlyInAnyOrder(SCHEDULER_A, SCHEDULER_B);

            SchedulerStatusSnapshot snapshotA = snapshots.stream()
                    .filter(s -> s.schedulerName().equals(SCHEDULER_A)).findFirst().orElseThrow();
            SchedulerStatusSnapshot snapshotB = snapshots.stream()
                    .filter(s -> s.schedulerName().equals(SCHEDULER_B)).findFirst().orElseThrow();

            assertThat(snapshotA.health()).isEqualTo(SchedulerHealth.HEALTHY);
            assertThat(snapshotB.health()).isEqualTo(SchedulerHealth.STALE);
        }

        @Test
        @DisplayName("getAllSnapshots_queriesKnownSchedulerNames — delegates to repo")
        void getAllSnapshots_queriesKnownSchedulerNames() {
            when(historyRepo.findAllKnownSchedulerNames()).thenReturn(List.of());

            monitor.getAllSnapshots(INTERVAL_25H);

            verify(historyRepo).findAllKnownSchedulerNames();
        }
    }

    // ── findStaleRunningRecords ───────────────────────────────────────────────

    @Nested
    @DisplayName("findStaleRunningRecords — crash detection")
    class FindStaleRunningRecords {

        @Test
        @DisplayName("findStaleRunningRecords_returnsRepoResult — delegates threshold to repo")
        void findStaleRunningRecords_returnsRepoResult() {
            SchedulerExecutionHistory stuckRow = SchedulerExecutionHistory.builder()
                    .id(99L)
                    .schedulerName(SCHEDULER_A)
                    .nodeId("dead-node")
                    .startedAt(Instant.now().minus(Duration.ofHours(2)))
                    .status(SchedulerExecutionHistory.STATUS_RUNNING)
                    .build();
            when(historyRepo.findStaleRunningRecords(any(Instant.class)))
                    .thenReturn(List.of(stuckRow));

            List<SchedulerExecutionHistory> stale =
                    monitor.findStaleRunningRecords(Duration.ofMinutes(30));

            assertThat(stale).hasSize(1);
            assertThat(stale.get(0).getSchedulerName()).isEqualTo(SCHEDULER_A);
            assertThat(stale.get(0).getStatus()).isEqualTo(SchedulerExecutionHistory.STATUS_RUNNING);
        }

        @Test
        @DisplayName("findStaleRunningRecords_noneStuck_returnsEmptyList")
        void findStaleRunningRecords_noneStuck_returnsEmptyList() {
            when(historyRepo.findStaleRunningRecords(any(Instant.class)))
                    .thenReturn(List.of());

            List<SchedulerExecutionHistory> stale =
                    monitor.findStaleRunningRecords(Duration.ofMinutes(30));

            assertThat(stale).isEmpty();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SchedulerExecutionHistory successRecord(String schedulerName, Instant completedAt) {
        return SchedulerExecutionHistory.builder()
                .id(1L)
                .schedulerName(schedulerName)
                .nodeId("test-node")
                .startedAt(completedAt.minusSeconds(10))
                .completedAt(completedAt)
                .status(SchedulerExecutionHistory.STATUS_SUCCESS)
                .processedCount(100)
                .build();
    }
}
