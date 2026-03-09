package com.firstclub.membership.config;

import com.firstclub.membership.service.SeedingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds sample data (admin user + demo members) on application startup.
 *
 * Only active in the "dev" profile so the production database is never
 * pre-populated by application code. {@link SeedingService#createSampleUsers()}
 * is idempotent — it checks whether the admin account already exists before
 * inserting any rows.
 *
 * Depends on {@link SeedingService} (interface) rather than the concrete
 * {@code MembershipServiceImpl} so it is decoupled from implementation details.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements ApplicationRunner {

    private final SeedingService seedingService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("DevDataSeeder: seeding sample users for dev environment");
        seedingService.createSampleUsers();
    }
}
