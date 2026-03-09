package com.firstclub.notifications.webhooks.repository;

import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MerchantWebhookDeliveryRepository extends JpaRepository<MerchantWebhookDelivery, Long> {

    /**
     * Returns deliveries that are due for dispatch.
     * A delivery is due when its status is PENDING or FAILED
     * and its {@code nextAttemptAt} is in the past (or null).
     */
    @Query("""
            SELECT d FROM MerchantWebhookDelivery d
            WHERE d.status IN :statuses
              AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
            """)
    List<MerchantWebhookDelivery> findDueDeliveries(
            @Param("statuses") Collection<MerchantWebhookDeliveryStatus> statuses,
            @Param("now") LocalDateTime now);

    /** All deliveries for one endpoint, newest first. */
    List<MerchantWebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(Long endpointId);

    /**
     * All deliveries across all endpoints of a merchant, newest first.
     * Uses a subquery to enforce tenant isolation without a direct merchantId column.
     */
    @Query("""
            SELECT d FROM MerchantWebhookDelivery d
            WHERE d.endpointId IN (
                SELECT e.id FROM MerchantWebhookEndpoint e WHERE e.merchantId = :merchantId
            )
            ORDER BY d.createdAt DESC
            """)
    List<MerchantWebhookDelivery> findByMerchantIdOrderByCreatedAtDesc(@Param("merchantId") Long merchantId);

    /** Used to detect whether an endpoint has accumulated enough failures to auto-disable. */
    long countByEndpointIdAndStatus(Long endpointId, MerchantWebhookDeliveryStatus status);

    /** Platform-wide count by status — used by deep health checks. */
    long countByStatus(MerchantWebhookDeliveryStatus status);

    /**
     * Returns the most recent DELIVERED delivery matching the given fingerprint,
     * if one exists.  Used by the idempotent enqueue guard to skip re-delivery
     * of an already-delivered (endpoint, eventType, payload) combination.
     */
    Optional<MerchantWebhookDelivery> findTopByDeliveryFingerprintAndStatus(
            String deliveryFingerprint, MerchantWebhookDeliveryStatus status);

    /**
     * Filtered delivery search for the merchant delivery search API.
     *
     * <p>All filter parameters are optional: pass {@code null} to skip a filter.
     * Results are ordered newest-first; the {@code Pageable} controls limit/offset.
     */
    @Query("""
            SELECT d FROM MerchantWebhookDelivery d
            WHERE d.endpointId IN (
                SELECT e.id FROM MerchantWebhookEndpoint e WHERE e.merchantId = :merchantId
            )
              AND (:eventType    IS NULL OR d.eventType        = :eventType)
              AND (:status       IS NULL OR d.status           = :status)
              AND (:responseCode IS NULL OR d.lastResponseCode = :responseCode)
              AND (:fromTime     IS NULL OR d.createdAt        >= :fromTime)
              AND (:toTime       IS NULL OR d.createdAt        <= :toTime)
            ORDER BY d.createdAt DESC
            """)
    List<MerchantWebhookDelivery> searchDeliveries(
            @Param("merchantId")   Long merchantId,
            @Param("eventType")    String eventType,
            @Param("status")       MerchantWebhookDeliveryStatus status,
            @Param("responseCode") Integer responseCode,
            @Param("fromTime")     LocalDateTime fromTime,
            @Param("toTime")       LocalDateTime toTime,
            Pageable pageable);

    /**
     * Atomically claims a batch of due webhook deliveries using {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p><b>Guard:</b> BusinessLockScope.WEBHOOK_DELIVERY_PROCESSING
     * <p>Using {@code SKIP LOCKED} ensures that when two scheduler pods both attempt to
     * process deliveries simultaneously, each pod gets a disjoint set.  This closes the
     * double-dispatch race that was present when {@link #findDueDeliveries} (plain SELECT)
     * was used as the polling query.
     *
     * <p>The {@code LIMIT :limit} cap prevents a single worker from holding too many
     * row locks in a long-running transaction.
     *
     * <p><b>Note:</b> This is a native query because JPQL does not support
     * {@code FOR UPDATE SKIP LOCKED}.
     */
    @Query(value = """
            SELECT * FROM merchant_webhook_deliveries
            WHERE status IN ('PENDING', 'FAILED')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY next_attempt_at NULLS FIRST
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MerchantWebhookDelivery> findDueForProcessingWithSkipLocked(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
