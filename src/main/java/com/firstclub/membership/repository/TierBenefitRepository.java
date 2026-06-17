package com.firstclub.membership.repository;

import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.TierBenefit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TierBenefitRepository extends JpaRepository<TierBenefit, Long> {

    // JOIN FETCH the benefit so the DTO mapping doesn't trigger a lazy load per row.
    @Query("SELECT tb FROM TierBenefit tb JOIN FETCH tb.benefit WHERE tb.tier.id = :tierId")
    List<TierBenefit> findByTierIdFetchBenefit(@Param("tierId") Long tierId);

    Optional<TierBenefit> findByTierAndBenefit_Code(MembershipTier tier, String benefitCode);
}
