package com.firstclub.dunning.service;

import com.firstclub.dunning.dto.DunningPolicyCreateRequestDTO;
import com.firstclub.dunning.dto.DunningPolicyResponseDTO;
import com.firstclub.dunning.entity.DunningPolicy;

import java.util.List;

/**
 * Manages merchant-specific dunning policies.
 *
 * <p>A policy controls the retry schedule ({@code retry_offsets_json}),
 * the number of attempts, the grace window, whether backup payment methods
 * are tried, and what terminal status is applied when all retries are exhausted.
 */
public interface DunningPolicyService {

    /** Create a new dunning policy for the given merchant. */
    DunningPolicyResponseDTO createPolicy(Long merchantId, DunningPolicyCreateRequestDTO request);

    /** Return all policies belonging to this merchant. */
    List<DunningPolicyResponseDTO> listPolicies(Long merchantId);

    /** Return a policy by its code within the merchant's namespace. */
    DunningPolicyResponseDTO getPolicyByCode(Long merchantId, String policyCode);

    /**
     * Resolve the effective policy for a merchant.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Policy with {@code policy_code = 'DEFAULT'} for this merchant.
     *   <li>Any other policy for this merchant (first alphabetically by code).
     *   <li>Auto-create and persist a DEFAULT policy with safe baseline values
     *       so that DunningServiceV2 always has a policy to work with.
     * </ol>
     *
     * @return the resolved entity (never null)
     */
    DunningPolicy resolvePolicy(Long merchantId);

    /** Parse a {@code retry_offsets_json} string into a list of minute offsets. */
    List<Integer> parseOffsets(String json);
}
