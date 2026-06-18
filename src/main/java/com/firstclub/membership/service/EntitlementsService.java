package com.firstclub.membership.service;

import com.firstclub.membership.dto.EntitlementsDTO;

/**
 * Provides a user's current membership entitlements for the commerce/checkout platform.
 *
 * Cache is the primary read path; the database is the fallback. Any failure (cache or DB) yields
 * a safe non-member response so a membership outage never blocks checkout.
 */
public interface EntitlementsService {

    EntitlementsDTO getEntitlements(Long userId);

    /** Invalidate a user's cached entitlements (called on subscription lifecycle changes). */
    void invalidate(Long userId);

    /** Clear all cached entitlements (called when benefit configuration changes for a tier). */
    void invalidateAll();
}
