package com.firstclub.membership.security;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Central authorization checks shared by controllers. ADMIN is unrestricted; a USER may act
 * only on resources belonging to the membership user their login is linked to
 * ({@link AppUserPrincipal#getMembershipUserId()}). Prevents IDOR across every entry point.
 */
@Component
@RequiredArgsConstructor
public class AccessGuard {

    private final SubscriptionService subscriptionService;

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return principal.getMembershipUserId();
        }
        return null;
    }

    /** Allow if the caller is ADMIN or is acting on their own user id. */
    public void requireSelfOrAdmin(Long userId) {
        if (isAdmin()) return;
        if (userId != null && userId.equals(currentUserId())) return;
        throw forbidden("You may only access your own resources");
    }

    /** Allow if the caller is ADMIN or owns the given subscription. */
    public void requireSubscriptionOwnerOrAdmin(Long subscriptionId) {
        if (isAdmin()) return;
        Long userId = currentUserId();
        if (userId != null && subscriptionService.subscriptionBelongsToUser(subscriptionId, userId)) return;
        throw forbidden("You may only access your own subscriptions");
    }

    /** Allow only ADMIN. */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw forbidden("Administrator privileges required");
        }
    }

    private MembershipException forbidden(String message) {
        return new MembershipException(message, "FORBIDDEN", HttpStatus.FORBIDDEN);
    }
}
