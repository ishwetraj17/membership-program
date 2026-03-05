package com.firstclub.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main application class for FirstClub Membership Program
 * 
 * This is the entry point for the Spring Boot application.
 * Handles membership management with tiered pricing for Indian market.
 * 
 * @author Shwet Raj
 * @version 1.0.0
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@EnableCaching
public class MembershipApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipApplication.class, args);
        
        // Custom startup message - makes it easier to see when app is ready
        System.out.println("\n" + "=".repeat(70));
        System.out.println("🚀 FirstClub Membership Program Started Successfully!");
        System.out.println("👨‍💻 Developed by: Shwet Raj");
        System.out.println("=".repeat(70));
        System.out.println("📊 Swagger UI: http://localhost:8080/swagger-ui.html");
        System.out.println("🔍 H2 Console: http://localhost:8080/h2-console");
        System.out.println("💚 Health: http://localhost:8080/api/v1/membership/health");
        System.out.println("📈 Analytics: http://localhost:8080/api/v1/membership/analytics");
        System.out.println("=".repeat(70));
        System.out.println("🇮🇳 Optimized for Indian Market | INR Currency");
        System.out.println("=".repeat(70) + "\n");
    }
}