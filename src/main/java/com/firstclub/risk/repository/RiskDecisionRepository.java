package com.firstclub.risk.repository;

import com.firstclub.risk.entity.RiskDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskDecisionRepository extends JpaRepository<RiskDecision, Long> {

    Page<RiskDecision> findByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);

    Page<RiskDecision> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
