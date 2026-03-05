package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.MembershipTierDTO;
import com.firstclub.membership.mapper.MembershipTierMapper;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.service.TierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tier service implementation.
 *
 * Delegated from MembershipServiceImpl to keep that class focused
 * on subscription lifecycle only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TierServiceImpl implements TierService {

    private final MembershipTierRepository tierRepository;
    private final MembershipTierMapper tierMapper;

    @Override
    @Cacheable("tiers")
    public List<MembershipTierDTO> getAllTiers() {
        return tierRepository.findAll().stream()
            .map(tierMapper::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "tiers", key = "#name.toUpperCase()")
    public Optional<MembershipTierDTO> getTierByName(String name) {
        return tierRepository.findByName(name.toUpperCase()).map(tierMapper::toDTO);
    }

    @Override
    public Optional<MembershipTierDTO> getTierById(Long id) {
        return tierRepository.findById(id).map(tierMapper::toDTO);
    }
}
