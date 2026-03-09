package com.firstclub.platform.scheduler;

import com.firstclub.platform.lock.redis.LockOwnerIdentityProvider;
import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import com.firstclub.platform.scheduler.repository.SchedulerExecutionHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchedulerExecutionRecorder}.
 *
 * <p>Each test operates on the recorder's write path in isolation from the
 * transaction infrastructure ({@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW}
 * is a container concern and is not exercised here).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchedulerExecutionRecorder — Unit Tests")
class SchedulerExecutionRecorderTest {

    private static final String SCHEDULER_NAME = "subscription-renewal";
    private static final String NODE_ID = "node-abc12345";
    private static final long RUN_ID = 42L;

    @Mock
    private SchedulerExecutionHistoryRepository historyRepo;

    @Mock
    private LockOwnerIdentityProvider ownerIdentity;

    private SchedulerExecutionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new SchedulerExecutionRecorder(historyRepo, ownerIdentity);
        when(ownerIdentity.getInstanceId()).thenReturn(NODE_ID);
    }

    @Nested
    @DisplayName("recordStarted — RUNNING row persisted")
    class RecordStarted {

        @Test
        @DisplayName("recordStarted_persistsRunningRow — with nodeId and schedulerName")
        void recordStarted_persistsRunningRow() {
            SchedulerExecutionHistory savedRow = SchedulerExecutionHistory.builder()
                    .id(RUN_ID)
                    .schedulerName(SCHEDULER_NAME)
                    .nodeId(NODE_ID)
                    .startedAt(Instant.now())
                    .status(SchedulerExecutionHistory.STATUS_RUNNING)
                    .build();
            when(historyRepo.save(any(SchedulerExecutionHistory.class))).thenReturn(savedRow);

            Long runId = recorder.recordStarted(SCHEDULER_NAME);

            assertThat(runId).isEqualTo(RUN_ID);

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            SchedulerExecutionHistory persisted = captor.getValue();
            assertThat(persisted.getSchedulerName()).isEqualTo(SCHEDULER_NAME);
            assertThat(persisted.getNodeId()).isEqualTo(NODE_ID);
            assertThat(persisted.getStatus()).isEqualTo(SchedulerExecutionHistory.STATUS_RUNNING);
            assertThat(persisted.getStartedAt()).isNotNull();
            assertThat(persisted.getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("recordStarted_usesInstanceIdFromOwnerIdentity — correct nodeId")
        void recordStarted_usesInstanceIdFromOwnerIdentity() {
            SchedulerExecutionHistory savedRow = SchedulerExecutionHistory.builder()
                    .id(RUN_ID).schedulerName(SCHEDULER_NAME).nodeId(NODE_ID).startedAt(Instant.now())
                    .status(SchedulerExecutionHistory.STATUS_RUNNING).build();
            when(historyRepo.save(any(SchedulerExecutionHistory.class))).thenReturn(savedRow);

            recorder.recordStarted(SCHEDULER_NAME);

            verify(ownerIdentity).getInstanceId();
        }
    }

    @Nested
    @DisplayName("recordSuccess — row updated to SUCCESS")
    class RecordSuccess {

        @Test
        @DisplayName("recordSuccess_updatesStatusAndProcessedCount")
        void recordSuccess_updatesStatusAndProcessedCount() {
            SchedulerExecutionHistory existingRow = SchedulerExecutionHistory.builder()
                    .id(RUN_ID)
                    .schedulerName(SCHEDULER_NAME)
                    .nodeId(NODE_ID)
                    .startedAt(Instant.now().minusSeconds(30))
                    .status(SchedulerExecutionHistory.STATUS_RUNNING)
                    .build();
            when(historyRepo.findById(RUN_ID)).thenReturn(Optional.of(existingRow));
            when(historyRepo.save(any(SchedulerExecutionHistory.class))).thenReturn(existingRow);

            recorder.recordSuccess(RUN_ID, 150);

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            SchedulerExecutionHistory updated = captor.getValue();
            assertThat(updated.getStatus()).isEqualTo(SchedulerExecutionHistory.STATUS_SUCCESS);
            assertThat(updated.getProcessedCount()).isEqualTo(150);
            assertThat(updated.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("recordSuccess_runIdNotFound_logsWarnAndNoUpdate")
        void recordSuccess_runIdNotFound_logsWarnAndNoUpdate() {
            when(historyRepo.findById(RUN_ID)).thenReturn(Optional.empty());

            // Should not throw even if the run record is missing
            recorder.recordSuccess(RUN_ID, 0);

            verify(historyRepo).findById(RUN_ID);
        }
    }

    @Nested
    @DisplayName("recordFailure — row updated to FAILED")
    class RecordFailure {

        @Test
        @DisplayName("recordFailure_setsFailedStatusAndErrorMessage")
        void recordFailure_setsFailedStatusAndErrorMessage() {
            SchedulerExecutionHistory existingRow = SchedulerExecutionHistory.builder()
                    .id(RUN_ID).schedulerName(SCHEDULER_NAME).nodeId(NODE_ID)
                    .startedAt(Instant.now().minusSeconds(5))
                    .status(SchedulerExecutionHistory.STATUS_RUNNING).build();
            when(historyRepo.findById(RUN_ID)).thenReturn(Optional.of(existingRow));
            when(historyRepo.save(any(SchedulerExecutionHistory.class))).thenReturn(existingRow);

            RuntimeException ex = new RuntimeException("DB connection refused");
            recorder.recordFailure(RUN_ID, ex);

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            SchedulerExecutionHistory updated = captor.getValue();
            assertThat(updated.getStatus()).isEqualTo(SchedulerExecutionHistory.STATUS_FAILED);
            assertThat(updated.getErrorMessage()).isEqualTo("DB connection refused");
            assertThat(updated.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("recordFailure_errorMessageTruncatedAt4000Chars")
        void recordFailure_errorMessageTruncatedAt4000Chars() {
            SchedulerExecutionHistory existingRow = SchedulerExecutionHistory.builder()
                    .id(RUN_ID).schedulerName(SCHEDULER_NAME).nodeId(NODE_ID)
                    .startedAt(Instant.now().minusSeconds(5))
                    .status(SchedulerExecutionHistory.STATUS_RUNNING).build();
            when(historyRepo.findById(RUN_ID)).thenReturn(Optional.of(existingRow));
            when(historyRepo.save(any(SchedulerExecutionHistory.class))).thenReturn(existingRow);

            String longMessage = "E".repeat(6000);
            recorder.recordFailure(RUN_ID, new RuntimeException(longMessage));

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            assertThat(captor.getValue().getErrorMessage()).hasSize(4000);
        }

        @Test
        @DisplayName("recordFailure_nullExceptionMessage_usesClassName")
        void recordFailure_nullExceptionMessage_usesClassName() {
            SchedulerExecutionHistory existingRow = SchedulerExecutionHistory.builder()
                    .id(RUN_ID).schedulerName(SCHEDULER_NAME).nodeId(NODE_ID)
                    .startedAt(Instant.now().minusSeconds(5))
                    .status(SchedulerExecutionHistory.STATUS_RUNNING).build();
            when(historyRepo.findById(RUN_ID)).thenReturn(Optional.of(existingRow));
            when(historyRepo.save(any(SchedulerExecutionHistory.class))).thenReturn(existingRow);

            // Exception with null message
            recorder.recordFailure(RUN_ID, new NullPointerException());

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            assertThat(captor.getValue().getErrorMessage()).isEqualTo("NullPointerException");
        }
    }

    @Nested
    @DisplayName("recordSkipped — single terminal SKIPPED row")
    class RecordSkipped {

        @Test
        @DisplayName("recordSkipped_persistsSkippedRowWithReason")
        void recordSkipped_persistsSkippedRowWithReason() {
            when(historyRepo.save(any(SchedulerExecutionHistory.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            recorder.recordSkipped(SCHEDULER_NAME, "lock-busy");

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            SchedulerExecutionHistory persisted = captor.getValue();
            assertThat(persisted.getSchedulerName()).isEqualTo(SCHEDULER_NAME);
            assertThat(persisted.getStatus()).isEqualTo(SchedulerExecutionHistory.STATUS_SKIPPED);
            assertThat(persisted.getErrorMessage()).isEqualTo("lock-busy");
            assertThat(persisted.getNodeId()).isEqualTo(NODE_ID);
            // Started and completed should both be set (single-shot terminal record)
            assertThat(persisted.getStartedAt()).isNotNull();
            assertThat(persisted.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("recordSkipped_notPrimaryReason_persistsCorrectly")
        void recordSkipped_notPrimaryReason_persistsCorrectly() {
            when(historyRepo.save(any(SchedulerExecutionHistory.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            recorder.recordSkipped(SCHEDULER_NAME, "not-primary");

            ArgumentCaptor<SchedulerExecutionHistory> captor =
                    ArgumentCaptor.forClass(SchedulerExecutionHistory.class);
            verify(historyRepo).save(captor.capture());

            assertThat(captor.getValue().getErrorMessage()).isEqualTo("not-primary");
        }
    }
}
