package com.firstclub.platform.idempotency.service;

import com.firstclub.platform.idempotency.IdempotencyKeyEntity;
import com.firstclub.platform.idempotency.IdempotencyKeyRepository;
import com.firstclub.platform.idempotency.IdempotencyStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Database lifecycle manager for idempotency key records.
 *
 * <p>Creates records with {@link IdempotencyStatus#PROCESSING} and transitions
 * them through their lifecycle:
 * <pre>
 *   createPlaceholder → PROCESSING
 *   markCompleted     → COMPLETED
 *   markFailed(true)  → FAILED_RETRYABLE
 *   markFailed(false) → FAILED_FINAL
 *   resetStuck        → PROCESSING → FAILED_RETRYABLE  (scheduler)
 * </pre>
 *
 * <p>Lookup tries the Phase-4 {@code (merchant_id, idempotency_key)} index first
 * and falls back to the legacy composite-PK path for records created pre-V51.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyRecordService {

    private final IdempotencyKeyRepository repository;

    /**
     * Looks up an existing record.
     *
     * <p>Tries the new {@code (merchant_id, idempotency_key)} index first; falls
     * back to the legacy composite-PK path ({@code merchantId:rawKey}).
     */
    public Optional<IdempotencyKeyEntity> findByMerchantAndKey(String merchantId, String rawKey) {
        Optional<IdempotencyKeyEntity> result =
                repository.findByMerchantIdAndIdempotencyKey(merchantId, rawKey);
        if (result.isPresent()) return result;
        return repository.findByMerchantAndKey(merchantId, rawKey);
    }

    /**
     * Creates a {@link IdempotencyStatus#PROCESSING} placeholder.
     *
     * @param merchantId        authenticated merchant identifier
     * @param rawKey            client-supplied Idempotency-Key value
     * @param requestHash       SHA-256(method + path + body)
     * @param endpointSignature "{METHOD}:{url-template}"
     * @param requestId         tracing request ID (may be null)
     * @param correlationId     tracing correlation ID (may be null)
     * @param ttlHours          record TTL in hours
     */
    @Transactional
    public IdempotencyKeyEntity createPlaceholder(String merchantId, String rawKey,
                                                   String requestHash, String endpointSignature,
                                                   String requestId, String correlationId,
                                                   int ttlHours) {
        IdempotencyKeyEntity entity = IdempotencyKeyEntity.builder()
                .key(merchantId + ":" + rawKey)
                .idempotencyKey(rawKey)
                .merchantId(merchantId)
                .requestHash(requestHash)
                .endpointSignature(endpointSignature)
                .status(IdempotencyStatus.PROCESSING)
                .processingStartedAt(LocalDateTime.now())
                .requestId(requestId)
                .correlationId(correlationId)
                .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                .build();
        log.debug("Creating PROCESSING placeholder merchant={} key={}", merchantId, rawKey);
        return repository.save(entity);
    }

    /**
     * Transitions the record to {@link IdempotencyStatus#COMPLETED} and stores
     * the response payload for future replay.
     */
    @Transactional
    public void markCompleted(String merchantId, String rawKey,
                               int statusCode, String responseBody,
                               String owner, String contentType) {
        repository.findByMerchantAndKey(merchantId, rawKey).ifPresentOrElse(entity -> {
            entity.setStatus(IdempotencyStatus.COMPLETED);
            entity.setStatusCode(statusCode);
            entity.setResponseBody(responseBody);
            entity.setOwner(owner);
            entity.setContentType(contentType);
            entity.setCompletedAt(LocalDateTime.now());
            repository.save(entity);
            log.debug("Marked COMPLETED merchant={} key={} httpStatus={}", merchantId, rawKey, statusCode);
        }, () -> log.warn("markCompleted: no record found for merchant={} key={}", merchantId, rawKey));
    }

    /**
     * Transitions the record to a FAILED state.
     *
     * @param retryable if {@code true}, sets {@link IdempotencyStatus#FAILED_RETRYABLE}
     *                  (client may retry with the same key); if {@code false}, sets
     *                  {@link IdempotencyStatus#FAILED_FINAL}
     */
    @Transactional
    public void markFailed(String merchantId, String rawKey, boolean retryable) {
        repository.findByMerchantAndKey(merchantId, rawKey).ifPresent(entity -> {
            entity.setStatus(retryable
                    ? IdempotencyStatus.FAILED_RETRYABLE
                    : IdempotencyStatus.FAILED_FINAL);
            entity.setCompletedAt(LocalDateTime.now());
            repository.save(entity);
        });
    }

    /**
     * Resets PROCESSING records stuck longer than {@code threshold} to
     * {@link IdempotencyStatus#FAILED_RETRYABLE}, making them safe to retry.
     *
     * @param threshold minimum age of a PROCESSING record before it is considered stuck
     * @return number of records reset
     */
    @Transactional
    public int resetStuckProcessing(Duration threshold) {
        LocalDateTime cutoff = LocalDateTime.now().minus(threshold);
        int count = repository.resetStuckProcessing(
                IdempotencyStatus.PROCESSING,
                cutoff,
                IdempotencyStatus.FAILED_RETRYABLE,
                LocalDateTime.now());
        if (count > 0) {
            log.warn("Reset {} stuck PROCESSING record(s) older than {} min",
                    count, threshold.toMinutes());
        }
        return count;
    }
}
