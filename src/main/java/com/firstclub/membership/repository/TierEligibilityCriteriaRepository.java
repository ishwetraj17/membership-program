package com.firstclub.membership.repository;

import com.firstclub.membership.entity.TierEligibilityCriteria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TierEligibilityCriteriaRepository extends JpaRepository<TierEligibilityCriteria, Long> {

    Optional<TierEligibilityCriteria> findByTier_Name(String tierName);

    /** Returns criteria ordered highest tier first so we can short-circuit at the best eligible tier. */
    @Query("SELECT c FROM TierEligibilityCriteria c JOIN c.tier t ORDER BY t.level DESC")
    List<TierEligibilityCriteria> findAllOrderByTierLevelDesc();
}
