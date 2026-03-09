package com.firstclub.platform.ops;

import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.platform.ops.dto.OutboxLagResponseDTO;
import com.firstclub.platform.ops.service.impl.OutboxOpsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OutboxOpsService — Unit Tests")
class OutboxOpsServiceTest {

    @Mock OutboxEventRepository  outboxEventRepository;
    @InjectMocks OutboxOpsServiceImpl service;

    /** Stub the two Phase-16 queries with safe defaults for every test. */
    @BeforeEach
    void stubPhase16Queries() {
        when(outboxEventRepository.findStaleProcessing(
                eq(OutboxEventStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(outboxEventRepository.findOldestCreatedAtInStatuses(any()))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("getOutboxLag")
    class GetOutboxLag {

        @Test
        @DisplayName("aggregates counts by status and totalPending only NEW+PROCESSING+FAILED")
        void aggregatesCounts() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(10L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(2L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(3L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.DONE)).thenReturn(500L);
            when(outboxEventRepository.countActiveByEventType(OutboxEventStatus.DONE))
                    .thenReturn(List.of());

            OutboxLagResponseDTO result = service.getOutboxLag();

            assertThat(result.newCount()).isEqualTo(10L);
            assertThat(result.processingCount()).isEqualTo(2L);
            assertThat(result.failedCount()).isEqualTo(3L);
            assertThat(result.doneCount()).isEqualTo(500L);
            assertThat(result.totalPending()).isEqualTo(15L);   // 10 + 2 + 3
        }

        @Test
        @DisplayName("builds byEventType map from repository query rows")
        void buildsByEventTypeMap() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(5L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(2L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.DONE)).thenReturn(0L);
            when(outboxEventRepository.countActiveByEventType(OutboxEventStatus.DONE))
                    .thenReturn(List.of(
                            new Object[]{"INVOICE_CREATED", 4L},
                            new Object[]{"PAYMENT_SUCCEEDED", 3L}));

            OutboxLagResponseDTO result = service.getOutboxLag();

            assertThat(result.byEventType()).containsEntry("INVOICE_CREATED", 4L);
            assertThat(result.byEventType()).containsEntry("PAYMENT_SUCCEEDED", 3L);
        }

        @Test
        @DisplayName("staleLeasesCount reflects stale PROCESSING events")
        void staleLeasesCount_reflectsStaleEvents() {
            when(outboxEventRepository.countByStatus(any())).thenReturn(0L);
            when(outboxEventRepository.countActiveByEventType(any())).thenReturn(List.of());

            // Override the default stub to return 2 stale events
            com.firstclub.outbox.entity.OutboxEvent stale1 =
                    com.firstclub.outbox.entity.OutboxEvent.builder()
                            .id(1L).status(OutboxEventStatus.PROCESSING).build();
            com.firstclub.outbox.entity.OutboxEvent stale2 =
                    com.firstclub.outbox.entity.OutboxEvent.builder()
                            .id(2L).status(OutboxEventStatus.PROCESSING).build();
            when(outboxEventRepository.findStaleProcessing(
                    eq(OutboxEventStatus.PROCESSING), any(LocalDateTime.class)))
                    .thenReturn(List.of(stale1, stale2));

            OutboxLagResponseDTO result = service.getOutboxLag();

            assertThat(result.staleLeasesCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("oldestPendingAgeSeconds is null when no pending events")
        void oldestPendingAgeSeconds_nullWhenNoPending() {
            when(outboxEventRepository.countByStatus(any())).thenReturn(0L);
            when(outboxEventRepository.countActiveByEventType(any())).thenReturn(List.of());
            // default stub returns empty

            OutboxLagResponseDTO result = service.getOutboxLag();

            assertThat(result.oldestPendingAgeSeconds()).isNull();
        }

        @Test
        @DisplayName("oldestPendingAgeSeconds is positive when pending events exist")
        void oldestPendingAgeSeconds_posWhenPendingExists() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(1L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.DONE)).thenReturn(0L);
            when(outboxEventRepository.countActiveByEventType(any())).thenReturn(List.of());
            when(outboxEventRepository.findOldestCreatedAtInStatuses(any()))
                    .thenReturn(Optional.of(LocalDateTime.now().minusMinutes(10)));

            OutboxLagResponseDTO result = service.getOutboxLag();

            assertThat(result.oldestPendingAgeSeconds()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("reportedAt is populated")
        void reportedAtIsPopulated() {
            when(outboxEventRepository.countByStatus(any())).thenReturn(0L);
            when(outboxEventRepository.countActiveByEventType(any())).thenReturn(List.of());

            assertThat(service.getOutboxLag().reportedAt()).isNotNull();
        }
    }
}
