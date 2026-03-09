package com.firstclub.platform.idempotency;

import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Manages the lifecycle of idempotency key records.
 *
 * <p>The filter calls this service in two phases:
 * <ol>
 *   <li><strong>Pre-request</strong>: {@link #findByMerchantAndKey} to check for an
 *       existing record, then {@link #createPlaceholder} if none exists.</li>
 *   <li><strong>Post-request</strong>: {@link #storeResponse} to persist the
 *       final HTTP status code and response body.</li>
 * </ol>
 *
 * <h3>Phase 3 — merchant scoping</h3>
 * <p>All methods accept a {@code merchantId} parameter.  Records are stored
 * under the composite PK {@code "{merchantId}:{rawKey}"} so that the same raw
 * idempotency key value can be used independently by different merchants.
 *
 * <h3>Phase 4 — status lifecycle</h3>
 * <p>Placeholders are created with {@link IdempotencyStatus#PROCESSING}.
 * {@link #storeResponse} marks the record {@link IdempotencyStatus#COMPLETED}.
 * {@link #markFailed} marks the record with the appropriate failure status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    /**
     * Looks up an existing idempotency record by merchant and raw key.
     *
     * @param merchantId authenticated merchant identifier
     * @param rawKey     client-supplied Idempotency-Key header value
     */
    public Optional<IdempotencyKeyEntity> findByMerchantAndKey(String merchantId, String rawKey) {
        return repository.findByMerchantAndKey(merchantId, rawKey);
    }

    /**
     * Creates a PROCESSING placeholder record for a new idempotency key.
     *
     * @param merchantId        authenticated merchant identifier
     * @param rawKey            client-supplied Idempotency-Key header value
     * @param requestHash       SHA-256 hash of the request
     * @param endpointSignature "{METHOD}:{url-template}" of the handler
     * @param ttlHours          hours before the record expires
     */
    @Transactional
    public IdempotencyKeyEntity createPlaceholder(String merchantId, String rawKey,
                                                   String requestHash, String endpointSignature,
                                                   int ttlHours) {
        String compositeKey = merchantId + ":" + rawKey;
        IdempotencyKeyEntity entity = IdempotencyKeyEntity.builder()
                .key(compositeKey)
                .merchantId(merchantId)
                .idempotencyKey(rawKey)
                .requestHash(requestHash)
                .endpointSignature(endpointSignature)
                .status(IdempotencyStatus.PROCESSING)
                .processingStartedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                .build();
        log.debug("Creating idempotency placeholder for merchant={} key={}", merchantId, rawKey);
        return repository.save(entity);
    }

    /**
     * Stores the response for a completed request and marks the record
     * {@link IdempotencyStatus#COMPLETED}.
     *
     * <p>Throws {@link MembershipException} with {@code IDEMPOTENCY_CONFLICT}
     * (HTTP 409) if the stored request hash does not match the provided hash —
     * which can happen when two concurrent requests race to store their result.
     *
     * @param merchantId   authenticated merchant identifier
     * @param rawKey       client-supplied Idempotency-Key header value
     * @param requestHash  hash of the current request (must match the placeholder)
     * @param statusCode   HTTP status code to store
     * @param responseBody serialised response body to store
     * @param owner        principal name (may be null for anonymous requests)
     * @param contentType  Content-Type of the response
     */
    @Transactional
    public void storeResponse(String merchantId, String rawKey, String requestHash,
                              int statusCode, String responseBody,
                              String owner, String contentType) {
        String compositeKey = merchantId + ":" + rawKey;
        IdempotencyKeyEntity entity = repository.findById(compositeKey)
                .orElseThrow(() -> new MembershipException(
                        "Idempotency placeholder not found for key: " + compositeKey,
                        "IDEMPOTENCY_ERROR"));

        if (!entity.getRequestHash().equals(requestHash)) {
            throw new MembershipException(
                    "Idempotency key '" + rawKey + "' was already used with a different request payload",
                    "IDEMPOTENCY_CONFLICT",
                    HttpStatus.CONFLICT);
        }

        entity.setStatus(IdempotencyStatus.COMPLETED);
        entity.setCompletedAt(LocalDateTime.now());
        entity.setResponseBody(responseBody);
        entity.setStatusCode(statusCode);
        entity.setOwner(owner);
        entity.setContentType(contentType);
        repository.save(entity);
        log.debug("Stored idempotency response for merchant={} key={} status={}",
                merchantId, rawKey, statusCode);
    }

    /**
     * Marks a record as failed without storing a response body.
     *
     * @param merchantId authenticated merchant identifier
     * @param rawKey     client-supplied Idempotency-Key header value
     * @param retryable  if {@code true}, sets {@link IdempotencyStatus#FAILED_RETRYABLE}
     *                   so the client may retry with the same key; otherwise
     *                   sets {@link IdempotencyStatus#FAILED_FINAL}
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
}
