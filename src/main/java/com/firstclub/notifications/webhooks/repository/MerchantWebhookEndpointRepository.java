package com.firstclub.notifications.webhooks.repository;

import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MerchantWebhookEndpointRepository extends JpaRepository<MerchantWebhookEndpoint, Long> {

    /** All endpoints (active and inactive) belonging to this merchant. */
    List<MerchantWebhookEndpoint> findByMerchantId(Long merchantId);

    /** Only enabled endpoints for this merchant (used during event dispatch). */
    @Query("SELECT e FROM MerchantWebhookEndpoint e WHERE e.merchantId = :merchantId AND e.active = true")
    List<MerchantWebhookEndpoint> findActiveByMerchantId(@Param("merchantId") Long merchantId);

    /** Ownership check — returns empty if this endpoint is not owned by the merchant. */
    Optional<MerchantWebhookEndpoint> findByMerchantIdAndId(Long merchantId, Long id);

    /**
     * Count of endpoints with a non-null {@code autoDisabledAt} timestamp.
     * Used by metrics and admin health checks to surface repeatedly-failing endpoints.
     */
    @Query("SELECT COUNT(e) FROM MerchantWebhookEndpoint e WHERE e.autoDisabledAt IS NOT NULL")
    long countAutoDisabled();
}
