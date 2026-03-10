package com.firstclub.risk.repository;

import com.firstclub.risk.entity.RiskDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiskDecisionRepository extends JpaRepository<RiskDecision, Long> {

    Page<RiskDecision> findByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);

    Page<RiskDecision> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // Phase 18: look up all decisions for a payment intent (explain endpoint)
    List<RiskDecision> findByPaymentIntentIdOrderByCreatedAtDesc(Long paymentIntentId);

    Optional<RiskDecision> findTopByPaymentIntentIdOrderByCreatedAtDesc(Long paymentIntentId);
}
