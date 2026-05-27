package com.firstclub.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@EnableJpaAuditing
@ConfigurationPropertiesScan
public class MembershipApplication {

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
