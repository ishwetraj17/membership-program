package com.firstclub.membership.security;

import com.firstclub.membership.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Helper bean used in @PreAuthorize SpEL expressions for ownership checks.
 *
 * Usage in controller:
 *   @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
 */
@Service
@RequiredArgsConstructor
public class SecurityService {

    private final UserRepository userRepository;

    /**
     * Returns true if the authenticated principal's email matches the email of
     * the user with the given userId. Admins should bypass this check via hasRole().
     */
    public boolean isSameUser(Long userId, Authentication authentication) {
        if (authentication == null || userId == null) {
            return false;
        }
        String principalEmail = authentication.getName();
        return userRepository.findById(userId)
            .map(user -> user.getEmail().equalsIgnoreCase(principalEmail))
            .orElse(false);
    }
}
