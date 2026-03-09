package com.firstclub.platform.ops;

import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.platform.integrity.entity.IntegrityCheckRun;
import com.firstclub.platform.integrity.repository.IntegrityCheckFindingRepository;
import com.firstclub.platform.integrity.repository.IntegrityCheckRunRepository;
import com.firstclub.platform.ops.dto.ScalingReadinessDTO;
import com.firstclub.platform.ops.dto.SystemSummaryDTO;
import com.firstclub.platform.ops.entity.JobLock;
import com.firstclub.platform.ops.repository.FeatureFlagRepository;
import com.firstclub.platform.ops.repository.JobLockRepository;
import com.firstclub.platform.ops.service.impl.DeepHealthServiceImpl;
import com.firstclub.platform.ratelimit.RedisSlidingWindowRateLimiter;
import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisStatus;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.repository.ReconMismatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 20 — System Endpoints Unit Tests")
class Phase20SystemEndpointsTest {

    @Mock OutboxEventRepository                outboxEventRepository;
    @Mock DeadLetterMessageRepository          deadLetterMessageRepository;
    @Mock MerchantWebhookDeliveryRepository    webhookDeliveryRepository;
    @Mock RevenueRecognitionScheduleRepository revRecogRepository;
    @Mock ReconMismatchRepository              reconMismatchRepository;
    @Mock FeatureFlagRepository                featureFlagRepository;
    @Mock RedisAvailabilityService             redisAvailabilityService;
    @Mock RedisSlidingWindowRateLimiter        rateLimiter;
    @Mock DunningAttemptRepository             dunningAttemptRepository;
    @Mock IntegrityCheckFindingRepository      integrityCheckFindingRepository;
    @Mock IntegrityCheckRunRepository          integrityCheckRunRepository;
    @Mock JobLockRepository                    jobLockRepository;

    @InjectMocks DeepHealthServiceImpl service;

