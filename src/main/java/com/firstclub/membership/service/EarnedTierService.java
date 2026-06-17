package com.firstclub.membership.service;

import com.firstclub.membership.dto.UserTierAssignmentDTO;

import java.util.Optional;

/**
 * Manages the tier a user has <em>earned</em> through order activity — promotion and
 * demotion as their metrics change — distinct from the tier they purchase.
 */
public interface EarnedTierService {

    /** Re-evaluate and persist the user's earned tier now. */
    UserTierAssignmentDTO assignEarnedTier(Long userId);

    /** Current earned tier; computed and persisted on first read if absent. */
    Optional<UserTierAssignmentDTO> getEarnedTier(Long userId);

    /** Re-evaluate every user (scheduled). Returns the number successfully processed. */
    int reevaluateAll();
}
