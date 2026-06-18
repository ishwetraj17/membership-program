package com.firstclub.membership;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.Benefit;
import com.firstclub.membership.entity.BenefitType;
import com.firstclub.membership.entity.ProductCategory;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.SubscriptionEvent;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.entity.AuditEvent;
import com.firstclub.membership.entity.OutboxEvent;
import com.firstclub.membership.repository.AuditEventRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.OutboxEventRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.SavingsService;
import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.service.BenefitEngine;
import com.firstclub.membership.service.BenefitRuleService;
import com.firstclub.membership.service.EntitlementsService;
import com.firstclub.membership.service.benefit.BenefitEvaluation;
import com.firstclub.membership.service.benefit.CartContext;
import com.firstclub.membership.entity.Coupon;
import com.firstclub.membership.event.OutboxEventService;
import com.firstclub.membership.service.CheckoutService;
import com.firstclub.membership.service.CouponService;
import com.firstclub.membership.service.EarnedTierService;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    "logging.level.com.firstclub.membership=WARN",
    // ── Keep the rate limiter from interfering with the HTTP test batch ──────
    "rate-limit.capacity=100000"
})
@DisplayName("FirstClub Membership Program — Integration Tests")
class MembershipApplicationTests {

    @Autowired private MembershipService membershipService;
    @Autowired private PlanService planService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UserService userService;
    @Autowired private TierEvaluationService tierEvaluationService;
    @Autowired private EarnedTierService earnedTierService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private CouponService couponService;
    @Autowired private BenefitRuleService benefitRuleService;
    @Autowired private BenefitEngine benefitEngine;
    @Autowired private EntitlementsService entitlementsService;
    @Autowired private SavingsService savingsService;
    @Autowired private MembershipTierRepository tierRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private OutboxEventService outboxEventService;
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

        @Test @DisplayName("Tiers expose entity-backed configurable benefits")
        void configurableBenefits() {
            TierDTO gold = membershipService.getTierByName("GOLD").orElseThrow();
            assertThat(gold.getConfiguredBenefits()).isNotEmpty();
            assertThat(gold.getConfiguredBenefits()).extracting(BenefitDTO::getCode)
                    .contains("EXTRA_DISCOUNT", "FREE_DELIVERY", "MONTHLY_COUPONS");
            // The benefit catalog is populated.
            assertThat(membershipService.getBenefitCatalog()).extracting(BenefitDTO::getCode)
                    .contains("PRIORITY_SUPPORT", "EARLY_ACCESS");
        }

        @Test @DisplayName("A benefit can be attached to a tier at runtime")
        void assignBenefitAtRuntime() {
            // Silver has no priority support by default; attach it at runtime.
            TierDTO updated = membershipService.assignBenefitToTier("SILVER", "PRIORITY_SUPPORT", null);
            assertThat(updated.getConfiguredBenefits()).extracting(BenefitDTO::getCode)
                    .contains("PRIORITY_SUPPORT");
        }

        @Test @DisplayName("Admin can create a plan and deactivate it")
        void planAdminLifecycle() {
            int before = planService.getActivePlans().size();
            MembershipPlanDTO created = planService.createPlan(new CreatePlanRequest(
                    "GOLD", "Gold Biennial", "Two-year Gold", MembershipPlan.PlanType.YEARLY,
                    new BigDecimal("8999.00"), 24));
            assertThat(created.getId()).isNotNull();
            assertThat(planService.getActivePlans()).hasSize(before + 1);

            planService.deactivatePlan(created.getId());
            assertThat(planService.getActivePlans()).hasSize(before);
        }

