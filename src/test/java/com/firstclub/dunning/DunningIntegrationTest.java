package com.firstclub.dunning;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.service.DunningService;
import com.firstclub.dunning.service.RenewalService;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the dunning and renewal lifecycle.
 *
 * <p>Uses a live PostgreSQL container (Testcontainers) and a mocked
 * {@link PaymentGatewayPort} to control charge outcomes deterministically.
 *
 * <h3>Test 1 — renewal fails → PAST_DUE + dunning schedule</h3>
 * Subscription with elapsed {@code next_renewal_at} → renewal service triggers
 * a charge that fails → subscription becomes PAST_DUE and 4 SCHEDULED dunning
 * attempts are created.
 *
 * <h3>Test 2 — dunning retry succeeds → ACTIVE + invoice PAID</h3>
 * Subscription in PAST_DUE with one due dunning attempt → dunning service
 * triggers a charge that succeeds → subscription becomes ACTIVE and the
 * associated invoice is marked PAID.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DunningIntegrationTest extends PostgresIntegrationTestBase {

    // @MockitoBean replaces SimulatedPaymentGateway with a controllable mock
    @MockitoBean
    PaymentGatewayPort paymentGatewayPort;

    @Autowired private RenewalService           renewalService;
    @Autowired private DunningService           dunningService;
    @Autowired private SubscriptionRepository   subscriptionRepository;
    @Autowired private DunningAttemptRepository dunningAttemptRepository;
    @Autowired private InvoiceRepository        invoiceRepository;
    @Autowired private UserRepository           userRepository;
    @Autowired private MembershipTierRepository tierRepository;
    @Autowired private MembershipPlanRepository planRepository;

    private User           testUser;
    private MembershipPlan testPlan;

    @BeforeAll
    void setUpFixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        MembershipTier tier = MembershipTier.builder()
                .name("DUNNING_TIER_" + suffix)
                .description("Dunning integration-test tier")
                .level(1)
                .discountPercentage(new BigDecimal("5.00"))
                .freeDelivery(false).exclusiveDeals(false)
                .earlyAccess(false).prioritySupport(false)
                .maxCouponsPerMonth(2).deliveryDays(5)
                .build();
        tier = tierRepository.save(tier);

        testPlan = MembershipPlan.builder()
                .name("Dunning Test Monthly " + suffix)
                .description("Monthly plan for dunning integration tests")
                .type(MembershipPlan.PlanType.MONTHLY)
                .price(new BigDecimal("299.00"))
                .durationInMonths(1)
                .isActive(true)
                .tier(tier)
                .build();
        testPlan = planRepository.save(testPlan);

        testUser = User.builder()
                .email("dunning-test-" + suffix + "@test.com")
                .password("$2a$10$notUsedInTests_hashedPlaceholder")
                .name("Dunning Test User")
                .phoneNumber("9000000099")
                .address("1 Dunning Lane")
                .city("Bengaluru")
                .state("Karnataka")
                .pincode("560001")
                .status(User.UserStatus.ACTIVE)
                .build();
        testUser = userRepository.save(testUser);
    }

    // -------------------------------------------------------------------------
    // Test 1 — renewal charge fails → PAST_DUE + dunning scheduled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("renewal charge failure → subscription PAST_DUE with 4 SCHEDULED dunning attempts")
    void renewalChargeFails_setsPastDueAndSchedulesDunning() {
        // Arrange: gateway always declines for this test
        when(paymentGatewayPort.charge(any())).thenReturn(ChargeOutcome.FAILED);

        LocalDateTime now = LocalDateTime.now();
        Subscription sub = subscriptionRepository.save(Subscription.builder()
                .user(testUser)
                .plan(testPlan)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(now.minusMonths(1))
                .endDate(now.minusDays(1))
                .nextBillingDate(now.minusDays(1))
                .nextRenewalAt(now.minusDays(1))     // past due
                .paidAmount(testPlan.getPrice())
                .autoRenewal(true)
                .cancelAtPeriodEnd(false)
                .build());

        // Act
        renewalService.processRenewal(sub.getId());

        // Assert — subscription status
        Subscription updated = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(updated.getGraceUntil()).isNotNull().isAfter(now);

        // Assert — invoice created and still OPEN (payment failed)
        List<Invoice> invoices = invoiceRepository.findBySubscriptionId(sub.getId());
        assertThat(invoices).as("Exactly one renewal invoice should exist").hasSize(1);
        assertThat(invoices.get(0).getStatus()).isEqualTo(InvoiceStatus.OPEN);

        // Assert — 4 SCHEDULED dunning attempts
        List<DunningAttempt> attempts = dunningAttemptRepository.findBySubscriptionId(sub.getId());
        assertThat(attempts).hasSize(4);
        assertThat(attempts).allMatch(a -> a.getStatus() == DunningStatus.SCHEDULED);
        assertThat(attempts).extracting(DunningAttempt::getAttemptNumber)
                .containsExactlyInAnyOrder(1, 2, 3, 4);

        // Assert schedules are in ascending order (+1h < +6h < +24h < +3d from failure time)
        List<LocalDateTime> scheduledTimes = attempts.stream()
                .map(DunningAttempt::getScheduledAt).sorted().toList();
        for (int i = 1; i < scheduledTimes.size(); i++) {
            assertThat(scheduledTimes.get(i)).isAfter(scheduledTimes.get(i - 1));
        }
    }

    // -------------------------------------------------------------------------
    // Test 2 — dunning retry succeeds → ACTIVE + invoice PAID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dunning retry charge succeeds → subscription ACTIVE and invoice PAID")
    void dunningRetrySucceeds_activatesSubscriptionAndPaysInvoice() {
        // Arrange: gateway succeeds on the retry
        when(paymentGatewayPort.charge(any())).thenReturn(ChargeOutcome.SUCCESS);

        LocalDateTime now = LocalDateTime.now();

        // Subscription already in PAST_DUE (renewal already failed earlier)
        Subscription sub = subscriptionRepository.save(Subscription.builder()
                .user(testUser)
                .plan(testPlan)
                .status(SubscriptionStatus.PAST_DUE)
                .startDate(now.minusMonths(1))
                .endDate(now)
                .nextBillingDate(now)
                .nextRenewalAt(now)
                .graceUntil(now.plusDays(7))
                .paidAmount(testPlan.getPrice())
                .autoRenewal(true)
                .cancelAtPeriodEnd(false)
                .build());

        // A renewal invoice already exists and is still OPEN
        Invoice invoice = invoiceRepository.save(Invoice.builder()
                .userId(testUser.getId())
                .subscriptionId(sub.getId())
                .status(InvoiceStatus.OPEN)
                .currency("INR")
                .totalAmount(testPlan.getPrice())
                .dueDate(now.plusDays(7))
                .periodStart(now)
                .periodEnd(now.plusMonths(1))
                .build());

        // One dunning attempt is scheduled in the past (due now)
        DunningAttempt attempt = dunningAttemptRepository.save(DunningAttempt.builder()
                .subscriptionId(sub.getId())
                .invoiceId(invoice.getId())
                .attemptNumber(1)
                .scheduledAt(now.minusMinutes(30))   // past — due for processing
                .status(DunningStatus.SCHEDULED)
                .build());

        // Two remaining future attempts (should be cancelled on success)
        dunningAttemptRepository.save(DunningAttempt.builder()
                .subscriptionId(sub.getId()).invoiceId(invoice.getId())
                .attemptNumber(2).scheduledAt(now.plusHours(6))
                .status(DunningStatus.SCHEDULED).build());
        dunningAttemptRepository.save(DunningAttempt.builder()
                .subscriptionId(sub.getId()).invoiceId(invoice.getId())
                .attemptNumber(3).scheduledAt(now.plusHours(24))
                .status(DunningStatus.SCHEDULED).build());

        // Act
        dunningService.processDueAttempts();

        // Assert — subscription reactivated
        Subscription activated = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(activated.getGraceUntil()).isNull();

        // Assert — invoice paid
        Invoice paidInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(paidInvoice.getStatus()).isEqualTo(InvoiceStatus.PAID);

        // Assert — processed attempt is SUCCESS
        DunningAttempt processed = dunningAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(DunningStatus.SUCCESS);

        // Assert — remaining scheduled attempts were cancelled (FAILED)
        List<DunningAttempt> remaining = dunningAttemptRepository
                .findBySubscriptionIdAndStatus(sub.getId(), DunningStatus.SCHEDULED);
        assertThat(remaining).isEmpty();
    }
}
