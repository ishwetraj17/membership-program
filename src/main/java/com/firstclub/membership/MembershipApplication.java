package com.firstclub.membership;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main application class for FirstClub Membership Program
 *
 * Entry point for the Spring Boot application.
 * Handles membership management with tiered pricing for Indian market.
 *
 * @author Shwet Raj
 * @version 1.0.0
 */
@SpringBootApplication(scanBasePackages = "com.firstclub")
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
@EnableCaching
@Slf4j
public class MembershipApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipApplication.class, args);

        // Use SLF4J so the startup banner respects the configured log level,
        // log format (JSON/structured), and any log-aggregation pipeline.
        String sep = "=".repeat(70);
        log.info(sep);
        log.info("FirstClub Membership Program started successfully!");
        log.info("Developed by: Shwet Raj");
        log.info(sep);
        log.info("Swagger UI : http://localhost:8080/swagger-ui.html");
        log.info("H2 Console : http://localhost:8080/h2-console");
        log.info("Health     : http://localhost:8080/api/v1/membership/health");
        log.info("Analytics  : http://localhost:8080/api/v1/membership/analytics");
        log.info(sep);
        log.info("Optimized for Indian Market | INR Currency");
        log.info(sep);
    }
}