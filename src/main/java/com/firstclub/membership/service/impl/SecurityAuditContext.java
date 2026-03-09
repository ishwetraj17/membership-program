package com.firstclub.membership.service.impl;

import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.service.AuditContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Production implementation of {@link AuditContext}.
 *
 * Resolves the currently authenticated principal from Spring Security’s
 * {@code SecurityContextHolder} and maps the email to a numeric user ID.
 *
 * Uses a lightweight {@code SELECT u.id} query instead of loading the full
 * UserDTO — avoids an unnecessary N+1 DB call on every write operation.
 *
 * Returns {@code null} for scheduler or anonymous invocations; those audit
 * entries are recorded with a {@code null} changedByUserId, which is correct.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityAuditContext implements AuditContext {

    private final UserRepository userRepository;

    @Override
    public Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
                return null;
            }
            return userRepository.findIdByEmailAndIsDeletedFalse(auth.getName()).orElse(null);
        } catch (Exception e) {
            log.debug("Could not resolve current user ID for audit log: {}", e.getMessage());
            return null;
        }
    }
}
