package com.firstclub.membership.security;

/**
 * Tracks consecutive failed logins per username and decides when an account is temporarily locked.
 * Two interchangeable implementations exist:
 *
 * <ul>
 *   <li>{@link LocalLoginAttemptService} — in-process (Caffeine). The default; correct on a single
 *       node.</li>
 *   <li>{@link RedisLoginAttemptService} — Redis-backed (activated by the {@code redis} profile) so
 *       lockout state is shared across pods; an attacker can't dodge the lock by spreading attempts
 *       over multiple instances.</li>
 * </ul>
 *
 * Both honour the same {@code security.lockout.max-attempts} / {@code window-minutes} configuration,
 * so swapping strategies changes only where the counter lives, never the lockout behaviour.
 */
public interface LoginAttemptService {

    /** @return {@code true} if {@code username} has reached the failure threshold and is locked. */
    boolean isLockedOut(String username);

    /** Records one failed login attempt, (re)starting the lockout window. */
    void recordFailure(String username);

    /** Clears the failure counter after a successful login. */
    void recordSuccess(String username);
}
