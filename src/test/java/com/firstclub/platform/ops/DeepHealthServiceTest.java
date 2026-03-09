package com.firstclub.platform.ops;

import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.platform.integrity.repository.IntegrityCheckFindingRepository;
import com.firstclub.platform.integrity.repository.IntegrityCheckRunRepository;
import com.firstclub.platform.ops.repository.JobLockRepository;
import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.ops.repository.FeatureFlagRepository;
import com.firstclub.platform.ops.service.impl.DeepHealthServiceImpl;
import com.firstclub.platform.ratelimit.RedisSlidingWindowRateLimiter;
import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisStatus;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.repository.ReconMismatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeepHealthService — Unit Tests")
class DeepHealthServiceTest {

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
        when(outboxEventRepository.countByStatus(OutboxEventStatus.NEW)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING)).thenReturn(0L);
        when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(0L);
        when(deadLetterMessageRepository.count()).thenReturn(0L);
        when(webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.PENDING)).thenReturn(0L);
        when(webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.FAILED)).thenReturn(0L);
        when(revRecogRepository.countByStatus(RevenueRecognitionStatus.FAILED)).thenReturn(0L);
        when(reconMismatchRepository.countByStatus(ReconMismatchStatus.OPEN)).thenReturn(0L);
        when(featureFlagRepository.count()).thenReturn(0L);
        when(redisAvailabilityService.getStatus()).thenReturn(RedisStatus.DISABLED);
        lenient().when(rateLimiter.getBlocksLastHour()).thenReturn(0L);
        lenient().when(dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED)).thenReturn(0L);
        lenient().when(integrityCheckFindingRepository.countByStatus("FAIL")).thenReturn(0L);
        lenient().when(integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        lenient().when(jobLockRepository.findAllByOrderByJobNameAsc()).thenReturn(List.of());
    }

    @Nested
    @DisplayName("buildDeepHealthReport")
    class BuildReport {

        @Test
        @DisplayName("HEALTHY when all counters are zero")
        void healthy_whenAllCountsZero() {
            stubAllZero();

            DeepHealthResponseDTO report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("HEALTHY");
            assertThat(report.dbReachable()).isTrue();
            assertThat(report.outboxFailedCount()).isZero();
            assertThat(report.dlqCount()).isZero();
        }

        @Test
        @DisplayName("DEGRADED when DLQ has entries")
        void degraded_whenDlqHasEntries() {
            stubAllZero();
            when(deadLetterMessageRepository.count()).thenReturn(3L);

            DeepHealthResponseDTO report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("DEGRADED");
            assertThat(report.dlqCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("DEGRADED when outbox has failures")
        void degraded_whenOutboxHasFailures() {
            stubAllZero();
            when(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).thenReturn(5L);

            DeepHealthResponseDTO report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("DEGRADED");
            assertThat(report.outboxFailedCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("DEGRADED when Redis is DOWN but DB is reachable")
        void degraded_whenRedisDown() {
            stubAllZero();
            when(redisAvailabilityService.getStatus()).thenReturn(RedisStatus.DOWN);

            DeepHealthResponseDTO report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("DEGRADED");
            assertThat(report.redisStatus()).isEqualTo("DOWN");
            assertThat(report.dbReachable()).isTrue();
        }

        @Test
        @DisplayName("DOWN when database throws exception")
        void down_whenDatabaseThrows() {
            when(outboxEventRepository.countByStatus(any())).thenThrow(new RuntimeException("DB gone"));
            when(redisAvailabilityService.getStatus()).thenReturn(RedisStatus.DISABLED);

            DeepHealthResponseDTO report = service.buildDeepHealthReport();

            assertThat(report.overallStatus()).isEqualTo("DOWN");
            assertThat(report.dbReachable()).isFalse();
        }

        @Test
        @DisplayName("all fields are populated in healthy report")
        void allFieldsPopulated() {
            stubAllZero();
            when(featureFlagRepository.count()).thenReturn(4L);
            when(reconMismatchRepository.countByStatus(ReconMismatchStatus.OPEN)).thenReturn(2L);

            DeepHealthResponseDTO report = service.buildDeepHealthReport();

            assertThat(report.featureFlagCount()).isEqualTo(4L);
            assertThat(report.reconMismatchOpenCount()).isEqualTo(2L);
            assertThat(report.ledgerStatus()).isEqualTo("UNCHECKED");
            assertThat(report.redisStatus()).isEqualTo("DISABLED");
            assertThat(report.checkedAt()).isNotNull();
        }
    }
}
