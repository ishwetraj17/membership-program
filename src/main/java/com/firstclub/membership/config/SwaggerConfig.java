package com.firstclub.membership.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("FirstClub Membership API")
                .version("1.0.0")
                .description("""
                    Membership management backend for FirstClub.

                    **Domain:** Silver / Gold / Platinum tiers × Monthly / Quarterly / Yearly plans = 9 plans total.
                    Each subscription is user-scoped and status-tracked (ACTIVE → CANCELLED / EXPIRED → renewed).

                    **Concurrency:** duplicate active subscriptions are prevented by a PostgreSQL partial unique \
                    index (`uq_user_active_subscription`) backed by optimistic locking (`@Version`) and an \
                    application-level guard.

                    **Caching:** plan and tier lists are cached (Caffeine, 10-minute TTL).

                    **Pagination:** `GET /api/v1/membership/subscriptions` supports `?page=0&size=20&sort=id,desc`.
                    """)
                .contact(new Contact()
                    .name("Shwet Raj")
                    .email("ishwetraj2@gmail.com")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local")));
    }
}
