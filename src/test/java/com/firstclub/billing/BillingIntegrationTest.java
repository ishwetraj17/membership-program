package com.firstclub.billing;

import com.firstclub.billing.dto.SubscriptionV2Response;
import com.firstclub.billing.entity.CreditNote;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.CreditNoteRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.BillingSubscriptionService;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.repository.*;
import com.firstclub.payments.dto.GatewayPayRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Billing module.
 *
 * <p>All tests run against a live PostgreSQL container (Testcontainers) and are
 * skipped automatically when Docker is unavailable.
 *
 * <h3>Test 1 — full billing flow via gateway webhook</h3>
 * Creates a V2 subscription → POST /gateway/pay SUCCEEDED → polls until
 * subscription becomes ACTIVE and invoice becomes PAID.
 *
 * <h3>Test 2 — credit-note covers full invoice</h3>
 * Pre-loads a credit note that covers the plan price → createSubscriptionV2
 * must activate subscription immediately (no PaymentIntent created).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingIntegrationTest extends PostgresIntegrationTestBase {

    // -------------------------------------------------------------------------
    // Autowired dependencies
    // -------------------------------------------------------------------------

    @Autowired private BillingSubscriptionService billingSubscriptionService;
    @Autowired private SubscriptionRepository     subscriptionRepository;
    @Autowired private InvoiceRepository          invoiceRepository;
    @Autowired private UserRepository             userRepository;
    @Autowired private MembershipPlanRepository   planRepository;
    @Autowired private MembershipTierRepository   tierRepository;
    @Autowired private CreditNoteRepository       creditNoteRepository;
    @Autowired private TestRestTemplate           restTemplate;

    // -------------------------------------------------------------------------
    // Shared test fixtures (created once per class)
    // -------------------------------------------------------------------------

    private User          testUser;
    private MembershipPlan testPlan;

    /**
     * Creates one user, one tier, and one plan that are shared across all test
     * methods in this class.  Each test method creates its own subscription.
     *
     * <p>Tier names are unique per class run to avoid conflicts when the Spring
     * application context is shared with other integration test classes.
     */
    @BeforeAll
    void setUpFixtures() {
        // Unique suffix prevents collisions if many test classes share the DB
        String suffix   = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        MembershipTier tier = MembershipTier.builder()
                .name("BILLING_TIER_" + suffix)
                .description("Billing integration-test tier")
                .level(1)
                .discountPercentage(new BigDecimal("5.00"))
                .freeDelivery(false)
                .exclusiveDeals(false)
                .earlyAccess(false)
                .prioritySupport(false)
                .maxCouponsPerMonth(2)
                .deliveryDays(5)
                .build();
        tier = tierRepository.save(tier);

        testPlan = MembershipPlan.builder()
                .name("Billing Test Monthly " + suffix)
                .description("Monthly plan for billing integration tests")
                .type(MembershipPlan.PlanType.MONTHLY)
                .price(new BigDecimal("999.00"))
                .durationInMonths(1)
                .isActive(true)
                .tier(tier)
                .build();
        testPlan = planRepository.save(testPlan);

        testUser = User.builder()
                .email("billing-test-" + suffix + "@test.com")
                .password("$2a$10$notUsedInTests_hashedPlaceholder")
                .name("Billing Test User")
                .phoneNumber("9000000001")
                .address("1 Billing Street")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400001")
                .status(User.UserStatus.ACTIVE)
                .build();
        testUser = userRepository.save(testUser);
    }

    // -------------------------------------------------------------------------
    // Test 1 — full flow (PENDING → payment → ACTIVE)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("full billing flow: PENDING subscription activated via webhook after gateway payment")
    void createSubscriptionV2_gatewaySucceeded_activatesSubscriptionAndPaysInvoice() throws InterruptedException {
        // 1. Create subscription via V2 flow
        SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(testUser.getId())
                .planId(testPlan.getId())
                .autoRenewal(false)
                .build();

        SubscriptionV2Response response = billingSubscriptionService.createSubscriptionV2(request);

        assertThat(response.getSubscriptionId()).isNotNull();
        assertThat(response.getInvoiceId()).isNotNull();
        assertThat(response.getPaymentIntentId()).isNotNull();
        assertThat(response.getAmountDue()).isEqualByComparingTo(testPlan.getPrice());
        assertThat(response.getStatus()).isEqualTo(Subscription.SubscriptionStatus.PENDING);

        // 2. Simulate gateway payment SUCCEEDED → async webhook fires in 2-5s
        ResponseEntity<?> payResp = restTemplate.postForEntity(
                "/gateway/pay",
                new GatewayPayRequest(response.getPaymentIntentId(), "SUCCEEDED"),
                Map.class);
        assertThat(payResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 3. Poll until subscription is ACTIVE (webhook drives the transition)
        Long subId = response.getSubscriptionId();
        boolean activated = pollUntil(
                () -> subscriptionRepository.findById(subId)
                        .map(s -> s.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                        .orElse(false),
                15, TimeUnit.SECONDS);

        assertThat(activated)
                .as("Subscription %d should reach ACTIVE within 15 s", subId)
                .isTrue();

        // 4. Invoice must be PAID
        Long invoiceId = response.getInvoiceId();
        assertThat(invoiceRepository.findById(invoiceId))
                .isPresent()
                .hasValueSatisfying(inv -> assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.PAID));
    }

    // -------------------------------------------------------------------------
    // Test 2 — credit note covers the full amount (immediate activation)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("credits cover full invoice: subscription is activated immediately without a PaymentIntent")
    void createSubscriptionV2_creditsCoverFullAmount_immediateActivation() {
        // Create a second user so credits don't bleed across tests
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User creditUser = userRepository.save(User.builder()
                .email("credit-user-" + suffix + "@test.com")
                .password("$2a$10$notUsedInTests_hashedPlaceholder")
                .name("Credit User " + suffix)
                .phoneNumber("9000000002")
                .address("2 Credit Lane")
                .city("Pune")
                .state("Maharashtra")
                .pincode("411001")
                .status(User.UserStatus.ACTIVE)
                .build());

        // Pre-load a credit note that covers the entire plan price
        creditNoteRepository.save(CreditNote.builder()
                .userId(creditUser.getId())
                .currency("INR")
                .amount(testPlan.getPrice())
                .reason("Test wallet top-up")
                .usedAmount(BigDecimal.ZERO)
                .build());

        // Create subscription — credits should absorb the invoice immediately
        SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(creditUser.getId())
                .planId(testPlan.getId())
                .autoRenewal(false)
                .build();

        SubscriptionV2Response response = billingSubscriptionService.createSubscriptionV2(request);

        // No PaymentIntent needed — credits covered everything
        assertThat(response.getPaymentIntentId())
                .as("paymentIntentId should be null when credits cover the full amount")
                .isNull();
        assertThat(response.getClientSecret()).isNull();
        assertThat(response.getAmountDue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);

        // Subscription must already be ACTIVE in DB
        Long subId = response.getSubscriptionId();
        assertThat(subscriptionRepository.findById(subId))
                .hasValueSatisfying(s ->
                        assertThat(s.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE));

        // Invoice must already be PAID in DB
        Long invoiceId = response.getInvoiceId();
        assertThat(invoiceRepository.findById(invoiceId))
                .hasValueSatisfying(inv ->
                        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.PAID));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private boolean pollUntil(BooleanSupplier condition, int timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