    private void stubAllZero() {
        lenient().when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(0L);
        lenient().when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);
        lenient().when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
        lenient().when(deadLetterMessageRepository.count()).thenReturn(0L);
        lenient().when(webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.PENDING)).thenReturn(0L);
        lenient().when(webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.FAILED)).thenReturn(0L);
        lenient().when(revRecogRepository.countByStatus(RevenueRecognitionStatus.FAILED)).thenReturn(0L);
        lenient().when(reconMismatchRepository.countByStatus(ReconMismatchStatus.OPEN)).thenReturn(0L);
        lenient().when(featureFlagRepository.count()).thenReturn(0L);
        lenient().when(redisAvailabilityService.getStatus()).thenReturn(RedisStatus.DISABLED);
        lenient().when(rateLimiter.getBlocksLastHour()).thenReturn(0L);
        lenient().when(dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED)).thenReturn(0L);
        lenient().when(integrityCheckFindingRepository.countByStatus("FAIL")).thenReturn(0L);
        lenient().when(integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        lenient().when(jobLockRepository.findAllByOrderByJobNameAsc()).thenReturn(List.of());
    }

    // ── buildSystemSummary ────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSystemSummary")
    class BuildSystemSummary {

        @Test
        @DisplayName("returns zero counts when no data exists")
        void allZeroWhenNoData() {
            stubAllZero();

            SystemSummaryDTO summary = service.buildSystemSummary();

            assertThat(summary.outboxPendingCount()).isZero();
            assertThat(summary.outboxFailedCount()).isZero();
            assertThat(summary.dlqCount()).isZero();
            assertThat(summary.webhookPendingCount()).isZero();
            assertThat(summary.webhookFailedCount()).isZero();
            assertThat(summary.reconMismatchOpenCount()).isZero();
            assertThat(summary.dunningBacklogCount()).isZero();
            assertThat(summary.staleJobLockCount()).isZero();
            assertThat(summary.integrityViolationCount()).isZero();
            assertThat(summary.integrityLastRunStatus()).isEqualTo("NONE");
            assertThat(summary.integrityLastRunAt()).isNull();
            assertThat(summary.generatedAt()).isNotNull();
        }

        @Test
        @DisplayName("reflects dunning backlog and integrity violations")
        void reflectsDunningAndIntegrity() {
            stubAllZero();
            when(dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED)).thenReturn(7L);
            when(integrityCheckFindingRepository.countByStatus("FAIL")).thenReturn(3L);

            SystemSummaryDTO summary = service.buildSystemSummary();

            assertThat(summary.dunningBacklogCount()).isEqualTo(7L);
            assertThat(summary.integrityViolationCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("reports stale job locks correctly")
        void reportsStaleJobLocks() {
            stubAllZero();
            JobLock stale = new JobLock();
            stale.setLockedUntil(LocalDateTime.now().minusMinutes(5));
            stale.setJobName("billing-cycle-job");
            JobLock fresh = new JobLock();
            fresh.setLockedUntil(LocalDateTime.now().plusMinutes(5));
            fresh.setJobName("dunning-job");
            when(jobLockRepository.findAllByOrderByJobNameAsc()).thenReturn(List.of(stale, fresh));

            SystemSummaryDTO summary = service.buildSystemSummary();

            assertThat(summary.staleJobLockCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("shows last integrity run status when run exists")
        void showsLastIntegrityRunStatus() {
            stubAllZero();
            IntegrityCheckRun run = new IntegrityCheckRun();
            run.setStatus("COMPLETED");
            run.setStartedAt(LocalDateTime.now().minusHours(2));
            when(integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(run));

            SystemSummaryDTO summary = service.buildSystemSummary();

            assertThat(summary.integrityLastRunStatus()).isEqualTo("COMPLETED");
            assertThat(summary.integrityLastRunAt()).isNotNull();
        }

        @Test
        @DisplayName("generatedAt is a recent timestamp")
        void generatedAtIsRecent() {
            stubAllZero();

            SystemSummaryDTO summary = service.buildSystemSummary();

            assertThat(summary.generatedAt()).isAfter(LocalDateTime.now().minusSeconds(5));
        }
    }

    // ── buildScalingReadiness ─────────────────────────────────────────────────

    @Nested
    @DisplayName("buildScalingReadiness")
    class BuildScalingReadiness {

        @Test
        @DisplayName("architecture shape is MODULAR_MONOLITH")
        void architectureShapeIsModularMonolith() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.architectureShape()).isEqualTo("MODULAR_MONOLITH");
        }

        @Test
        @DisplayName("bottlenecks list is non-empty")
        void bottlenecksNonEmpty() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.currentBottlenecks()).isNotEmpty();
        }

        @Test
        @DisplayName("evolution stages map contains stage_1 through stage_6")
        void evolutionStagesContainAllStages() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.evolutionStages()).containsKeys(
                    "stage_1", "stage_2", "stage_3", "stage_4", "stage_5", "stage_6");
        }

        @Test
        @DisplayName("stage_1 description contains CURRENT")
        void stage1ContainsCurrent() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.evolutionStages().get("stage_1")).contains("CURRENT");
        }

        @Test
        @DisplayName("single-node risks list is non-empty")
        void singleNodeRisksNonEmpty() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.singleNodeRisks()).isNotEmpty();
        }

        @Test
        @DisplayName("decomposition candidates are listed")
        void decompositionCandidatesListed() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.decompositionCandidates()).isNotEmpty();
        }

        @Test
        @DisplayName("projection-backed subsystems list is non-empty")
        void projectionBackedSubsystemsNonEmpty() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.projectionBackedSubsystems()).isNotEmpty();
        }

        @Test
        @DisplayName("generatedAt is a recent timestamp")
        void generatedAtIsRecent() {
            ScalingReadinessDTO dto = service.buildScalingReadiness();

            assertThat(dto.generatedAt()).isAfter(LocalDateTime.now().minusSeconds(5));
        }
    }

    // ── DeepHealth new fields ─────────────────────────────────────────────────

    @Nested
    @DisplayName("buildDeepHealthReport — Phase 20 fields")
    class DeepHealthNewFields {

        @Test
        @DisplayName("dunningBacklogCount is populated in health report")
        void dunningBacklogInHealthReport() {
            stubAllZero();
            when(dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED)).thenReturn(5L);

            var report = service.buildDeepHealthReport();

            assertThat(report.dunningBacklogCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("integrity violation count is populated in health report")
        void integrityViolationInHealthReport() {
            stubAllZero();
            when(integrityCheckFindingRepository.countByStatus("FAIL")).thenReturn(2L);

            var report = service.buildDeepHealthReport();

            assertThat(report.integrityViolationCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("DEGRADED when integrity violations exist")
        void degradedWhenIntegrityViolations() {
            stubAllZero();
            when(integrityCheckFindingRepository.countByStatus("FAIL")).thenReturn(1L);

            var report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("DEGRADED");
        }

        @Test
        @DisplayName("dunning backlog does NOT degrade overall status")
        void dunningBacklogDoesNotDegrade() {
            stubAllZero();
            when(dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED)).thenReturn(100L);

            var report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("HEALTHY");
            assertThat(report.dunningBacklogCount()).isEqualTo(100L);
        }

        @Test
        @DisplayName("integrityLastRunStatus is NONE when no run exists")
        void integrityLastRunStatusIsNoneWhenAbsent() {
            stubAllZero();

            var report = service.buildDeepHealthReport();

            assertThat(report.integrityLastRunStatus()).isEqualTo("NONE");
        }

        @Test
        @DisplayName("integrityLastRunStatus reflects last run status")
        void integrityLastRunStatusReflectsActualRun() {
            stubAllZero();
            IntegrityCheckRun run = new IntegrityCheckRun();
            run.setStatus("COMPLETED");
            run.setStartedAt(LocalDateTime.now().minusHours(1));
            when(integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(run));

            var report = service.buildDeepHealthReport();

            assertThat(report.integrityLastRunStatus()).isEqualTo("COMPLETED");
        }
    }
}
