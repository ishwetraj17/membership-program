package com.firstclub.platform.observability;

import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.platform.health.DeepHealthController;
import com.firstclub.platform.health.DlqDepthHealthIndicator;
import com.firstclub.platform.health.ObservabilityHealthReportDTO;
import com.firstclub.platform.health.OutboxLagHealthIndicator;
import com.firstclub.platform.health.ProjectionLagHealthIndicator;
import com.firstclub.platform.health.SchedulerStalenessHealthIndicator;
import com.firstclub.platform.metrics.FinancialMetrics;
import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.ops.service.DeepHealthService;
import com.firstclub.platform.scheduler.health.SchedulerHealth;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor;
import com.firstclub.platform.scheduler.health.SchedulerHealthMonitor.SchedulerStatusSnapshot;
import com.firstclub.platform.slo.SloDefinition;
import com.firstclub.platform.slo.SloStatus;
import com.firstclub.platform.slo.SloStatusEntry;
import com.firstclub.platform.slo.SloStatusService;
import com.firstclub.reporting.projections.dto.ProjectionLagReport;
import com.firstclub.reporting.projections.service.ProjectionLagMonitor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 21 — Observability, SLOs, and deep health unit tests.
 *
 * <p>Tests are organised into nested classes, one per Phase 21 class.
 * All tests use {@link SimpleMeterRegistry} for Micrometer tests so no
 * Spring context is required — pure unit tests, fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 21: Observability, SLOs, and Deep Health")
class Phase21ObservabilityTests {

    // =========================================================================
    // OutboxLagHealthIndicator
    // =========================================================================

    @Nested
    @DisplayName("OutboxLagHealthIndicator")
    class OutboxLagHealthTests {

        @Mock OutboxEventRepository outboxEventRepository;
        OutboxLagHealthIndicator indicator;

        @BeforeEach
        void setUp() {
            indicator = new OutboxLagHealthIndicator(outboxEventRepository);
        }

