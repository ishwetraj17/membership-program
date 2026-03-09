package com.firstclub.platform.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link IdempotencyCheckpointEntity}.
 *
 * <p>Checkpoints are always retrieved in creation order so that callers
 * can walk the step history in sequence.
 */
public interface IdempotencyCheckpointRepository
        extends JpaRepository<IdempotencyCheckpointEntity, Long> {

    /**
     * Returns all checkpoints for a given merchant and idempotency key,
     * ordered by {@code created_at} ascending.
     */
    List<IdempotencyCheckpointEntity> findByMerchantIdAndIdempotencyKeyOrderByCreatedAt(
            String merchantId, String idempotencyKey);
}
