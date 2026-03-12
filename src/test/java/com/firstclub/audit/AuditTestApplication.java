package com.firstclub.audit;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot bootstrap for @DataJpaTest classes in the
 * com.firstclub.audit package hierarchy. @DataJpaTest searches upward
 * from the test class's package to find a @SpringBootConfiguration;
 * this stub satisfies that requirement without pulling in the full
 * application's component scan (which contains conflicting bean names).
 *
 * scanBasePackages, @EntityScan, and @EnableJpaRepositories are all
 * intentionally limited to only the audit and platform.version packages
 * so that the conflicting RevenueRecognitionCeilingChecker beans in other
 * packages are never in scope.
 */
@SpringBootApplication(scanBasePackages = {
        "com.firstclub.audit",
        "com.firstclub.platform.version"
})
@EntityScan(basePackages = {
        "com.firstclub.audit.entity",
        "com.firstclub.platform.version"
})
@EnableJpaRepositories(basePackages = {
        "com.firstclub.audit.repository",
        "com.firstclub.platform.version"
})
public class AuditTestApplication {
}
