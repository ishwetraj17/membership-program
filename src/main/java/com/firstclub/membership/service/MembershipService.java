package com.firstclub.membership.service;

import com.firstclub.membership.dto.TierDTO;

import java.util.List;
import java.util.Optional;

public interface MembershipService {
    List<TierDTO> getAllTiers();
    Optional<TierDTO> getTierByName(String name);
    Optional<TierDTO> getTierById(Long id);
}
