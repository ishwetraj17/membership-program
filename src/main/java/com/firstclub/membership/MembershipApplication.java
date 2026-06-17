package com.firstclub.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;

@SpringBootApplication
@EnableTransactionManagement
@EnableJpaAuditing
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class MembershipApplication {

    /**
     * Single source of "now" for business logic. Injecting a Clock (rather than calling
     * LocalDateTime.now() inline) makes time-dependent logic — pro-ration, expiry windows —
     * deterministic and unit-testable with a fixed clock.
     */
    @Bean
    Clock clock() {
        // UTC everywhere — combined with hibernate.jdbc.time_zone=UTC, timestamps are stored and
        // read as UTC regardless of the server's zone.
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(MembershipApplication.class, args);

        System.out.println("\n" + "=".repeat(65));
        System.out.println("  FirstClub Membership Program — started successfully");
        System.out.println("=".repeat(65));
        System.out.println("  Swagger : http://localhost:8080/swagger-ui.html");
        System.out.println("  Health  : http://localhost:8080/api/v1/membership/health");
        System.out.println("  Metrics : http://localhost:8080/actuator/metrics");
        System.out.println("=".repeat(65) + "\n");
    }
}
