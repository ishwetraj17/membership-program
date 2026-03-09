package com.firstclub.payments.routing.repository;

import com.firstclub.payments.routing.entity.GatewayRouteRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GatewayRouteRuleRepository extends JpaRepository<GatewayRouteRule, Long> {

    /**
     * Find active rules scoped to a specific merchant, for a given method type, currency, and
     * up-to-and-including the current retry number. Ordered by priority ASC, then retry_number DESC
     * so that more specific (higher retry_number) rules at equal priority take precedence.
     */
    @Query("SELECT r FROM GatewayRouteRule r " +
           "WHERE r.active = true " +
           "  AND r.merchantId = :merchantId " +
           "  AND r.paymentMethodType = :methodType " +
           "  AND r.currency = :currency " +
           "  AND r.retryNumber <= :retryNumber " +
           "ORDER BY r.priority ASC, r.retryNumber DESC")
    List<GatewayRouteRule> findActiveRulesForMerchantAndMethodAndCurrency(
            @Param("merchantId") Long merchantId,
            @Param("methodType") String methodType,
            @Param("currency") String currency,
            @Param("retryNumber") int retryNumber);

    /**
     * Find active platform-wide rules (merchant_id IS NULL) for a given method type, currency,
     * and up-to-and-including the current retry number.
     */
    @Query("SELECT r FROM GatewayRouteRule r " +
           "WHERE r.active = true " +
           "  AND r.merchantId IS NULL " +
           "  AND r.paymentMethodType = :methodType " +
           "  AND r.currency = :currency " +
           "  AND r.retryNumber <= :retryNumber " +
           "ORDER BY r.priority ASC, r.retryNumber DESC")
    List<GatewayRouteRule> findPlatformDefaultRules(
            @Param("methodType") String methodType,
            @Param("currency") String currency,
            @Param("retryNumber") int retryNumber);

    List<GatewayRouteRule> findAllByOrderByPriorityAsc();
}
