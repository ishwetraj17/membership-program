package com.firstclub.platform.idempotency.service;

import com.firstclub.platform.idempotency.IdempotencyCheckpointEntity;
import com.firstclub.platform.idempotency.IdempotencyCheckpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and retrieves operation checkpoints for idempotent multi-step workflows.
 *
 * <p>Typical usage pattern in a service method:
 * <pre>
 *   // Check whether this step was already completed on a previous attempt
 *   boolean alreadyDone = checkpointService.getForKey(merchantId, idempotencyKey)
 *       .stream().anyMatch(cp -> "SUBSCRIPTION_SAVED".equals(cp.getStepName())
 *                              && "SUCCESS".equals(cp.getStepStatus()));
 *   if (!alreadyDone) {
 *       Subscription sub = subscriptionRepo.save(newSubscription);
 *       checkpointService.record(merchantId, idempotencyKey,
 *           "CREATE_SUBSCRIPTION", "SUBSCRIPTION_SAVED", "SUCCESS",
 *           "Subscription", sub.getId(), null);
 *   }
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class IdempotencyCheckpointService {

    private final IdempotencyCheckpointRepository repository;

    /**
     * Persists a single operation checkpoint.
     *
     * @param merchantId      authenticated merchant identifier
     * @param idempotencyKey  raw Idempotency-Key value
     * @param operationType   high-level operation (e.g., {@code "CREATE_SUBSCRIPTION"})
     * @param stepName        individual step name (e.g., {@code "PAYMENT_CHARGED"})
     * @param stepStatus      outcome: {@code "SUCCESS"}, {@code "SKIPPED"}, or {@code "FAILED"}
     * @param resourceType    optional resource type (e.g., {@code "Subscription"})
     * @param resourceId      optional ID of the created/modified resource
     * @param payloadJson     optional JSON payload for audit/replay context
     * @return the saved entity
     */
    @Transactional
    public IdempotencyCheckpointEntity record(String merchantId, String idempotencyKey,
                                               String operationType, String stepName,
                                               String stepStatus, String resourceType,
                                               Long resourceId, String payloadJson) {
        IdempotencyCheckpointEntity entity = IdempotencyCheckpointEntity.builder()
                .merchantId(merchantId)
                .idempotencyKey(idempotencyKey)
                .operationType(operationType)
                .stepName(stepName)
                .stepStatus(stepStatus)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .payloadJson(payloadJson)
                .build();
        return repository.save(entity);
    }

    /**
     * Returns all checkpoints for the given merchant and key in creation order.
     */
    public List<IdempotencyCheckpointEntity> getForKey(String merchantId, String idempotencyKey) {
        return repository.findByMerchantIdAndIdempotencyKeyOrderByCreatedAt(merchantId, idempotencyKey);
    }
}
