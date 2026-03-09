package com.firstclub.risk.repository;

import com.firstclub.risk.entity.RiskEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {

    List<RiskEvent> findAllByOrderByCreatedAtDesc();

    /**
     * Count payment-attempt events for a user within a time window.
     * Used for per-user velocity checking.
     */
    @Query("SELECT COUNT(r) FROM RiskEvent r " +
           "WHERE r.userId = :userId " +
           "  AND r.type = com.firstclub.risk.entity.RiskEvent.RiskEventType.PAYMENT_ATTEMPT " +
           "  AND r.createdAt >= :since")
    long countPaymentAttemptsByUserSince(@Param("userId") Long userId,
                                         @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(r) FROM RiskEvent r " +
           "WHERE r.ip = :ip " +
           "  AND r.type = com.firstclub.risk.entity.RiskEvent.RiskEventType.PAYMENT_ATTEMPT " +
           "  AND r.createdAt >= :since")
    long countPaymentAttemptsByIpSince(@Param("ip") String ip,
                                       @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT r.userId) FROM RiskEvent r " +
           "WHERE r.deviceId = :deviceId " +
           "  AND r.type = com.firstclub.risk.entity.RiskEvent.RiskEventType.PAYMENT_ATTEMPT " +
           "  AND r.createdAt >= :since")
    long countDistinctUsersByDeviceIdSince(@Param("deviceId") String deviceId,
                                           @Param("since") LocalDateTime since);
}
