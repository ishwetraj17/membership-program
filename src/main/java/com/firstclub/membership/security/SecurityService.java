package com.firstclub.membership.security;

import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Helper bean used in @PreAuthorize SpEL expressions for ownership checks.
 *
 * Usage in controller:
 *   @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
 *   @PreAuthorize("hasRole('ADMIN') or @securityService.isSubscriptionOwner(#id, authentication)")
 *
 * Both methods use lightweight COUNT/EXISTS queries — no entity loading.
 */
@Service
@RequiredArgsConstructor
public class SecurityService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Returns true if the authenticated principal's email matches the email of
     * the non-deleted user with the given userId.
     * Uses a single EXISTS query — no full entity load.
     */
    public boolean isSameUser(Long userId, Authentication authentication) {
        if (authentication == null || userId == null) {
            return false;
        }
        return userRepository.existsByIdAndEmailIgnoreCaseAndIsDeletedFalse(userId, authentication.getName());
    }

    /**
     * Returns true if the subscription with the given ID belongs to the
     * authenticated principal. Used to guard GET/PUT subscription endpoints
     * so users can only access their own subscriptions.
     */
    public boolean isSubscriptionOwner(Long subscriptionId, Authentication authentication) {
        if (authentication == null || subscriptionId == null) {
            return false;
        }
        return subscriptionRepository.existsByIdAndUserEmail(subscriptionId, authentication.getName());
    }
}
