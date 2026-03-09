package com.firstclub.membership.service;

/**
 * Abstracts the source of the "currently authenticated user" identity
 * used when recording subscription history audit entries.
 *
 * Decoupling MembershipServiceImpl from SecurityContextHolder directly
 * makes unit tests simpler (no need to prime the static security context)
 * and keeps the service layer independent of the security infrastructure.
 *
 * The default implementation {@link impl.SecurityAuditContext} resolves
 * the ID from the live Spring Security context. Tests can supply a stub.
 */
public interface AuditContext {

    /**
     * Returns the ID of the currently authenticated user, or {@code null}
     * for unauthenticated / scheduler-triggered invocations.
     */
    Long getCurrentUserId();
}
