package com.firstclub.membership.service;

import com.firstclub.membership.dto.MembershipTierDTO;

import java.util.List;
import java.util.Optional;

/**
 * Service for membership tier operations.
 *
 * Separated from MembershipService to keep responsibilities focused.
 * Tier data changes rarely — all reads are Cacheable.
 */
public interface TierService {

    List<MembershipTierDTO> getAllTiers();

    Optional<MembershipTierDTO> getTierByName(String name);

    Optional<MembershipTierDTO> getTierById(Long id);
}
