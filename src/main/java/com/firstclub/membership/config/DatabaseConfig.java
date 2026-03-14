package com.firstclub.membership.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration active only in the {@code dev} profile.
 *
 * <p>The DataSource is intentionally NOT declared here — Spring Boot's
 * auto-configuration reads {@code spring.datasource.*} from
 * {@code application-dev.properties} (H2) or, when tests override those
 * properties via {@code @DynamicPropertySource}, from Testcontainers (Postgres).
 * Declaring a {@code @Bean DataSource} here would bypass both mechanisms.
 *
 * <p>{@code @EnableJpaAuditing} is kept so that any future use of Spring Data
 * auditing annotations ({@code @CreatedDate}, {@code @LastModifiedDate}) works
 * automatically in the dev environment.
 *
 * Implemented by Shwet Raj
 */
@Configuration
@Profile("dev")
@EnableJpaRepositories(basePackages = "com.firstclub")
@EnableJpaAuditing
public class DatabaseConfig {
}