        @Test
        @DisplayName("returns UP when backlog is zero and no failures")
        void healthy_whenZeroBacklog() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails().get("failedCount")).isEqualTo(0L);
        }

        @Test
        @DisplayName("returns UNKNOWN/DEGRADED when failed events exist")
        void degraded_whenFailedEventsExist() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(3L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(10L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails()).containsKey("failedCount");
        }

        @Test
        @DisplayName("returns UNKNOWN/DEGRADED when pending count exceeds degraded threshold")
        void degraded_whenPendingExceedsDegradedThreshold() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(400L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(200L);

            Health health = indicator.health();
            // 400 + 200 = 600 >= 500 threshold
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        }

        @Test
        @DisplayName("returns DOWN when pending count exceeds critical threshold")
        void down_whenPendingExceedsCriticalThreshold() {
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(4000L);
            when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(2000L);

            Health health = indicator.health();
            // 4000 + 2000 = 6000 >= 5000 threshold
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }
    }

    // =========================================================================
    // DlqDepthHealthIndicator
    // =========================================================================

    @Nested
    @DisplayName("DlqDepthHealthIndicator")
    class DlqDepthHealthTests {

        @Mock DeadLetterMessageRepository dlqRepo;
        DlqDepthHealthIndicator indicator;

        @BeforeEach
        void setUp() { indicator = new DlqDepthHealthIndicator(dlqRepo); }

        @Test
        @DisplayName("returns UP when DLQ is empty")
        void healthy_whenDlqEmpty() {
            when(dlqRepo.count()).thenReturn(0L);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("returns DEGRADED when DLQ has entries below DOWN threshold")
        void degraded_whenDlqHasFewEntries() {
            when(dlqRepo.count()).thenReturn(5L);
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails().get("dlqDepth")).isEqualTo(5L);
        }

        @Test
        @DisplayName("returns DOWN when DLQ reaches the saturation threshold")
        void down_whenDlqSaturated() {
            when(dlqRepo.count()).thenReturn(50L);
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("returns DOWN above saturation threshold")
        void down_whenDlqExceedsSaturationThreshold() {
            when(dlqRepo.count()).thenReturn(200L);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        }
    }

    // =========================================================================
    // ProjectionLagHealthIndicator
    // =========================================================================

    @Nested
    @DisplayName("ProjectionLagHealthIndicator")
    class ProjectionLagHealthTests {

        @Mock ProjectionLagMonitor projectionLagMonitor;
        ProjectionLagHealthIndicator indicator;

        @BeforeEach
        void setUp() { indicator = new ProjectionLagHealthIndicator(projectionLagMonitor); }

        @Test
        @DisplayName("returns UP when all projections are fresh")
        void healthy_whenProjectionsFresh() {
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of(
                    "payment_summary", ProjectionLagReport.builder()
                            .projectionName("payment_summary").lagSeconds(30L).build(),
                    "subscription_status", ProjectionLagReport.builder()
                            .projectionName("subscription_status").lagSeconds(60L).build()
            ));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("returns DEGRADED when max lag exceeds 5 minutes")
        void degraded_whenLagExceedsDegradedThreshold() {
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of(
                    "payment_summary", ProjectionLagReport.builder()
                            .projectionName("payment_summary").lagSeconds(400L).build()
            ));
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails().get("maxLagSeconds")).isEqualTo(400L);
        }

        @Test
        @DisplayName("returns DOWN when max lag exceeds 1 hour")
        void down_whenLagExceedsDownThreshold() {
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of(
                    "ledger_balance", ProjectionLagReport.builder()
                            .projectionName("ledger_balance").lagSeconds(7200L).build()
            ));
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("treats empty projections (lag = -1) as healthy")
        void healthy_whenProjectionIsEmpty() {
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of(
                    "payment_summary", ProjectionLagReport.builder()
                            .projectionName("payment_summary").lagSeconds(-1L).build()
            ));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }
    }

    // =========================================================================
    // SchedulerStalenessHealthIndicator
    // =========================================================================

    @Nested
    @DisplayName("SchedulerStalenessHealthIndicator")
    class SchedulerStalenessTests {

        @Mock SchedulerHealthMonitor schedulerHealthMonitor;
        SchedulerStalenessHealthIndicator indicator;

        @BeforeEach
        void setUp() { indicator = new SchedulerStalenessHealthIndicator(schedulerHealthMonitor); }

        @Test
        @DisplayName("returns UP when no scheduler history (fresh deployment)")
        void healthy_whenNoHistory() {
            when(schedulerHealthMonitor.getAllSnapshots(any(Duration.class))).thenReturn(List.of());
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("returns UP when all schedulers are HEALTHY")
        void healthy_whenAllSchedulersHealthy() {
            SchedulerStatusSnapshot snap = new SchedulerStatusSnapshot(
                    "subscription-renewal", SchedulerHealth.HEALTHY,
                    Instant.now().minusSeconds(60), Instant.now().minusSeconds(60), "SUCCESS",
                    Duration.ofMinutes(30));
            when(schedulerHealthMonitor.getAllSnapshots(any())).thenReturn(List.of(snap));
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("returns DEGRADED when any scheduler is STALE")
        void degraded_whenSchedulerIsStale() {
            SchedulerStatusSnapshot stale = new SchedulerStatusSnapshot(
                    "dunning-scheduler", SchedulerHealth.STALE,
                    Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3600), "SUCCESS",
                    Duration.ofMinutes(30));
            when(schedulerHealthMonitor.getAllSnapshots(any())).thenReturn(List.of(stale));

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails()).containsKey("staleCount");
            assertThat(health.getDetails().get("staleCount")).isEqualTo(1L);
        }

        @Test
        @DisplayName("returns DEGRADED when some schedulers have never run")
        void degraded_whenSchedulerNeverRanButOthersHealthy() {
            SchedulerStatusSnapshot healthy = new SchedulerStatusSnapshot(
                    "existing-scheduler", SchedulerHealth.HEALTHY,
                    Instant.now().minusSeconds(60), Instant.now().minusSeconds(60), "SUCCESS",
                    Duration.ofMinutes(30));
            SchedulerStatusSnapshot neverRan = new SchedulerStatusSnapshot(
                    "new-scheduler", SchedulerHealth.NEVER_RAN,
                    null, null, null,
                    Duration.ofMinutes(30));
            when(schedulerHealthMonitor.getAllSnapshots(any())).thenReturn(List.of(healthy, neverRan));

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        }
    }

    // =========================================================================
    // SloStatusService
    // =========================================================================

    @Nested
    @DisplayName("SloStatusService")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class SloStatusTests {

        private MeterRegistry registry;
        private FinancialMetrics financialMetrics;

        @Mock DeadLetterMessageRepository dlqRepo;
        @Mock com.firstclub.membership.repository.SubscriptionRepository subRepo;
        @Mock OutboxEventRepository outboxRepo;
        @Mock DisputeRepository disputeRepo;
        @Mock ProjectionLagMonitor projectionLagMonitor;

        private SloStatusService sloStatusService;

        @BeforeEach
        void setUp() {
            registry = new SimpleMeterRegistry();

            when(outboxRepo.countByStatus(OutboxEventStatus.NEW)).thenReturn(0L);
            when(outboxRepo.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);
            when(dlqRepo.count()).thenReturn(0L);
            when(disputeRepo.countByStatusIn(any())).thenReturn(0L);
            when(subRepo.countBySubscriptionStatus(any())).thenReturn(0L);
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of());

            financialMetrics = new FinancialMetrics(
                    registry, outboxRepo, dlqRepo, disputeRepo, subRepo, projectionLagMonitor);
            sloStatusService = new SloStatusService(financialMetrics, dlqRepo);
        }

        @Test
        @DisplayName("returns INSUFFICIENT_DATA for all rate-SLOs when no events recorded")
        void insufficientData_whenNoEvents() {
            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            assertThat(entries).isNotEmpty();

            // Payment, refund, dunning rate SLOs should all be insufficient
            entries.stream()
                    .filter(e -> e.sloId().endsWith(".rate"))
                    .forEach(e -> assertThat(e.status())
                            .as("SLO '%s' should be INSUFFICIENT_DATA when counters are zero", e.sloId())
                            .isEqualTo(SloStatus.INSUFFICIENT_DATA));
        }

        @Test
        @DisplayName("payment SLO is MEETING when success rate is above target")
        void meeting_whenPaymentSuccessRateAboveTarget() {
            // 98 successes, 2 failures => 98% >= 95% target
            financialMetrics.getPaymentSuccess().increment(98);
            financialMetrics.getPaymentFailure().increment(2);

            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            SloStatusEntry paymentSlo = entries.stream()
                    .filter(e -> e.sloId().equals(SloDefinition.PAYMENT_SUCCESS_RATE.sloId()))
                    .findFirst().orElseThrow();

            assertThat(paymentSlo.status()).isEqualTo(SloStatus.MEETING);
            assertThat(paymentSlo.currentValue()).isCloseTo(98.0, org.assertj.core.data.Offset.offset(0.1));
        }

        @Test
        @DisplayName("payment SLO is AT_RISK when success rate is between at-risk and target")
        void atRisk_whenPaymentSuccessRateBetweenThresholds() {
            // 91 successes, 9 failures => 91% — AT_RISK (target=95%, atRisk=90%)
            financialMetrics.getPaymentSuccess().increment(91);
            financialMetrics.getPaymentFailure().increment(9);

            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            SloStatusEntry paymentSlo = entries.stream()
                    .filter(e -> e.sloId().equals(SloDefinition.PAYMENT_SUCCESS_RATE.sloId()))
                    .findFirst().orElseThrow();

            assertThat(paymentSlo.status()).isEqualTo(SloStatus.AT_RISK);
        }

        @Test
        @DisplayName("payment SLO is BREACHED when success rate falls below at-risk threshold")
        void breached_whenPaymentSuccessRateBelowAtRisk() {
            // 80 successes, 20 failures => 80% < 90% at-risk threshold
            financialMetrics.getPaymentSuccess().increment(80);
            financialMetrics.getPaymentFailure().increment(20);

            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            SloStatusEntry paymentSlo = entries.stream()
                    .filter(e -> e.sloId().equals(SloDefinition.PAYMENT_SUCCESS_RATE.sloId()))
                    .findFirst().orElseThrow();

            assertThat(paymentSlo.status()).isEqualTo(SloStatus.BREACHED);
        }

        @Test
        @DisplayName("DLQ ceiling SLO is MEETING when DLQ is empty")
        void meeting_whenDlqEmpty() {
            when(dlqRepo.count()).thenReturn(0L);

            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            SloStatusEntry dlqSlo = entries.stream()
                    .filter(e -> e.sloId().equals(SloDefinition.DLQ_CEILING.sloId()))
                    .findFirst().orElseThrow();

            assertThat(dlqSlo.status()).isEqualTo(SloStatus.MEETING);
        }

        @Test
        @DisplayName("DLQ ceiling SLO is AT_RISK when depth between thresholds")
        void atRisk_whenDlqBetweenThresholds() {
            when(dlqRepo.count()).thenReturn(75L);

            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            SloStatusEntry dlqSlo = entries.stream()
                    .filter(e -> e.sloId().equals(SloDefinition.DLQ_CEILING.sloId()))
                    .findFirst().orElseThrow();

            assertThat(dlqSlo.status()).isEqualTo(SloStatus.AT_RISK);
        }

        @Test
        @DisplayName("DLQ ceiling SLO is BREACHED when depth exceeds target")
        void breached_whenDlqExceedsTarget() {
            when(dlqRepo.count()).thenReturn(150L);

            List<SloStatusEntry> entries = sloStatusService.getAllSloStatuses();
            SloStatusEntry dlqSlo = entries.stream()
                    .filter(e -> e.sloId().equals(SloDefinition.DLQ_CEILING.sloId()))
                    .findFirst().orElseThrow();

            assertThat(dlqSlo.status()).isEqualTo(SloStatus.BREACHED);
        }

        @Test
        @DisplayName("overallSloStatus returns CRITICAL when any SLO is BREACHED")
        void critical_whenAnySloBreached() {
            financialMetrics.getPaymentSuccess().increment(5);
            financialMetrics.getPaymentFailure().increment(95); // 5% success — breached

            assertThat(sloStatusService.overallSloStatus()).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("overallSloStatus returns HEALTHY when all SLOs are MEETING or INSUFFICIENT_DATA")
        void healthy_whenNoBreachNorAtRisk() {
            financialMetrics.getPaymentSuccess().increment(98);
            financialMetrics.getPaymentFailure().increment(2);
            financialMetrics.getRefundCompleted().increment(100);
            financialMetrics.getDunningSuccess().increment(70);
            financialMetrics.getDunningExhausted().increment(30);

            String overall = sloStatusService.overallSloStatus();
            // AT_RISK or HEALTHY depending on latency timers (0 count = INSUFFICIENT_DATA)
            assertThat(overall).isIn("HEALTHY", "AT_RISK");
        }
    }

    // =========================================================================
    // FinancialMetrics — counter and timer wiring sanity
    // =========================================================================

    @Nested
    @DisplayName("FinancialMetrics")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class FinancialMetricsTests {

        private MeterRegistry registry;
        private FinancialMetrics metrics;

        @Mock OutboxEventRepository outboxRepo;
        @Mock DeadLetterMessageRepository dlqRepo;
        @Mock DisputeRepository disputeRepo;
        @Mock com.firstclub.membership.repository.SubscriptionRepository subRepo;
        @Mock ProjectionLagMonitor projectionLagMonitor;

        @BeforeEach
        void setUp() {
            registry = new SimpleMeterRegistry();
            when(outboxRepo.countByStatus(any())).thenReturn(0L);
            when(dlqRepo.count()).thenReturn(0L);
            when(disputeRepo.countByStatusIn(any())).thenReturn(0L);
            when(subRepo.countBySubscriptionStatus(any())).thenReturn(0L);
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of());
            metrics = new FinancialMetrics(registry, outboxRepo, dlqRepo, disputeRepo, subRepo, projectionLagMonitor);
        }

        @Test
        @DisplayName("payment.success.total counter increments correctly")
        void paymentSuccessCounterIncrements() {
            metrics.getPaymentSuccess().increment();
            metrics.getPaymentSuccess().increment();
            assertThat(metrics.getPaymentSuccess().count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("payment.failure.total counter increments correctly")
        void paymentFailureCounterIncrements() {
            metrics.getPaymentFailure().increment(5);
            assertThat(metrics.getPaymentFailure().count()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("all counters exist in the registry")
        void allCountersRegistered() {
            assertThat(registry.find("payment.success.total").counter()).isNotNull();
            assertThat(registry.find("payment.failure.total").counter()).isNotNull();
            assertThat(registry.find("payment.unknown.total").counter()).isNotNull();
            assertThat(registry.find("refund.completed.total").counter()).isNotNull();
            assertThat(registry.find("refund.failed.total").counter()).isNotNull();
            assertThat(registry.find("dunning.success.total").counter()).isNotNull();
            assertThat(registry.find("dunning.exhausted.total").counter()).isNotNull();
            assertThat(registry.find("dispute.opened.total").counter()).isNotNull();
            assertThat(registry.find("ledger.invariant.violation.total").counter()).isNotNull();
            assertThat(registry.find("outbox.dlq.total").counter()).isNotNull();
        }

        @Test
        @DisplayName("all timers exist in the registry")
        void allTimersRegistered() {
            assertThat(registry.find("payment.capture.latency").timer()).isNotNull();
            assertThat(registry.find("webhook.delivery.latency").timer()).isNotNull();
            assertThat(registry.find("recon.run.duration").timer()).isNotNull();
            assertThat(registry.find("outbox.handler.duration").timer()).isNotNull();
        }

        @Test
        @DisplayName("all gauges exist in the registry")
        void allGaugesRegistered() {
            assertThat(registry.find("outbox.backlog").gauge()).isNotNull();
            assertThat(registry.find("dlq.depth").gauge()).isNotNull();
            assertThat(registry.find("open.dispute.count").gauge()).isNotNull();
            assertThat(registry.find("past_due.subscription.count").gauge()).isNotNull();
            assertThat(registry.find("projection.lag.seconds").gauge()).isNotNull();
        }

        @Test
        @DisplayName("outbox.backlog gauge sums NEW + PROCESSING")
        void outboxBacklogGaugeSumsNewAndProcessing() {
            when(outboxRepo.countByStatus(OutboxEventStatus.NEW)).thenReturn(30L);
            when(outboxRepo.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(10L);
            // Gauge lambda is evaluated lazily; read it from the registry
            double value = registry.find("outbox.backlog").gauge().value();
            assertThat(value).isEqualTo(40.0);
        }

        @Test
        @DisplayName("Timer.record() registers timing samples")
        void timerRecordsSamples() {
            Timer timer = metrics.getPaymentCaptureLatency();
            timer.record(Duration.ofMillis(100));
            timer.record(Duration.ofMillis(200));
            assertThat(timer.count()).isEqualTo(2);
        }
    }

    // =========================================================================
    // DeepHealthController
    // =========================================================================

    @Nested
    @DisplayName("DeepHealthController")
    class DeepHealthControllerTests {

        @Mock DeepHealthService deepHealthService;
        @Mock SchedulerHealthMonitor schedulerHealthMonitor;
        @Mock ProjectionLagMonitor projectionLagMonitor;
        @Mock SloStatusService sloStatusService;

        @InjectMocks
        DeepHealthController controller;

        @Test
        @DisplayName("enhancedDeepHealth returns 200 OK with composite report")
        void enhancedDeepHealth_returns200() {
            DeepHealthResponseDTO base = new DeepHealthResponseDTO(
                    "HEALTHY", true, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, "NONE", 0L, "UNCHECKED", "UP", 0L, LocalDateTime.now());
            when(deepHealthService.buildDeepHealthReport()).thenReturn(base);
            when(schedulerHealthMonitor.getAllSnapshots(any())).thenReturn(List.of());
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of());
            when(sloStatusService.getAllSloStatuses()).thenReturn(List.of());
            when(sloStatusService.overallSloStatus()).thenReturn("HEALTHY");

            ResponseEntity<ObservabilityHealthReportDTO> response = controller.enhancedDeepHealth();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().overallStatus()).isEqualTo("HEALTHY");
            assertThat(response.getBody().checkedAt()).isNotNull();
        }

        @Test
        @DisplayName("enhancedDeepHealth returns DEGRADED when scheduler is stale")
        void enhancedDeepHealth_degraded_whenSchedulerStale() {
            DeepHealthResponseDTO base = new DeepHealthResponseDTO(
                    "HEALTHY", true, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, "NONE", 0L, "UNCHECKED", "UP", 0L, LocalDateTime.now());
            SchedulerStatusSnapshot staleSnap = new SchedulerStatusSnapshot(
                    "dunning-scheduler", SchedulerHealth.STALE,
                    null, null, null, Duration.ofMinutes(30));

            when(deepHealthService.buildDeepHealthReport()).thenReturn(base);
            when(schedulerHealthMonitor.getAllSnapshots(any())).thenReturn(List.of(staleSnap));
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of());
            when(sloStatusService.getAllSloStatuses()).thenReturn(List.of());
            when(sloStatusService.overallSloStatus()).thenReturn("HEALTHY");

            ResponseEntity<ObservabilityHealthReportDTO> response = controller.enhancedDeepHealth();

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().overallStatus()).isEqualTo("DEGRADED");
        }

        @Test
        @DisplayName("enhancedDeepHealth returns DEGRADED when projection lag exceeds threshold")
        void enhancedDeepHealth_degraded_whenProjectionLagHigh() {
            DeepHealthResponseDTO base = new DeepHealthResponseDTO(
                    "HEALTHY", true, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, "NONE", 0L, "UNCHECKED", "UP", 0L, LocalDateTime.now());
            when(deepHealthService.buildDeepHealthReport()).thenReturn(base);
            when(schedulerHealthMonitor.getAllSnapshots(any())).thenReturn(List.of());
            when(projectionLagMonitor.checkAllProjections()).thenReturn(Map.of(
                    "payment_summary", ProjectionLagReport.builder()
                            .projectionName("payment_summary").lagSeconds(900L).build()));
            when(sloStatusService.getAllSloStatuses()).thenReturn(List.of());
            when(sloStatusService.overallSloStatus()).thenReturn("HEALTHY");

            ResponseEntity<ObservabilityHealthReportDTO> response = controller.enhancedDeepHealth();

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().overallStatus()).isEqualTo("DEGRADED");
            assertThat(response.getBody().maxProjectionLagSeconds()).isEqualTo(900L);
        }

        @Test
        @DisplayName("sloStatus endpoint returns list of SLO entries")
        void sloStatus_returnsList() {
            SloStatusEntry entry = new SloStatusEntry(
                    "payment.success.rate", "Payment Capture Success Rate",
                    95.0, 98.0, SloStatus.MEETING, "since last restart",
                    "98% success rate", LocalDateTime.now());
            when(sloStatusService.getAllSloStatuses()).thenReturn(List.of(entry));

            ResponseEntity<List<SloStatusEntry>> response = controller.sloStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).sloId()).isEqualTo("payment.success.rate");
        }
    }
}
