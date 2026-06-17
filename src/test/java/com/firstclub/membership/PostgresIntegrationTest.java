package com.firstclub.membership;

import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.PlanService;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-PostgreSQL integration test. Runs the full Flyway migration set against an ephemeral
 * Postgres 16 container, exercising the PostgreSQL-specific DDL that H2 cannot (BIGSERIAL,
 * partial unique index). Automatically skipped when Docker is unavailable, so the default
 * {@code mvn test} stays green everywhere.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL (Testcontainers) — Flyway + partial unique index")
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private PlanService planService;
    @Autowired private MembershipService membershipService;
    @Autowired private UserService userService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway seeds the catalog and the partial unique index exists")
    void migrationsApplied() {
        assertThat(planService.getActivePlans()).hasSize(9);
        assertThat(membershipService.getAllTiers()).hasSize(3);
        assertThat(membershipService.getBenefitCatalog()).isNotEmpty();

        // The PostgreSQL-specific partial unique index (one ACTIVE subscription per user).
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_user_active_subscription'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Subscription lifecycle works end-to-end on Postgres")
    void lifecycleOnPostgres() {
        UserDTO user = userService.createUser(UserDTO.builder()
                .name("PG User").email("pg" + System.nanoTime() + "@test.com")
                .phoneNumber("9876543210").address("1 Test").city("Mumbai")
                .state("Maharashtra").pincode("400001").build());

        Long planId = planService.getActivePlans().get(0).getId();
        var sub = subscriptionService.createSubscription(
                SubscriptionRequestDTO.builder().userId(user.getId()).planId(planId).autoRenewal(true).build());
        assertThat(sub.getId()).isNotNull();

        // Second active subscription for the same user is blocked.
        assertThatThrownBy(() -> subscriptionService.createSubscription(
                SubscriptionRequestDTO.builder().userId(user.getId()).planId(planId).autoRenewal(true).build()))
                .isInstanceOf(MembershipException.class);
    }

    @Test
    @DisplayName("Concurrent creates for one user yield exactly one ACTIVE subscription")
    void concurrentCreatesYieldOneActive() throws Exception {
        UserDTO user = userService.createUser(UserDTO.builder()
                .name("Race User").email("race" + System.nanoTime() + "@test.com")
                .phoneNumber("9876543210").address("1 Test").city("Mumbai")
                .state("Maharashtra").pincode("400001").build());
        Long planId = planService.getActivePlans().get(0).getId();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                            .userId(user.getId()).planId(planId).autoRenewal(true).build());
                    return true;
                } catch (Exception e) {
                    return false; // app guard, optimistic lock, or the partial unique index
                }
            }));
        }
        start.countDown(); // release all threads at once

        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) successes++;
        }
        pool.shutdown();

        // The partial unique index guarantees exactly one winner under the race.
        assertThat(successes).isEqualTo(1);
        assertThat(subscriptionService.getActiveSubscription(user.getId())).isPresent();
    }
}
