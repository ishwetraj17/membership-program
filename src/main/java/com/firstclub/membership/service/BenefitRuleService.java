package com.firstclub.membership.service;

import com.firstclub.membership.dto.BenefitRuleDTO;
import com.firstclub.membership.dto.BenefitRuleRequest;

import java.util.List;

/**
 * Admin configuration of commerce benefit rules. Mutations invalidate cached entitlements so the
 * checkout engine and the entitlements contract reflect new configuration promptly.
 */
public interface BenefitRuleService {

    BenefitRuleDTO create(BenefitRuleRequest request);

    BenefitRuleDTO update(Long id, BenefitRuleRequest request);

    void delete(Long id);

    BenefitRuleDTO get(Long id);

    /** All rules for a tier (highest priority first). */
    List<BenefitRuleDTO> listByTier(Long tierId);
}
