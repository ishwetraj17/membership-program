package com.firstclub.platform.ratelimit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistence access for rate-limit audit events.
 *
 * <p>The only custom query is {@link #countBlocksLastHour()}, which is used
 * by the ops deep-health endpoint and the
 * {@link RedisSlidingWindowRateLimiter#getBlocksLastHour()} method.
 */
@Repository
public interface RateLimitEventRepository extends JpaRepository<RateLimitEventEntity, UUID> {

    /**
     * Count all blocked events created within the last hour.
     * Used by the deep-health service to surface rate-limit pressure.
     */
    @Query("SELECT COUNT(e) FROM RateLimitEventEntity e " +
           "WHERE e.blocked = true AND e.createdAt >= :since")
    long countBlocksSince(@Param("since") LocalDateTime since);

    /** Convenience wrapper — counts blocks in the rolling 1-hour window. */
    default long countBlocksLastHour() {
        return countBlocksSince(LocalDateTime.now().minusHours(1));
    }
}
