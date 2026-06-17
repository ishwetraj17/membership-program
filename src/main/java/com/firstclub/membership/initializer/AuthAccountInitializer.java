package com.firstclub.membership.initializer;

import com.firstclub.membership.config.SecurityProperties;
import com.firstclub.membership.entity.AppAccount;
import com.firstclub.membership.repository.AppAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds the demo admin and user accounts on first startup (all profiles) so the API is
 * usable out of the box. Credentials are configurable via {@code security.seed.*}.
 *
 * Production note: override the seed passwords and JWT secret via environment variables —
 * the built-in defaults are for local/demo use only.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class AuthAccountInitializer implements ApplicationRunner {

    /** The built-in demo passwords — never acceptable in production. */
    private static final Set<String> DEFAULT_PASSWORDS = Set.of("admin123", "demo123");

    private final AppAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        seed(properties.getSeed().getAdminUsername(), properties.getSeed().getAdminPassword(), AppAccount.Role.ADMIN);
        seed(properties.getSeed().getUserUsername(), properties.getSeed().getUserPassword(), AppAccount.Role.USER);
    }

    private void seed(String username, String rawPassword, AppAccount.Role role) {
        if (accountRepository.existsByUsername(username)) {
            return;
        }
        // Never auto-seed built-in demo credentials in production — force an explicit override.
        if (isProd() && DEFAULT_PASSWORDS.contains(rawPassword)) {
            log.warn("Refusing to seed '{}' with a built-in default password in prod. "
                    + "Set security.seed.* (or SEED_*_PASSWORD) to provision this account.", username);
            return;
        }
        accountRepository.save(AppAccount.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build());
        log.info("Seeded {} account '{}'", role, username);
    }

    private boolean isProd() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
