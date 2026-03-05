package com.firstclub.membership.service;

/**
 * Token blacklist for revoked JWT tokens (used on logout).
 *
 * Tokens are stored until their natural expiry to prevent re-use.
 * A production system should use a distributed store (Redis) so that
 * blacklisted tokens are honoured across all instances.
 */
public interface TokenBlacklistService {

    /** Revoke a token by adding it to the blacklist until it expires. */
    void blacklist(String token);

    /** Returns true if the token has been blacklisted. */
    boolean isBlacklisted(String token);
}
