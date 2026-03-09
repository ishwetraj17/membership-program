package com.firstclub.membership.service;

/**
 * Contract for seeding demo/dev data.
 *
 * Decouples {@code DevDataSeeder} from the concrete {@code MembershipServiceImpl}
 * so it can depend on an interface, not an implementation.
 * Only active in the {@code dev} profile via {@code DevDataSeeder}.
 */
public interface SeedingService {

    /**
     * Create sample users (admin + demo members) if they do not already exist.
     * Idempotent — safe to call multiple times.
     */
    void createSampleUsers();
}
