package com.firstclub.membership;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.PlanService;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.TierEvaluationService;
import com.firstclub.membership.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test suite — runs against an H2 in-memory database with Flyway
 * disabled; Hibernate creates the schema from entity metadata instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    // ── In-memory H2 for tests ──────────────────────────────────────────────
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    // ── Let Hibernate create the schema; Flyway SQL is PostgreSQL-specific ──
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    // ── Suppress noisy SQL in test output ───────────────────────────────────
    "logging.level.org.hibernate.SQL=WARN",
    "logging.level.com.firstclub.membership=WARN"
})
@DisplayName("FirstClub Membership Program — Integration Tests")
class MembershipApplicationTests {

    @Autowired private MembershipService membershipService;
    @Autowired private PlanService planService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UserService userService;
    @Autowired private TierEvaluationService tierEvaluationService;
    @Autowired private TestRestTemplate restTemplate;

    @LocalServerPort private int port;

    private String baseUrl() { return "http://localhost:" + port; }

    private String uniqueEmail(String prefix) {
        return prefix + System.currentTimeMillis() + "@test.com";
    }

    private UserDTO testUser(String name, String emailPrefix) {
        return UserDTO.builder()
                .name(name)
                .email(uniqueEmail(emailPrefix))
                .phoneNumber("9876543210")
                .address("123 Test Street")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400001")
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Context & Initialisation
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Context & Initialisation")
    class ContextTests {

        @Test @DisplayName("Spring context loads")
        void contextLoads() {
            assertThat(membershipService).isNotNull();
            assertThat(planService).isNotNull();
            assertThat(subscriptionService).isNotNull();
            assertThat(userService).isNotNull();
            assertThat(tierEvaluationService).isNotNull();
        }

        @Test @DisplayName("3 membership tiers are initialised")
        void tiersInitialised() {
            List<TierDTO> tiers = membershipService.getAllTiers();
            assertThat(tiers).hasSize(3);
            assertThat(tiers).extracting(TierDTO::getName)
                    .containsExactlyInAnyOrder("SILVER", "GOLD", "PLATINUM");
        }

        @Test @DisplayName("9 plans are initialised (3 tiers × 3 durations)")
        void plansInitialised() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            assertThat(plans).hasSize(9);

            long monthly   = plans.stream().filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY).count();
            long quarterly = plans.stream().filter(p -> p.getType() == MembershipPlan.PlanType.QUARTERLY).count();
            long yearly    = plans.stream().filter(p -> p.getType() == MembershipPlan.PlanType.YEARLY).count();

            assertThat(monthly).isEqualTo(3);
            assertThat(quarterly).isEqualTo(3);
            assertThat(yearly).isEqualTo(3);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Business Logic
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Business Logic")
    class BusinessLogicTests {

        @Test @DisplayName("Tier pricing hierarchy: Silver < Gold < Platinum")
        void pricingHierarchy() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();

            BigDecimal silver = monthly(plans, "SILVER");
            BigDecimal gold   = monthly(plans, "GOLD");
            BigDecimal platinum = monthly(plans, "PLATINUM");

            assertThat(gold).isGreaterThan(silver);
            assertThat(platinum).isGreaterThan(gold);
        }

        @Test @DisplayName("Yearly plans save money vs. 12× monthly")
        void yearlySavings() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();

            MembershipPlanDTO silverMonthly = planOf(plans, "SILVER", MembershipPlan.PlanType.MONTHLY);
            MembershipPlanDTO silverYearly  = planOf(plans, "SILVER", MembershipPlan.PlanType.YEARLY);

            BigDecimal fullYearlyNoDiscount = silverMonthly.getPrice().multiply(new BigDecimal("12"));
            assertThat(silverYearly.getPrice()).isLessThan(fullYearlyNoDiscount);
            assertThat(silverYearly.getSavings()).isPositive();
        }

