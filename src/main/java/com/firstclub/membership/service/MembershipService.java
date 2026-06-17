package com.firstclub.membership.service;

import com.firstclub.membership.dto.BenefitDTO;
import com.firstclub.membership.dto.TierDTO;

import java.util.List;
import java.util.Optional;

public interface MembershipService {
    List<TierDTO> getAllTiers();
    Optional<TierDTO> getTierByName(String name);
    Optional<TierDTO> getTierById(Long id);

    // ─── Configurable benefits ────────────────────────────────
    List<BenefitDTO> getBenefitCatalog();
    BenefitDTO createBenefit(BenefitDTO request);
    TierDTO assignBenefitToTier(String tierName, String benefitCode, String value);
    TierDTO removeBenefitFromTier(String tierName, String benefitCode);
}
