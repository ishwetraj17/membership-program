package com.firstclub.platform.ops.service.impl;

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
import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.ops.dto.ScalingReadinessDTO;
import com.firstclub.platform.ops.dto.SystemSummaryDTO;
import com.firstclub.platform.ops.repository.FeatureFlagRepository;
import com.firstclub.platform.ops.repository.JobLockRepository;
import com.firstclub.platform.ops.service.DeepHealthService;
import com.firstclub.platform.ratelimit.RedisSlidingWindowRateLimiter;
import com.firstclub.platform.redis.RedisAvailabilityService;
import com.firstclub.platform.redis.RedisStatus;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.repository.ReconMismatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepHealthServiceImpl implements DeepHealthService {

    private final OutboxEventRepository                  outboxEventRepository;
    private final DeadLetterMessageRepository            deadLetterMessageRepository;
    private final MerchantWebhookDeliveryRepository      webhookDeliveryRepository;
    private final RevenueRecognitionScheduleRepository   revRecogRepository;
    private final ReconMismatchRepository                reconMismatchRepository;
    private final FeatureFlagRepository                  featureFlagRepository;
    private final RedisAvailabilityService               redisAvailabilityService;
    private final RedisSlidingWindowRateLimiter          rateLimiter;
    private final DunningAttemptRepository               dunningAttemptRepository;
    private final IntegrityCheckRunRepository            integrityCheckRunRepository;
    private final IntegrityCheckFindingRepository        integrityCheckFindingRepository;
    private final JobLockRepository                      jobLockRepository;

    @Override
    @Transactional(readOnly = true)
    public DeepHealthResponseDTO buildDeepHealthReport() {
        boolean dbReachable = true;
        long outboxPending = 0, outboxFailed = 0, dlqCount = 0;
        long webhookPending = 0, webhookFailed = 0;
        long revFailed = 0, reconOpen = 0, flagCount = 0;
        long rlBlocks = 0;
        long dunningBacklog = 0, integrityViolations = 0;
        String integrityLastStatus = "NONE";

        try {
            outboxPending        = outboxEventRepository.countByStatus(OutboxEventStatus.NEW)
                                   + outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING);
            outboxFailed         = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
            dlqCount             = deadLetterMessageRepository.count();
            webhookPending       = webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.PENDING);
            webhookFailed        = webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.FAILED);
            revFailed            = revRecogRepository.countByStatus(RevenueRecognitionStatus.FAILED);
            reconOpen            = reconMismatchRepository.countByStatus(ReconMismatchStatus.OPEN);
            flagCount            = featureFlagRepository.count();
            rlBlocks             = rateLimiter.getBlocksLastHour();
            dunningBacklog       = dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED);
            integrityViolations  = integrityCheckFindingRepository.countByStatus("FAIL");
            integrityLastStatus  = integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()
                                       .map(IntegrityCheckRun::getStatus).orElse("NONE");
        } catch (Exception e) {
            log.error("Deep health check: database query failed — {}", e.getMessage());
            dbReachable = false;
        }

        RedisStatus redisStatus = redisAvailabilityService.getStatus();
        String status = computeStatus(dbReachable, outboxFailed, dlqCount, webhookFailed,
                                      revFailed, integrityViolations, redisStatus);

        return new DeepHealthResponseDTO(
                status,
                dbReachable,
                outboxPending,
                outboxFailed,
                dlqCount,
                webhookPending,
                webhookFailed,
                revFailed,
                reconOpen,
                dunningBacklog,
                integrityViolations,
                integrityLastStatus,
                flagCount,
                "UNCHECKED",   // ledger double-entry invariant — see ops-runbook.md
                redisStatus.name(),
                rlBlocks,
                LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public SystemSummaryDTO buildSystemSummary() {
        long outboxPending      = outboxEventRepository.countByStatus(OutboxEventStatus.NEW)
                                  + outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING);
        long outboxFailed       = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);
        long dlqCount           = deadLetterMessageRepository.count();
        long webhookPending     = webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.PENDING);
        long webhookFailed      = webhookDeliveryRepository.countByStatus(MerchantWebhookDeliveryStatus.FAILED);
        long reconOpen          = reconMismatchRepository.countByStatus(ReconMismatchStatus.OPEN);
        long dunningBacklog     = dunningAttemptRepository.countByStatus(DunningAttempt.DunningStatus.SCHEDULED);
        long integrityViolations = integrityCheckFindingRepository.countByStatus("FAIL");
        String integrityLastStatus = integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()
                                        .map(IntegrityCheckRun::getStatus).orElse("NONE");
        LocalDateTime integrityLastRunAt = integrityCheckRunRepository.findFirstByOrderByStartedAtDesc()
                                        .map(IntegrityCheckRun::getStartedAt).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        long staleJobLocks = jobLockRepository.findAllByOrderByJobNameAsc().stream()
                .filter(j -> j.getLockedUntil() != null && j.getLockedUntil().isBefore(now))
                .count();

        return new SystemSummaryDTO(
                outboxPending,
                outboxFailed,
                dlqCount,
                webhookPending,
                webhookFailed,
                reconOpen,
                dunningBacklog,
                staleJobLocks,
                integrityViolations,
                integrityLastStatus,
                integrityLastRunAt,
                now);
    }

    @Override
    public ScalingReadinessDTO buildScalingReadiness() {
        return new ScalingReadinessDTO(
                "MODULAR_MONOLITH",
                List.of(
                        "Single PostgreSQL write node — all transactional writes serialized",
                        "Outbox polling loop — throughput bounded by polling interval",
                        "Synchronous webhook delivery fan-out under high merchant count",
                        "In-process job scheduler — no horizontal scale of background jobs",
                        "Redis single instance — rate-limit and idempotency store SPOF",
                        "Revenue recognition scheduled-event fan-out at billing cycle peaks"
                ),
                List.of(
                        "billing.cycle — projection rebuilt from subscription events",
                        "recon.mismatch — projection rebuilt from payment and charge events",
                        "ledger.revenue — projection rebuilt from subscription lifecycle events",
                        "reporting.mrr — rebuilt from billing cycle projections",
                        "risk.score — rebuilt from payment and chargeback events",
                        "dunning.attempt — rebuilt from subscription and payment events"
                ),
                List.of(
                        "rate-limiting — Redis sliding window per policy",
                        "idempotency — Redis key store with TTL",
                        "feature-flags — Redis-backed toggle cache"
                ),
                List.of(
                        "Single PostgreSQL instance — no read replicas, no standby failover",
                        "Embedded Quartz scheduler — cannot distribute job execution",
                        "In-memory rate-limit fallback — metrics lost on pod restart",
                        "Single outbox dispatcher thread — backpressure under burst load"
                ),
                List.of(
                        "dunning — high-frequency retry loop, natural microservice boundary",
                        "notifications/webhooks — stateless fan-out, independently scalable",
                        "reporting — read-only aggregation, suitable for read replica routing",
                        "risk — event-driven scoring engine, decouples from write path"
                ),
                Map.of(
                        "stage_1", "CURRENT — Modular monolith, single PostgreSQL, embedded scheduler",
                        "stage_2", "Add PostgreSQL read replicas; route reporting queries to replica",
                        "stage_3", "Extract dunning and notification as independent deployable services",
                        "stage_4", "Introduce message broker (Kafka/SQS); replace outbox polling with event streaming",
                        "stage_5", "Shard PostgreSQL by merchant_id; distribute job scheduler (Quartz cluster mode)",
                        "stage_6", "Full service mesh; per-service data stores; CQRS with event-sourced projections"
                ),
                LocalDateTime.now());
    }

    private static String computeStatus(boolean db, long outboxFailed, long dlq,
                                        long webhookFailed, long revFailed,
                                        long integrityViolations,
                                        RedisStatus redisStatus) {
        if (!db) return "DOWN";
        if (outboxFailed > 0 || dlq > 0 || webhookFailed > 0 || revFailed > 0
                || integrityViolations > 0) return "DEGRADED";
        if (redisStatus == RedisStatus.DOWN) return "DEGRADED";
        return "HEALTHY";
    }
}