        @Test @DisplayName("Tier levels are 1/2/3 for Silver/Gold/Platinum")
        void tierLevels() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            plans.stream().filter(p -> p.getTier().equals("SILVER")).forEach(p -> assertThat(p.getTierLevel()).isEqualTo(1));
            plans.stream().filter(p -> p.getTier().equals("GOLD")).forEach(p -> assertThat(p.getTierLevel()).isEqualTo(2));
            plans.stream().filter(p -> p.getTier().equals("PLATINUM")).forEach(p -> assertThat(p.getTierLevel()).isEqualTo(3));
        }

        @Test @DisplayName("Tier discounts are 5 / 10 / 15 percent")
        void tierDiscounts() {
            List<TierDTO> tiers = membershipService.getAllTiers();
            assertThat(tiers.stream().filter(t -> t.getName().equals("SILVER")).findFirst().orElseThrow().getDiscountPercentage())
                    .isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(tiers.stream().filter(t -> t.getName().equals("GOLD")).findFirst().orElseThrow().getDiscountPercentage())
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(tiers.stream().filter(t -> t.getName().equals("PLATINUM")).findFirst().orElseThrow().getDiscountPercentage())
                    .isEqualByComparingTo(new BigDecimal("15.00"));
        }

        // helpers
        private BigDecimal monthly(List<MembershipPlanDTO> plans, String tier) {
            return planOf(plans, tier, MembershipPlan.PlanType.MONTHLY).getPrice();
        }
        private MembershipPlanDTO planOf(List<MembershipPlanDTO> plans, String tier, MembershipPlan.PlanType type) {
            return plans.stream()
                    .filter(p -> p.getTier().equals(tier) && p.getType() == type)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Plan not found: " + tier + "/" + type));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // User Management
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("User Management")
    class UserTests {

        @Test @DisplayName("Create user with valid data")
        void createUser() {
            UserDTO dto = testUser("Test User", "create");
            UserDTO created = userService.createUser(dto);
            assertThat(created.getId()).isNotNull();
            assertThat(created.getEmail()).isEqualTo(dto.getEmail());
        }

        @Test @DisplayName("Retrieve user by ID")
        void getById() {
            UserDTO created = userService.createUser(testUser("Get Test", "get"));
            UserDTO found = userService.getUserById(created.getId()).orElseThrow();
            assertThat(found.getName()).isEqualTo("Get Test");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Subscription Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Subscription Lifecycle")
    class SubscriptionTests {

        @Test @DisplayName("Create subscription successfully")
        void createSubscription() {
            UserDTO user = userService.createUser(testUser("Sub User", "sub"));
            MembershipPlanDTO plan = silverMonthly();

            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(plan.getId()).autoRenewal(true).build());

            assertThat(sub.getId()).isNotNull();
            assertThat(sub.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(sub.getPaidAmount()).isEqualByComparingTo(plan.getPrice());
        }

        @Test @DisplayName("Block duplicate active subscriptions")
        void noDuplicateActive() {
            UserDTO user = userService.createUser(testUser("Dup User", "dup"));
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(true).build();

            subscriptionService.createSubscription(req);

            assertThatThrownBy(() -> subscriptionService.createSubscription(req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("already has an active subscription");
        }

        @Test @DisplayName("Cancel subscription")
        void cancelSubscription() {
            UserDTO user = userService.createUser(testUser("Cancel User", "cancel"));
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(true).build());

            SubscriptionDTO cancelled = subscriptionService.cancelSubscription(sub.getId(), "Test cancellation");

            assertThat(cancelled.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            assertThat(cancelled.getCancellationReason()).isEqualTo("Test cancellation");
            assertThat(cancelled.getAutoRenewal()).isFalse();
            assertThat(cancelled.getCancelledAt()).isNotNull();
        }

        @Test @DisplayName("Cannot cancel already-cancelled subscription")
        void cancelNonActive() {
            UserDTO user = userService.createUser(testUser("Cancel2 User", "cancel2"));
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(true).build());
            subscriptionService.cancelSubscription(sub.getId(), "First");

            assertThatThrownBy(() -> subscriptionService.cancelSubscription(sub.getId(), "Second"))
                    .isInstanceOf(MembershipException.class);
        }

        @Test @DisplayName("Get active subscription")
        void getActive() {
            UserDTO user = userService.createUser(testUser("Active User", "active"));
            MembershipPlanDTO goldPlan = planOf("GOLD", MembershipPlan.PlanType.MONTHLY);
            subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(goldPlan.getId()).autoRenewal(true).build());

            Optional<SubscriptionDTO> active = subscriptionService.getActiveSubscription(user.getId());
            assertThat(active).isPresent();
            assertThat(active.get().getTier()).isEqualTo("GOLD");
        }

        private MembershipPlanDTO silverMonthly() {
            return planOf("SILVER", MembershipPlan.PlanType.MONTHLY);
        }

        private MembershipPlanDTO planOf(String tier, MembershipPlan.PlanType type) {
            return planService.getActivePlans().stream()
                    .filter(p -> p.getTier().equals(tier) && p.getType() == type)
                    .findFirst()
                    .orElseThrow();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tier Eligibility
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Tier Eligibility")
    class TierEligibilityTests {

        @Test @DisplayName("Evaluate returns a valid tier name")
        void evaluateReturnsValidTier() {
            UserDTO user = userService.createUser(testUser("Eligibility User", "elig"));
            TierEligibilityResult result = tierEvaluationService.evaluateEligibleTier(user.getId());

            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(user.getId());
            assertThat(result.getEligibleTierName()).isIn("SILVER", "GOLD", "PLATINUM");
            assertThat(result.getOrderCount()).isGreaterThanOrEqualTo(0);
            assertThat(result.getMonthlySpend()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Exception Handling
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Exception Handling")
    class ExceptionTests {

        @Test @DisplayName("User not found throws MembershipException")
        void userNotFound() {
            assertThatThrownBy(() -> subscriptionService.getUserSubscriptions(99999L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }

        @Test @DisplayName("Plan not found throws MembershipException")
        void planNotFound() {
            UserDTO user = userService.createUser(testUser("Exception User", "exc"));
            assertThatThrownBy(() -> subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(99999L).autoRenewal(true).build()))
                    .isInstanceOf(MembershipException.class);
        }

        @Test @DisplayName("Subscription not found throws MembershipException")
        void subscriptionNotFound() {
            assertThatThrownBy(() -> subscriptionService.cancelSubscription(99999L, "Test"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REST API
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("REST API Integration")
    class RestApiTests {

        private <T> HttpEntity<T> jsonRequest(T body) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(body, headers);
        }

        @Test @DisplayName("POST /users — creates user")
        void createUserApi() {
            UserDTO req = testUser("API User", "apiuser");
            ResponseEntity<UserDTO> resp = restTemplate.postForEntity(baseUrl() + "/api/v1/users", jsonRequest(req), UserDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getId()).isNotNull();
        }

        @Test @DisplayName("GET /plans — returns 9 plans")
        void getAllPlans() {
            ResponseEntity<MembershipPlanDTO[]> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/membership/plans", MembershipPlanDTO[].class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSize(9);
        }

        @Test @DisplayName("GET /tiers — returns TierDTO (no entity leak)")
        void getAllTiers() {
            ResponseEntity<TierDTO[]> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/membership/tiers", TierDTO[].class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSize(3);
        }

        @Test @DisplayName("POST /subscriptions — creates subscription")
        void createSubscriptionApi() {
            UserDTO user = userService.createUser(testUser("SubAPI User", "subapi"));
            MembershipPlanDTO plan = planService.getActivePlans().stream()
                    .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();

            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(plan.getId()).autoRenewal(true).build();

            ResponseEntity<SubscriptionDTO> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions", jsonRequest(req), SubscriptionDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
        }

        @Test @DisplayName("GET /health — returns UP")
        void health() {
            ResponseEntity<Object> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/membership/health", Object.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test @DisplayName("GET /users/{id}/tier-eligibility — returns result")
        void tierEligibilityApi() {
            UserDTO user = userService.createUser(testUser("Elig API User", "eligapi"));
            ResponseEntity<TierEligibilityResult> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/users/" + user.getId() + "/tier-eligibility",
                    TierEligibilityResult.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getEligibleTierName()).isIn("SILVER", "GOLD", "PLATINUM");
        }

        @Test @DisplayName("GET /users/99999 — 404")
        void userNotFound() {
            ResponseEntity<String> resp = restTemplate.getForEntity(baseUrl() + "/api/v1/users/99999", String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("POST /subscriptions with null IDs — 400")
        void invalidSubscriptionRequest() {
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder().userId(null).planId(null).build();
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions", jsonRequest(req), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test @DisplayName("PUT /subscriptions/{id}/cancel — cancels subscription")
        void cancelSubscriptionApi() {
            UserDTO user = userService.createUser(testUser("Cancel API User", "cancelapi"));
            MembershipPlanDTO plan = planService.getActivePlans().get(0);
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(plan.getId()).autoRenewal(true).build());

            Map<String, String> body = new HashMap<>();
            body.put("reason", "API test cancellation");

            ResponseEntity<SubscriptionDTO> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/subscriptions/" + sub.getId() + "/cancel",
                    HttpMethod.PUT, jsonRequest(body), SubscriptionDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
        }
    }
}