        @Test @DisplayName("A benefit can be created in the catalog and detached from a tier")
        void benefitLifecycle() {
            BenefitDTO created = membershipService.createBenefit(BenefitDTO.builder()
                    .code("GIFT_WRAP").name("Gift wrap").description("Free gift wrapping")
                    .category(Benefit.Category.REWARDS).build());
            assertThat(created.getCode()).isEqualTo("GIFT_WRAP");

            membershipService.assignBenefitToTier("GOLD", "GIFT_WRAP", null);
            TierDTO afterRemove = membershipService.removeBenefitFromTier("GOLD", "GIFT_WRAP");
            assertThat(afterRemove.getConfiguredBenefits()).extracting(BenefitDTO::getCode)
                    .doesNotContain("GIFT_WRAP");
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

        @Test @DisplayName("Downgrade subscription to lower tier")
        void downgradeSubscription() {
            UserDTO user = userService.createUser(testUser("Downgrade User", "downgrade"));
            MembershipPlanDTO goldPlan = planOf("GOLD", MembershipPlan.PlanType.MONTHLY);
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(goldPlan.getId()).autoRenewal(false).build());

            MembershipPlanDTO silverPlan = planOf("SILVER", MembershipPlan.PlanType.MONTHLY);
            SubscriptionDTO downgraded = subscriptionService.downgradeSubscription(sub.getId(), silverPlan.getId());

            assertThat(downgraded.getTier()).isEqualTo("SILVER");
            assertThat(downgraded.getTierLevel()).isEqualTo(1);
            assertThat(downgraded.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
        }

        @Test @DisplayName("Idempotency-Key replays the original create instead of acting twice")
        void idempotentCreateReplays() {
            UserDTO user = userService.createUser(testUser("Idem User", "idem"));
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(true).build();

            SubscriptionDTO first = subscriptionService.createSubscription(req, "key-abc");
            SubscriptionDTO replay = subscriptionService.createSubscription(req, "key-abc");

            // Same subscription returned, not a second one (which would hit the active-sub guard).
            assertThat(replay.getId()).isEqualTo(first.getId());
            assertThat(subscriptionService.getUserSubscriptions(user.getId(), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(1);
        }

        @Test @DisplayName("Idempotency-Key reused with a different request is rejected")
        void idempotentKeyConflict() {
            UserDTO user = userService.createUser(testUser("Idem Conflict User", "idemc"));
            subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(true).build(),
                    "key-xyz");

            // Same key, different plan → conflict.
            MembershipPlanDTO gold = planOf("GOLD", MembershipPlan.PlanType.MONTHLY);
            assertThatThrownBy(() -> subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(gold.getId()).autoRenewal(true).build(),
                    "key-xyz"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Idempotency-Key");
        }

        @Test @DisplayName("Lifecycle events are recorded as an append-only billing log")
        void lifecycleEventsRecorded() {
            UserDTO user = userService.createUser(testUser("Events User", "events"));
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(false).build());
            subscriptionService.cancelSubscription(sub.getId(), "done");

            List<SubscriptionEventDTO> events = subscriptionService.getSubscriptionEvents(sub.getId());
            assertThat(events).extracting(SubscriptionEventDTO::getEventType)
                    .containsExactly(SubscriptionEvent.EventType.CREATED, SubscriptionEvent.EventType.CANCELLED);
            // The CREATED event captures the charge; CANCELLED carries no money movement.
            assertThat(events.get(0).getAmount()).isEqualByComparingTo(sub.getPaidAmount());
            assertThat(events.get(1).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test @DisplayName("Dead-letter outbox events can be replayed (requeued to PENDING)")
        void deadLetterReplay() {
            OutboxEvent dead = outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("SUBSCRIPTION").aggregateId(999_999L).eventType("TEST")
                    .payload("{}").status(OutboxEvent.Status.DEAD)
                    .createdAt(java.time.LocalDateTime.now()).build());

            int replayed = outboxEventService.replayDead();
            assertThat(replayed).isGreaterThanOrEqualTo(1);
            // No longer DEAD (the relay may already have re-dispatched it to DISPATCHED).
            assertThat(outboxEventRepository.findById(dead.getId()).orElseThrow().getStatus())
                    .isNotEqualTo(OutboxEvent.Status.DEAD);
        }

        @Test @DisplayName("Lifecycle changes write transactional-outbox events")
        void outboxEventsWritten() {
            UserDTO user = userService.createUser(testUser("Outbox User", "outbox"));
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(silverMonthly().getId()).autoRenewal(false).build());

            // An outbox row exists for the created subscription (status may already be DISPATCHED
            // if the relay ran — we only assert the durable record was written).
            List<OutboxEvent> outbox = outboxEventRepository.findByAggregateTypeAndAggregateId("SUBSCRIPTION", sub.getId());
            assertThat(outbox).isNotEmpty();
            assertThat(outbox).anyMatch(e -> e.getEventType().equals("CREATED"));
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

        @Test @DisplayName("isEligibleForTier is consistent with evaluateEligibleTier")
        void eligibilityConsistency() {
            UserDTO user = userService.createUser(testUser("Consistency User", "consist"));
            TierEligibilityResult result = tierEvaluationService.evaluateEligibleTier(user.getId());
            // The computed eligible tier must pass the point check too
            assertThat(tierEvaluationService.isEligibleForTier(user.getId(), result.getEligibleTierName())).isTrue();
        }

        @Test @DisplayName("Evaluation note describes the criteria applied")
        void evaluationNoteIsInformative() {
            UserDTO user = userService.createUser(testUser("Note User", "note"));
            TierEligibilityResult result = tierEvaluationService.evaluateEligibleTier(user.getId());
            assertThat(result.getEvaluationNote()).isNotBlank();
        }

        @Test @DisplayName("isEligibleForTier throws MembershipException for unknown tier name")
        void invalidTierThrowsMembershipException() {
            UserDTO user = userService.createUser(testUser("Invalid Tier User", "invtier"));
            assertThatThrownBy(() -> tierEvaluationService.isEligibleForTier(user.getId(), "DIAMOND"))
                    .isInstanceOf(MembershipException.class);
        }

        @Test @DisplayName("earned tier is assigned and matches the eligibility evaluation")
        void earnedTierAssigned() {
            UserDTO user = userService.createUser(testUser("Earned Tier User", "earned"));
            UserTierAssignmentDTO assignment = earnedTierService.assignEarnedTier(user.getId());

            assertThat(assignment.getUserId()).isEqualTo(user.getId());
            assertThat(assignment.getEarnedTierName()).isIn("SILVER", "GOLD", "PLATINUM");
            assertThat(assignment.getSource()).isNotNull();
            // Earned tier must equal the engine's evaluation for the same user.
            TierEligibilityResult eval = tierEvaluationService.evaluateEligibleTier(user.getId());
            assertThat(assignment.getEarnedTierName()).isEqualTo(eval.getEligibleTierName());
        }

        @Test @DisplayName("getEarnedTier computes and persists on first read, stable thereafter")
        void earnedTierLazyComputed() {
            UserDTO user = userService.createUser(testUser("Earned Lazy User", "earnedlazy"));
            String first = earnedTierService.getEarnedTier(user.getId()).orElseThrow().getEarnedTierName();
            String second = earnedTierService.getEarnedTier(user.getId()).orElseThrow().getEarnedTierName();
            assertThat(second).isEqualTo(first);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Exception Handling
    // ═════════════════════════════════════════════════════════════════════════

    @Nested @DisplayName("Checkout — benefit application")
    class CheckoutTests {

        @Test @DisplayName("active Gold member gets the discount and free delivery applied")
        void quoteAppliesGoldBenefits() {
            UserDTO user = userService.createUser(testUser("Checkout Gold", "cogold"));
            MembershipPlanDTO gold = planService.getActivePlans().stream()
                    .filter(p -> "GOLD".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();
            subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(gold.getId()).autoRenewal(true).build());

            CheckoutQuoteRequest req = CheckoutQuoteRequest.builder()
                    .userId(user.getId())
                    .items(List.of(new QuoteLineItem("Phone", "ELECTRONICS", new BigDecimal("1000.00"), 1)))
                    .deliveryFee(new BigDecimal("49.00"))
                    .build();
            CheckoutQuoteResponse r = checkoutService.quote(req);

            assertThat(r.getMembershipTier()).isEqualTo("GOLD");
            assertThat(r.getSubtotal()).isEqualByComparingTo("1000.00");
            assertThat(r.getDiscountAmount()).isEqualByComparingTo("100.00"); // 10% of 1000
            assertThat(r.isDeliveryWaived()).isTrue();
            assertThat(r.getTotal()).isEqualByComparingTo("900.00"); // 1000 - 100 + 0
        }

        @Test @DisplayName("user without an active membership pays full price + delivery")
        void quoteNoMembership() {
            UserDTO user = userService.createUser(testUser("Checkout None", "conone"));
            CheckoutQuoteRequest req = CheckoutQuoteRequest.builder()
                    .userId(user.getId())
                    .items(List.of(new QuoteLineItem("Phone", "ELECTRONICS", new BigDecimal("1000.00"), 1)))
                    .deliveryFee(new BigDecimal("49.00"))
                    .build();
            CheckoutQuoteResponse r = checkoutService.quote(req);

            assertThat(r.getMembershipTier()).isNull();
            assertThat(r.getDiscountAmount()).isEqualByComparingTo("0.00");
            assertThat(r.isDeliveryWaived()).isFalse();
            assertThat(r.getTotal()).isEqualByComparingTo("1049.00");
        }
    }

    @Nested @DisplayName("Coupons")
    class CouponTests {

        @Test @DisplayName("admin can create a coupon")
        void createCoupon() {
            CouponDTO c = couponService.createCoupon(new CreateCouponRequest(
                    "SAVE50", "Flat 50 off", Coupon.DiscountType.FLAT, new BigDecimal("50"), null, null, null));
            assertThat(c.getCode()).isEqualTo("SAVE50");
            assertThat(c.isActive()).isTrue();
        }

        @Test @DisplayName("redeeming enforces the per-user limit")
        void redeemEnforcesPerUserLimit() {
            UserDTO user = userService.createUser(testUser("Coupon User", "coupon"));
            // Seeded WELCOME10 = 10% off, one per member.
            RedeemCouponResponse first = couponService.redeem("WELCOME10", user.getId(), new BigDecimal("1000.00"));
            assertThat(first.getDiscountAmount()).isEqualByComparingTo("100.00");
            assertThat(first.getPayable()).isEqualByComparingTo("900.00");

            assertThatThrownBy(() -> couponService.redeem("WELCOME10", user.getId(), new BigDecimal("1000.00")))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("already used");
        }

        @Test @DisplayName("checkout confirm places an order and redeems the coupon atomically")
        void confirmPlacesOrderAndRedeems() {
            UserDTO user = userService.createUser(testUser("Confirm User", "confirm"));
            CheckoutQuoteRequest req = CheckoutQuoteRequest.builder()
                    .userId(user.getId())
                    .items(List.of(new QuoteLineItem("Phone", "ELECTRONICS", new BigDecimal("1000.00"), 1)))
                    .deliveryFee(new BigDecimal("49.00"))
                    .couponCode("WELCOME10")
                    .build();

            OrderDTO order = checkoutService.confirm(req);
            assertThat(order.getOrderId()).isNotNull();
            assertThat(order.getCouponDiscount()).isEqualByComparingTo("100.00");
            assertThat(order.getTotal()).isEqualByComparingTo("949.00");

            // Per-user-limit-1 coupon already redeemed → a second confirm fails (and places no order).
            assertThatThrownBy(() -> checkoutService.confirm(req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("already used");
        }

        @Test @DisplayName("checkout quote previews a coupon discount")
        void quotePreviewsCoupon() {
            UserDTO user = userService.createUser(testUser("Coupon Quote User", "couponq"));
            CheckoutQuoteRequest req = CheckoutQuoteRequest.builder()
                    .userId(user.getId())
                    .items(List.of(new QuoteLineItem("Phone", "ELECTRONICS", new BigDecimal("1000.00"), 1)))
                    .deliveryFee(new BigDecimal("49.00"))
                    .couponCode("WELCOME10")
                    .build();
            CheckoutQuoteResponse r = checkoutService.quote(req);
            assertThat(r.getCouponDiscount()).isEqualByComparingTo("100.00"); // 10% of 1000 (no membership)
            assertThat(r.getTotal()).isEqualByComparingTo("949.00");          // 1000 - 100 + 49
        }
    }

    @Nested @DisplayName("Phase 2 — Benefit engine")
    class BenefitEngineTests {

        private CartContext cart(String subtotal, Map<ProductCategory, BigDecimal> categories,
                                 Map<FeeType, BigDecimal> fees) {
            return CartContext.builder().subtotal(new BigDecimal(subtotal))
                    .categorySubtotals(categories).fees(fees).build();
        }

        /** A bare tier with no plans — enough to attach benefit rules and exercise the engine. */
        private MembershipTier freshTier() {
            return tierRepository.save(MembershipTier.builder()
                    .name("PHASE2_" + System.nanoTime()).description("test").level(99)
                    .discountPercentage(BigDecimal.ZERO).freeDelivery(false).exclusiveDeals(false)
                    .earlyAccess(false).prioritySupport(false).maxCouponsPerMonth(0).deliveryDays(5)
                    .additionalBenefits("").build());
        }

        /** Remove a throwaway tier and its rules so shared seeded state stays at exactly 3 tiers. */
        private void cleanup(MembershipTier tier) {
            benefitRuleService.listByTier(tier.getId()).forEach(r -> benefitRuleService.delete(r.getId()));
            tierRepository.delete(tier);
        }

        @Test @DisplayName("checkout applies the fee model and coupon together for a member")
        void checkoutFeesAndCoupon() {
            UserDTO user = userService.createUser(testUser("P2 Gold", "p2gold"));
            MembershipPlanDTO gold = planService.getActivePlans().stream()
                    .filter(p -> "GOLD".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();
            subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(gold.getId()).autoRenewal(true).build());

            CheckoutQuoteRequest req = CheckoutQuoteRequest.builder()
                    .userId(user.getId())
                    .items(List.of(new QuoteLineItem("Phone", "ELECTRONICS", new BigDecimal("1000.00"), 1)))
                    .deliveryFee(new BigDecimal("49.00"))
                    .handlingFee(new BigDecimal("10.00"))
                    .surgeFee(new BigDecimal("20.00"))
                    .couponCode("WELCOME10")
                    .build();
            CheckoutQuoteResponse r = checkoutService.quote(req);

            assertThat(r.getDiscountAmount()).isEqualByComparingTo("100.00"); // GOLD 10%
            assertThat(r.isDeliveryWaived()).isTrue();                        // GOLD free delivery
            assertThat(r.getDeliveryFee()).isEqualByComparingTo("0.00");      // waived → charged 0
            assertThat(r.getHandlingFee()).isEqualByComparingTo("10.00");     // not waived
            assertThat(r.getSurgeFee()).isEqualByComparingTo("20.00");
            assertThat(r.getTotalFees()).isEqualByComparingTo("30.00");
            assertThat(r.getWaivedFees()).contains("DELIVERY");
            assertThat(r.getCouponDiscount()).isEqualByComparingTo("90.00"); // 10% of (1000-100)
            assertThat(r.getTotal()).isEqualByComparingTo("840.00");         // 900 - 90 + 30
        }

        @Test @DisplayName("admin-configured threshold + category rules are read back by the engine")
        void adminConfiguredRulesDriveEngine() {
            MembershipTier tier = freshTier();
            try {
                benefitRuleService.create(BenefitRuleRequest.builder()
                        .tierId(tier.getId()).benefitType(BenefitType.PERCENTAGE_DISCOUNT)
                        .productCategory(ProductCategory.BEAUTY).discountPercentage(new BigDecimal("20.00"))
                        .build());
                benefitRuleService.create(BenefitRuleRequest.builder()
                        .tierId(tier.getId()).benefitType(BenefitType.DELIVERY_FEE_WAIVER)
                        .minCartValue(new BigDecimal("199.00")).build());

                assertThat(benefitRuleService.listByTier(tier.getId())).hasSize(2);

                Map<ProductCategory, BigDecimal> beauty = Map.of(ProductCategory.BEAUTY, new BigDecimal("500.00"));
                Map<FeeType, BigDecimal> delivery = Map.of(FeeType.DELIVERY, new BigDecimal("49.00"));

                // Above the waiver threshold: 20% off beauty + delivery waived.
                BenefitEvaluation above = benefitEngine.evaluate(tier.getId(), cart("500.00", beauty, delivery));
                assertThat(above.getDiscountAmount()).isEqualByComparingTo("100.00");
                assertThat(above.isWaived(FeeType.DELIVERY)).isTrue();

                // Below the waiver threshold: discount still applies, delivery not waived.
                Map<ProductCategory, BigDecimal> smallBeauty = Map.of(ProductCategory.BEAUTY, new BigDecimal("100.00"));
                BenefitEvaluation below = benefitEngine.evaluate(tier.getId(), cart("100.00", smallBeauty, delivery));
                assertThat(below.getDiscountAmount()).isEqualByComparingTo("20.00");
                assertThat(below.isWaived(FeeType.DELIVERY)).isFalse();
            } finally {
                cleanup(tier);
            }
        }

        @Test @DisplayName("deactivating a rule removes it from evaluation")
        void deactivatingRuleRemovesIt() {
            MembershipTier tier = freshTier();
            try {
                BenefitRuleDTO rule = benefitRuleService.create(BenefitRuleRequest.builder()
                        .tierId(tier.getId()).benefitType(BenefitType.PERCENTAGE_DISCOUNT)
                        .discountPercentage(new BigDecimal("10.00")).build());

                Map<FeeType, BigDecimal> noFees = Map.of();
                assertThat(benefitEngine.evaluate(tier.getId(), cart("1000.00", Map.of(), noFees)).getDiscountAmount())
                        .isEqualByComparingTo("100.00");

                benefitRuleService.update(rule.getId(), BenefitRuleRequest.builder()
                        .tierId(tier.getId()).benefitType(BenefitType.PERCENTAGE_DISCOUNT)
                        .discountPercentage(new BigDecimal("10.00")).active(false).build());

                assertThat(benefitEngine.evaluate(tier.getId(), cart("1000.00", Map.of(), noFees)).getDiscountAmount())
                        .isEqualByComparingTo("0.00");
            } finally {
                cleanup(tier);
            }
        }

        @Test @DisplayName("entitlements reflect a member's configured fee waivers")
        void entitlementsReflectRules() {
            UserDTO user = userService.createUser(testUser("P2 Ent", "p2ent"));
            MembershipPlanDTO gold = planService.getActivePlans().stream()
                    .filter(p -> "GOLD".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();
            subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(gold.getId()).autoRenewal(true).build());

            EntitlementsDTO ent = entitlementsService.getEntitlements(user.getId());
            assertThat(ent.isMember()).isTrue();
            assertThat(ent.getFeeWaivers()).contains("DELIVERY"); // GOLD baseline delivery waiver
        }
    }

    @Nested @DisplayName("Phase 3 — Growth & retention")
    class GrowthRetentionTests {

        private MembershipPlanDTO goldMonthly() {
            return planService.getActivePlans().stream()
                    .filter(p -> "GOLD".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();
        }

        private Long startTrial(Long userId, Long planId, int days, boolean autoRenew) {
            return subscriptionService.startTrial(TrialRequest.builder()
                    .userId(userId).planId(planId).trialDays(days).autoRenewal(autoRenew).build()).getId();
        }

        /** Force a trial's window into the past so the conversion job picks it up (clock is real). */
        private void expireTrialWindow(Long subscriptionId) {
            Subscription s = subscriptionRepository.findById(subscriptionId).orElseThrow();
            s.setTrialEndDate(LocalDateTime.now().minusDays(1));
            s.setEndDate(LocalDateTime.now().minusDays(1));
            subscriptionRepository.save(s);
        }

        @Test @DisplayName("starting a trial grants active membership without a charge")
        void trialGrantsMembership() {
            UserDTO user = userService.createUser(testUser("Trial", "trial"));
            SubscriptionDTO sub = subscriptionService.startTrial(TrialRequest.builder()
                    .userId(user.getId()).planId(goldMonthly().getId()).trialDays(14).autoRenewal(true).build());

            assertThat(sub.getTrial()).isTrue();
            assertThat(sub.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(sub.getPaidAmount()).isEqualByComparingTo("0");
            assertThat(entitlementsService.getEntitlements(user.getId()).isMember()).isTrue();
        }

        @Test @DisplayName("only 7/14/30-day trials are allowed")
        void trialLengthValidated() {
            UserDTO user = userService.createUser(testUser("Trial Bad", "trialbad"));
            assertThatThrownBy(() -> subscriptionService.startTrial(TrialRequest.builder()
                    .userId(user.getId()).planId(goldMonthly().getId()).trialDays(10).autoRenewal(true).build()))
                    .isInstanceOf(MembershipException.class);
        }

        @Test @DisplayName("a trial auto-converts to a paid subscription at the trial end")
        void trialConverts() {
            UserDTO user = userService.createUser(testUser("Convert", "convert3"));
            BigDecimal price = goldMonthly().getPrice();
            Long id = startTrial(user.getId(), goldMonthly().getId(), 7, true);

            expireTrialWindow(id);
            subscriptionService.processTrialConversions();

            Subscription converted = subscriptionRepository.findById(id).orElseThrow();
            assertThat(converted.getTrial()).isFalse();
            assertThat(converted.getTrialConverted()).isTrue();
            assertThat(converted.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(converted.getPaidAmount()).isEqualByComparingTo(price);
        }

        @Test @DisplayName("the renewal job never touches a trial (no double billing)")
        void renewalJobIgnoresTrials() {
            UserDTO user = userService.createUser(testUser("NoRenew", "norenew3"));
            Long id = startTrial(user.getId(), goldMonthly().getId(), 30, true);

            // Make the trial look due for renewal (past billing date) while its trial window is
            // still open — only the trial-conversion job should ever bill it.
            Subscription s = subscriptionRepository.findById(id).orElseThrow();
            s.setNextBillingDate(LocalDateTime.now().minusDays(1));
            subscriptionRepository.save(s);

            subscriptionService.processRenewals();

            Subscription after = subscriptionRepository.findById(id).orElseThrow();
            assertThat(after.getTrial()).isTrue();                 // still a trial — not renewed
            assertThat(after.getPaidAmount()).isEqualByComparingTo("0"); // never charged
        }

        @Test @DisplayName("a trial without auto-renewal expires at the trial end")
        void trialExpires() {
            UserDTO user = userService.createUser(testUser("Expire", "expire3"));
            Long id = startTrial(user.getId(), goldMonthly().getId(), 7, false);

            expireTrialWindow(id);
            subscriptionService.processTrialConversions();

            Subscription expired = subscriptionRepository.findById(id).orElseThrow();
            assertThat(expired.getStatus()).isEqualTo(Subscription.SubscriptionStatus.EXPIRED);
            assertThat(expired.getTrialConverted()).isFalse();
        }

        @Test @DisplayName("a trial cannot be manually upgraded (would double-bill at conversion)")
        void trialNotUpgradable() {
            UserDTO user = userService.createUser(testUser("NoUpgrade", "noupgrade"));
            Long id = startTrial(user.getId(), goldMonthly().getId(), 14, true);
            MembershipPlanDTO platinum = planService.getActivePlans().stream()
                    .filter(p -> "PLATINUM".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();

            assertThatThrownBy(() -> subscriptionService.upgradeSubscription(id, platinum.getId()))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("trial");
        }

        @Test @DisplayName("a trial can be cancelled")
        void trialCancelled() {
            UserDTO user = userService.createUser(testUser("CancelTrial", "canceltrial"));
            Long id = startTrial(user.getId(), goldMonthly().getId(), 30, true);

            SubscriptionDTO cancelled = subscriptionService.cancelSubscription(id, "changed mind");
            assertThat(cancelled.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
        }

        @Test @DisplayName("introductory offers discount the first period and record the savings")
        void introductoryPricing() {
            BigDecimal price = goldMonthly().getPrice();

            UserDTO free = userService.createUser(testUser("Free", "introfree"));
            SubscriptionDTO freeSub = subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(free.getId()).planId(goldMonthly().getId()).autoRenewal(true)
                    .introOfferCode("FREEMONTH").build());
            assertThat(freeSub.getPaidAmount()).isEqualByComparingTo("0");
            assertThat(savingsService.getUserSavings(free.getId()).getByBenefitType().get("INTRO_OFFER"))
                    .isEqualByComparingTo(price);

            UserDTO rupee = userService.createUser(testUser("Rupee", "introrupee"));
            SubscriptionDTO rupeeSub = subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(rupee.getId()).planId(goldMonthly().getId()).autoRenewal(true)
                    .introOfferCode("FIRSTMONTH1").build());
            assertThat(rupeeSub.getPaidAmount()).isEqualByComparingTo("1.00");

            UserDTO half = userService.createUser(testUser("Half", "introhalf"));
            SubscriptionDTO halfSub = subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(half.getId()).planId(goldMonthly().getId()).autoRenewal(true)
                    .introOfferCode("HALFOFF").build());
            assertThat(halfSub.getPaidAmount()).isEqualByComparingTo(price.divide(new BigDecimal("2")));
        }

        @Test @DisplayName("savings tracker aggregates discounts, waived fees and coupon accurately")
        void savingsAccuracy() {
            UserDTO user = userService.createUser(testUser("Saver", "saver"));
            subscriptionService.createSubscription(SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(goldMonthly().getId()).autoRenewal(true).build());

            // GOLD: 10% discount + free delivery. Cart 1000 electronics, delivery 49, coupon WELCOME10.
            checkoutService.confirm(CheckoutQuoteRequest.builder()
                    .userId(user.getId())
                    .items(List.of(new QuoteLineItem("Phone", "ELECTRONICS", new BigDecimal("1000.00"), 1)))
                    .deliveryFee(new BigDecimal("49.00"))
                    .couponCode("WELCOME10")
                    .build());

            SavingsSummaryDTO savings = savingsService.getUserSavings(user.getId());
            assertThat(savings.getByBenefitType().get("MEMBERSHIP_DISCOUNT")).isEqualByComparingTo("100.00");
            assertThat(savings.getByBenefitType().get("DELIVERY_FEE")).isEqualByComparingTo("49.00");
            assertThat(savings.getByBenefitType().get("COUPON")).isEqualByComparingTo("90.00"); // 10% of 900
            assertThat(savings.getByCategory().get("ELECTRONICS")).isEqualByComparingTo("100.00");
            assertThat(savings.getLifetimeSavings()).isEqualByComparingTo("239.00");
            assertThat(savings.getMonthlySavings()).isEqualByComparingTo("239.00");
        }

        @Test @DisplayName("analytics exposes retention metrics")
        void retentionMetrics() {
            UserDTO user = userService.createUser(testUser("Retain", "retain"));
            startTrial(user.getId(), goldMonthly().getId(), 14, true);

            Map<String, Object> stats = subscriptionService.getAnalyticsStats();
            assertThat(stats).containsKeys("activeMembers", "trialsStarted", "trialsConverted",
                    "trialConversionRate", "averageSavingsPerMember");
            BigDecimal rate = (BigDecimal) stats.get("trialConversionRate");
            assertThat(rate).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        }
    }

    @Nested @DisplayName("Exception Handling")
    class ExceptionTests {

        @Test @DisplayName("User not found throws MembershipException")
        void userNotFound() {
            assertThatThrownBy(() -> subscriptionService.getUserSubscriptions(99999L, PageRequest.of(0, 10)))
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

        private <T> HttpEntity<T> jsonRequest(T body, String token) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            return new HttpEntity<>(body, headers);
        }

        private HttpEntity<Void> authGet(String token) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            return new HttpEntity<>(headers);
        }

        private String login(String username, String password) {
            ResponseEntity<LoginResponse> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/login",
                    jsonRequest(new LoginRequest(username, password)), LoginResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            return resp.getBody().getToken();
        }

        private String userToken()  { return login("demo", "demo123"); }
        private String adminToken() { return login("admin", "admin123"); }

        @Test @DisplayName("POST /users — registration is public")
        void createUserApi() {
            UserDTO req = testUser("API User", "apiuser");
            ResponseEntity<UserDTO> resp = restTemplate.postForEntity(baseUrl() + "/api/v1/users", jsonRequest(req), UserDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getId()).isNotNull();
        }

        @Test @DisplayName("GET /plans — public catalog, returns 9 plans")
        void getAllPlans() {
            ResponseEntity<MembershipPlanDTO[]> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/membership/plans", MembershipPlanDTO[].class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSize(9);
        }

        @Test @DisplayName("GET /tiers — public catalog, returns TierDTO (no entity leak)")
        void getAllTiers() {
            ResponseEntity<TierDTO[]> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/membership/tiers", TierDTO[].class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSize(3);
        }

        @Test @DisplayName("POST /subscriptions — creates subscription (authenticated)")
        void createSubscriptionApi() {
            UserDTO user = userService.createUser(testUser("SubAPI User", "subapi"));
            MembershipPlanDTO plan = planService.getActivePlans().stream()
                    .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();

            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder()
                    .userId(user.getId()).planId(plan.getId()).autoRenewal(true).build();

            // Admin may create for any user (USER may only create for themselves — see ownership tests).
            ResponseEntity<SubscriptionDTO> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions", jsonRequest(req, adminToken()), SubscriptionDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
        }

        @Test @DisplayName("GET /health — public, returns UP")
        void health() {
            ResponseEntity<Object> resp = restTemplate.getForEntity(
                    baseUrl() + "/api/v1/membership/health", Object.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test @DisplayName("GET /users/{id}/tier-eligibility — returns result (admin)")
        void tierEligibilityApi() {
            UserDTO user = userService.createUser(testUser("Elig API User", "eligapi"));
            // Admin is unrestricted by the per-user ownership gate.
            ResponseEntity<TierEligibilityResult> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/users/" + user.getId() + "/tier-eligibility",
                    HttpMethod.GET, authGet(adminToken()), TierEligibilityResult.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getEligibleTierName()).isIn("SILVER", "GOLD", "PLATINUM");
        }

        @Test @DisplayName("a USER may access only their own user-scoped resources (self 200, other 403)")
        void ownershipEnforced() {
            RegisterRequest a = register("owna");
            RegisterRequest b = register("ownb");
            String tokenA = login(a.getEmail(), a.getPassword());
            Long idA = userService.getUserByEmail(a.getEmail()).orElseThrow().getId();
            Long idB = userService.getUserByEmail(b.getEmail()).orElseThrow().getId();

            ResponseEntity<String> own = restTemplate.exchange(
                    baseUrl() + "/api/v1/users/" + idA + "/tier-eligibility",
                    HttpMethod.GET, authGet(tokenA), String.class);
            assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> other = restTemplate.exchange(
                    baseUrl() + "/api/v1/users/" + idB + "/tier-eligibility",
                    HttpMethod.GET, authGet(tokenA), String.class);
            assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test @DisplayName("a USER cannot cancel another user's subscription via /membership (403)")
        void subscriptionOwnershipEnforced() {
            RegisterRequest a = register("subown-a");
            RegisterRequest b = register("subown-b");
            String tokenA = login(a.getEmail(), a.getPassword());
            String tokenB = login(b.getEmail(), b.getPassword());
            Long idA = userService.getUserByEmail(a.getEmail()).orElseThrow().getId();
            Long planId = planService.getActivePlans().stream()
                    .filter(p -> "SILVER".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow().getId();

            // A creates their own subscription.
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder().userId(idA).planId(planId).autoRenewal(true).build();
            ResponseEntity<SubscriptionDTO> created = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions", jsonRequest(req, tokenA), SubscriptionDTO.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long subId = created.getBody().getId();

            // B must not be able to cancel A's subscription.
            ResponseEntity<String> bCancels = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/subscriptions/" + subId + "/cancel",
                    HttpMethod.PUT, jsonRequest(Map.of(), tokenB), String.class);
            assertThat(bCancels.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // A can cancel their own.
            ResponseEntity<SubscriptionDTO> aCancels = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/subscriptions/" + subId + "/cancel",
                    HttpMethod.PUT, jsonRequest(Map.of(), tokenA), SubscriptionDTO.class);
            assertThat(aCancels.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test @DisplayName("a USER cannot create a subscription for another user (403)")
        void cannotCreateForAnotherUser() {
            RegisterRequest a = register("crossa");
            String tokenA = login(a.getEmail(), a.getPassword());
            // Some other user's id (admin-created).
            UserDTO other = userService.createUser(testUser("Cross Other", "crossother"));
            Long planId = planService.getActivePlans().get(0).getId();

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions",
                    jsonRequest(SubscriptionRequestDTO.builder().userId(other.getId()).planId(planId).autoRenewal(true).build(), tokenA),
                    String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        private RegisterRequest register(String prefix) {
            RegisterRequest r = new RegisterRequest("Owner " + prefix, uniqueEmail(prefix),
                    "password123", "9876543210", "1 Test Street", "Mumbai", "Maharashtra", "400001");
            ResponseEntity<UserDTO> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/register", jsonRequest(r), UserDTO.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return r;
        }

        @Test @DisplayName("GET /users/99999 — 404 (admin; ownership bypassed)")
        void userNotFound() {
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/users/99999", HttpMethod.GET, authGet(adminToken()), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("POST /subscriptions with null IDs — 400 (authenticated)")
        void invalidSubscriptionRequest() {
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder().userId(null).planId(null).build();
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions", jsonRequest(req, userToken()), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test @DisplayName("PUT /subscriptions/{id}/cancel — cancels subscription (authenticated)")
        void cancelSubscriptionApi() {
            UserDTO user = userService.createUser(testUser("Cancel API User", "cancelapi"));
            MembershipPlanDTO plan = planService.getActivePlans().get(0);
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(plan.getId()).autoRenewal(true).build());

            Map<String, String> body = new HashMap<>();
            body.put("reason", "API test cancellation");

            ResponseEntity<SubscriptionDTO> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/subscriptions/" + sub.getId() + "/cancel",
                    HttpMethod.PUT, jsonRequest(body, adminToken()), SubscriptionDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
        }

        @Test @DisplayName("PUT /subscriptions/{id}/upgrade with missing newPlanId — 400 (authenticated)")
        void upgradeWithMissingPlanId() {
            UserDTO user = userService.createUser(testUser("Upgrade NPE User", "upgradenpe"));
            MembershipPlanDTO plan = planService.getActivePlans().stream()
                    .filter(p -> "SILVER".equals(p.getTier()) && p.getType() == MembershipPlan.PlanType.MONTHLY)
                    .findFirst().orElseThrow();
            SubscriptionDTO sub = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(user.getId()).planId(plan.getId()).autoRenewal(true).build());

            // Send body without the required newPlanId field — expect 400 not 500
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/subscriptions/" + sub.getId() + "/upgrade",
                    HttpMethod.PUT, jsonRequest(Map.of(), userToken()), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test @DisplayName("PATCH /users/{id} with invalid status value — 400 not 500 (admin)")
        void patchInvalidStatus() {
            UserDTO user = userService.createUser(testUser("Patch Status User", "patchstatus"));
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/users/" + user.getId(),
                    HttpMethod.PATCH, jsonRequest(Map.of("status", "BANNED"), adminToken()), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test @DisplayName("GET /analytics — admin only, returns expected structure")
        void analyticsAggregatePath() {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/analytics", HttpMethod.GET, authGet(adminToken()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).containsKeys("revenue", "membership", "summary");
        }

        // ─── Security ───────────────────────────────────────────────────────────

        @Test @DisplayName("login with valid credentials returns a JWT")
        void loginReturnsToken() {
            String token = adminToken();
            assertThat(token).isNotBlank();
        }

        @Test @DisplayName("refresh token exchanges for a new access token; logout revokes it")
        void refreshAndLogout() {
            ResponseEntity<LoginResponse> login = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/login", jsonRequest(new LoginRequest("admin", "admin123")), LoginResponse.class);
            String refresh = login.getBody().getRefreshToken();
            assertThat(refresh).isNotBlank();

            ResponseEntity<LoginResponse> refreshed = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/refresh", jsonRequest(new RefreshRequest(refresh)), LoginResponse.class);
            assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(refreshed.getBody().getToken()).isNotBlank();

            // The rotated (old) refresh token can no longer be used.
            ResponseEntity<String> reuseOld = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/refresh", jsonRequest(new RefreshRequest(refresh)), String.class);
            assertThat(reuseOld.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // Logout revokes the new refresh token.
            String newRefresh = refreshed.getBody().getRefreshToken();
            restTemplate.postForEntity(baseUrl() + "/api/v1/auth/logout", jsonRequest(new RefreshRequest(newRefresh)), Void.class);
            ResponseEntity<String> afterLogout = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/refresh", jsonRequest(new RefreshRequest(newRefresh)), String.class);
            assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test @DisplayName("reusing a rotated refresh token revokes the whole chain (persisted)")
        void refreshReuseRevokesChain() {
            RegisterRequest u = register("chain");
            String refresh1 = restTemplate.postForEntity(baseUrl() + "/api/v1/auth/login",
                    jsonRequest(new LoginRequest(u.getEmail(), u.getPassword())), LoginResponse.class).getBody().getRefreshToken();

            String refresh2 = restTemplate.postForEntity(baseUrl() + "/api/v1/auth/refresh",
                    jsonRequest(new RefreshRequest(refresh1)), LoginResponse.class).getBody().getRefreshToken();

            // Reuse the rotated (revoked) token — must revoke the chain and persist that.
            ResponseEntity<String> reuse = restTemplate.postForEntity(baseUrl() + "/api/v1/auth/refresh",
                    jsonRequest(new RefreshRequest(refresh1)), String.class);
            assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // The newer token must now ALSO be rejected (proves the revocation committed).
            ResponseEntity<String> newToken = restTemplate.postForEntity(baseUrl() + "/api/v1/auth/refresh",
                    jsonRequest(new RefreshRequest(refresh2)), String.class);
            assertThat(newToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test @DisplayName("repeated failed logins lock the account (429)")
        void loginLockout() {
            // Register a user so locking them out doesn't affect the shared admin/demo accounts.
            RegisterRequest u = register("lockout");
            for (int i = 0; i < 5; i++) {
                restTemplate.postForEntity(baseUrl() + "/api/v1/auth/login",
                        jsonRequest(new LoginRequest(u.getEmail(), "wrong-password")), String.class);
            }
            // Even the correct password is now rejected with a lockout status.
            ResponseEntity<String> locked = restTemplate.postForEntity(baseUrl() + "/api/v1/auth/login",
                    jsonRequest(new LoginRequest(u.getEmail(), u.getPassword())), String.class);
            assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test @DisplayName("login with bad credentials — 401")
        void loginBadCredentials() {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/auth/login",
                    jsonRequest(new LoginRequest("admin", "wrong")), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test @DisplayName("auth events are audited — failure persists despite the 401 rollback")
        void authAudited() {
            adminToken(); // a LOGIN_SUCCESS
            restTemplate.postForEntity(baseUrl() + "/api/v1/auth/login",
                    jsonRequest(new LoginRequest("admin", "definitely-wrong")), String.class); // a LOGIN_FAILURE
            assertThat(auditEventRepository.findAll()).extracting(AuditEvent::getAction)
                    .contains("LOGIN_SUCCESS", "LOGIN_FAILURE");
        }

        @Test @DisplayName("protected endpoint without a token — 401")
        void protectedRequiresToken() {
            SubscriptionRequestDTO req = SubscriptionRequestDTO.builder().userId(1L).planId(1L).autoRenewal(true).build();
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/membership/subscriptions", jsonRequest(req), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test @DisplayName("admin endpoint with a USER token — 403")
        void adminEndpointForbiddenForUser() {
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl() + "/api/v1/membership/analytics", HttpMethod.GET, authGet(userToken()), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
