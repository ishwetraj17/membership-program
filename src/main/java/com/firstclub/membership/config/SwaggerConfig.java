package com.firstclub.membership.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger/OpenAPI configuration for comprehensive API documentation
 * 
 * Provides detailed API documentation with proper schemas, examples,
 * and error response documentation for interviewer review.
 * 
 * Implemented by Shwet Raj
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("FirstClub Membership Program API")
                .version("1.0.0")
                .description("""
                    Comprehensive membership management system for FirstClub.
                    
                    This API provides complete membership lifecycle management including:
                    • Plan discovery and comparison
                    • User subscription management  
                    • Tier-based benefits system
                    • Subscription lifecycle tracking
                    • Analytics and reporting
                    
                    **Key Features:**
                    - Flexible tier + duration combinations (Silver/Gold/Platinum × Monthly/Quarterly/Yearly)
                    - Complete subscription CRUD operations with proper validation
                    - User-centric subscription management endpoints
                    - Comprehensive plan discovery APIs
                    - Real-time subscription status tracking
                    - Proper error handling with meaningful error codes
                    
                    **Built for production with:**
                    - Input validation and sanitization
                    - Proper HTTP status codes
                    - Comprehensive error responses
                    - Swagger documentation with examples
                    - Clean architecture patterns
                    
                    Developed by Shwet Raj for FirstClub interview process.
                    """)
                .contact(new Contact()
                    .name("Shwet Raj")
                    .email("ishwetraj2@gmail.com")
                    .url("https://github.com/shwetraj17"))
                .license(new License()
                    .name("MIT License")
                    .url("https://opensource.org/licenses/MIT")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:8080")
                    .description("Development Server"),
                new Server()
                    .url("https://api.firstclub.com")
                    .description("Production Server (Future)")
            ));
    }
}
