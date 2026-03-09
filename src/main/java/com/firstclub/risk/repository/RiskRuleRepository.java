package com.firstclub.risk.repository;

import com.firstclub.risk.entity.RiskRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {

    Page<RiskRule> findAll(Pageable pageable);

    /**
     * Loads all active rules that apply to the given merchant (merchant-specific rules
     * AND platform-wide rules where merchantId IS NULL), sorted by ascending priority.
     */
    @Query("SELECT r FROM RiskRule r " +
           "WHERE r.active = true " +
           "  AND (r.merchantId = :merchantId OR r.merchantId IS NULL) " +
           "ORDER BY r.priority ASC")
    List<RiskRule> findActiveRulesForMerchant(@Param("merchantId") Long merchantId);
}
