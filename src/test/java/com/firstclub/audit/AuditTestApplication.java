package com.firstclub.audit;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot bootstrap for @DataJpaTest classes in the
 * com.firstclub.audit package hierarchy. @DataJpaTest searches upward
 * from the test class's package to find a @SpringBootConfiguration;
 * this stub satisfies that requirement without pulling in the full
 * application's component scan (which contains conflicting bean names).
 *
 * <p>Annotated with {@code @TestComponent} so that it is excluded from
 * component scanning during {@code @SpringBootTest} integration tests
 * (which load the real {@code MembershipApplication} configuration).
 *
 * scanBasePackages, @EntityScan, and @EnableJpaRepositories are all
 * intentionally limited to only the audit and platform.version packages
 * so that the conflicting RevenueRecognitionCeilingChecker beans in other
 * packages are never in scope.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@TestComponent
@ComponentScan(basePackages = {
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
