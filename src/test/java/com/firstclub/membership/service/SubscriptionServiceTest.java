package com.firstclub.membership.service;

import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.IdempotencyRecordRepository;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SubscriptionEventRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.config.MembershipConfig;
import com.firstclub.membership.dto.SubscriptionUpdateDTO;
import com.firstclub.membership.event.OutboxEventService;
import com.firstclub.membership.service.PaymentGateway;
import com.firstclub.membership.service.TierEvaluationService;
import com.firstclub.membership.service.impl.SubscriptionRenewalProcessor;
import com.firstclub.membership.service.impl.SubscriptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService — Unit Tests")
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private MembershipPlanRepository planRepository;
    @Mock private UserService userService;
    @Mock private SubscriptionRenewalProcessor renewalProcessor;
    @Mock private SubscriptionEventRepository eventRepository;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private OutboxEventService outboxEventService;
    @Mock private TierEvaluationService tierEvaluationService;
    @Mock private PaymentGateway paymentGateway;
    @Mock private org.springframework.transaction.PlatformTransactionManager txManager;
    @Mock private com.firstclub.membership.service.SavingsService savingsService;
    @Mock private com.firstclub.membership.service.IntroductoryOfferService introductoryOfferService;
    @Mock private com.firstclub.membership.service.impl.TrialConversionProcessor trialConversionProcessor;
    @Mock private com.firstclub.membership.repository.SavingsLedgerRepository savingsLedgerRepository;

    // Real registry so counter().increment() works without stubbing.
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    // Fixed clock so pro-ration and date arithmetic are fully deterministic.
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);

    private final MembershipConfig membershipConfig = new MembershipConfig();

    private SubscriptionServiceImpl subscriptionService;

    private User testUser;
    private MembershipTier silverTier;
    private MembershipPlan silverMonthly;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionServiceImpl(
                subscriptionRepository, planRepository, userService, renewalProcessor,
                eventRepository, idempotencyRepository, outboxEventService,
                tierEvaluationService, paymentGateway, membershipConfig, txManager,
                savingsService, introductoryOfferService, trialConversionProcessor,
                savingsLedgerRepository, meterRegistry, fixedClock);
        lenient().when(paymentGateway.charge(any(), any(), any()))
                .thenReturn(new PaymentGateway.PaymentResult("pay_test", true));
        lenient().when(paymentGateway.refund(any(), any()))
                .thenReturn(new PaymentGateway.PaymentResult("rfnd_test", true));
        // TransactionTemplate over the mock manager just runs the callback inline.
        lenient().when(txManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());

        silverTier = MembershipTier.builder()
                .id(1L).name("SILVER").level(1)
                .discountPercentage(new BigDecimal("5.00"))
                .freeDelivery(false).exclusiveDeals(false)
                .earlyAccess(false).prioritySupport(false)
                .maxCouponsPerMonth(2).deliveryDays(5)
                .additionalBenefits("Basic perks").build();

        silverMonthly = MembershipPlan.builder()
                .id(1L).name("Silver Monthly")
                .description("Entry level monthly plan")
                .type(MembershipPlan.PlanType.MONTHLY)
                .price(new BigDecimal("299.00"))
                .durationInMonths(1)
                .isActive(true)
                .tier(silverTier).build();

        testUser = User.builder()
                .id(10L).name("Test User")
                .email("test@example.com")
                .status(User.UserStatus.ACTIVE).build();
    }

    // ═══════════════════════════════════════════════════════════
    // createSubscription
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("createSubscription")
    class CreateSubscription {

        @Test @DisplayName("happy path — creates and returns active subscription")
        void success() {
            when(userService.findUserEntityById(10L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.findActiveSubscriptionByUser(eq(testUser), any()))
                    .thenReturn(Optional.empty());

            Subscription saved = buildActiveSubscription();
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(saved);
            when(subscriptionRepository.findById(saved.getId())).thenReturn(Optional.of(saved));

            SubscriptionDTO result = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(10L).planId(1L).autoRenewal(true).build());

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(result.getPaidAmount()).isEqualByComparingTo("299.00");
            assertThat(result.getTier()).isEqualTo("SILVER");
            // saved twice: PENDING (reserve) then ACTIVE (activate after charge).
            verify(subscriptionRepository, atLeastOnce()).save(any(Subscription.class));
            verify(paymentGateway).charge(eq(10L), any(), any());
        }

        @Test @DisplayName("duplicate active subscription — throws MembershipException")
        void duplicateThrows() {
            when(userService.findUserEntityById(10L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.findActiveSubscriptionByUser(eq(testUser), any()))
                    .thenReturn(Optional.of(buildActiveSubscription()));

            assertThatThrownBy(() -> subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(10L).planId(1L).autoRenewal(true).build()))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("already has an active subscription");

            verify(subscriptionRepository, never()).save(any());
        }

        @Test @DisplayName("plan not found — throws MembershipException")
        void planNotFound() {
            when(userService.findUserEntityById(10L)).thenReturn(testUser);
            when(planRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(10L).planId(999L).autoRenewal(true).build()))
                    .isInstanceOf(MembershipException.class);
        }

        @Test @DisplayName("payment decline — subscription is not activated (saga compensates)")
        void paymentDeclineDoesNotActivate() {
            when(userService.findUserEntityById(10L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.findActiveSubscriptionByUser(eq(testUser), any()))
                    .thenReturn(Optional.empty());
            Subscription pending = buildActiveSubscription();
            when(subscriptionRepository.save(any(Subscription.class))).thenReturn(pending);
            when(subscriptionRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(paymentGateway.charge(any(), any(), any()))
                    .thenReturn(new PaymentGateway.PaymentResult(null, false)); // declined

            assertThatThrownBy(() -> subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(10L).planId(1L).autoRenewal(true).build()))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("declined");

            // Never activated → no CREATED event emitted.
            verify(outboxEventService, never()).publish(any(), any(), eq("CREATED"), any());
        }

        @Test @DisplayName("eligibility enforcement on — ineligible user is rejected")
        void enforcementRejectsIneligible() {
            membershipConfig.setEnforceTierEligibility(true);
            when(userService.findUserEntityById(10L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.findActiveSubscriptionByUser(eq(testUser), any()))
                    .thenReturn(Optional.empty());
            when(tierEvaluationService.isEligibleForTier(testUser.getId(), "SILVER")).thenReturn(false);

            assertThatThrownBy(() -> subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(10L).planId(1L).autoRenewal(true).build()))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not eligible");

            verify(subscriptionRepository, never()).save(any());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // cancelSubscription
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("cancelSubscription")
    class CancelSubscription {

        @Test @DisplayName("cancels active subscription successfully")
        void success() {
            Subscription active = buildActiveSubscription();
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.cancelSubscription(active.getId(), "No longer needed");

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            assertThat(result.getCancellationReason()).isEqualTo("No longer needed");
            assertThat(result.getAutoRenewal()).isFalse();
        }

        @Test @DisplayName("cancel with refund-on-cancel enabled issues a pro-rated refund")
        void cancelRefundsWhenEnabled() {
            membershipConfig.setRefundOnCancel(true);
            Subscription active = buildActiveSubscription(); // full period remaining, paid 299
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(subscriptionRepository.save(active)).thenReturn(active);
            when(paymentGateway.refund(any(), any()))
                    .thenReturn(new PaymentGateway.PaymentResult("rfnd_1", true));

            SubscriptionDTO result = subscriptionService.cancelSubscription(active.getId(), "done");

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            verify(paymentGateway).refund(any(), any());
            // A REFUNDED event is published in addition to CANCELLED.
            verify(outboxEventService).publish(any(), any(), eq("REFUNDED"), any());
        }

        @Test @DisplayName("cancelling non-active subscription — throws MembershipException")
        void nonActiveThrows() {
            Subscription cancelled = buildActiveSubscription();
            cancelled.setStatus(Subscription.SubscriptionStatus.CANCELLED);
            when(subscriptionRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

            assertThatThrownBy(() -> subscriptionService.cancelSubscription(cancelled.getId(), "reason"))
                    .isInstanceOf(MembershipException.class);

            verify(subscriptionRepository, never()).save(any());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // upgradeSubscription
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("upgradeSubscription")
    class UpgradeSubscription {

        @Test @DisplayName("upgrade to higher tier — succeeds")
        void upgradeToHigherTier() {
            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold perks").build();

            MembershipPlan goldMonthly = MembershipPlan.builder()
                    .id(4L).name("Gold Monthly")
                    .description("Gold monthly plan")
                    .type(MembershipPlan.PlanType.MONTHLY)
                    .price(new BigDecimal("499.00"))
                    .durationInMonths(1)
                    .isActive(true)
                    .tier(goldTier).build();

            Subscription active = buildActiveSubscription();
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(planRepository.findById(4L)).thenReturn(Optional.of(goldMonthly));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.upgradeSubscription(active.getId(), 4L);

            assertThat(result.getTier()).isEqualTo("GOLD");
            verify(subscriptionRepository).save(active);
        }

        @Test @DisplayName("upgrade extends endDate from current billing period start")
        void upgradeExtendsEndDate() {
            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold perks").build();

            MembershipPlan goldYearly = MembershipPlan.builder()
                    .id(6L).name("Gold Yearly")
                    .description("Gold yearly plan")
                    .type(MembershipPlan.PlanType.YEARLY)
                    .price(new BigDecimal("5089.80"))
                    .durationInMonths(12)
                    .isActive(true)
                    .tier(goldTier).build();

            Subscription active = buildActiveSubscription();
            LocalDateTime expectedEnd = active.getStartDate().plusMonths(12);

            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(planRepository.findById(6L)).thenReturn(Optional.of(goldYearly));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.upgradeSubscription(active.getId(), 6L);

            assertThat(result.getEndDate()).isEqualTo(expectedEnd);
            assertThat(result.getNextBillingDate()).isEqualTo(expectedEnd);
        }

        @Test @DisplayName("upgrade adds pro-rated cost difference to paidAmount")
        void upgradeAddsProratedAdjustment() {
            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold perks").build();

            MembershipPlan goldMonthly = MembershipPlan.builder()
                    .id(4L).name("Gold Monthly")
                    .description("Gold monthly plan")
                    .type(MembershipPlan.PlanType.MONTHLY)
                    .price(new BigDecimal("499.00"))
                    .durationInMonths(1)
                    .isActive(true)
                    .tier(goldTier).build();

            Subscription active = buildActiveSubscription(); // paidAmount = 299.00, just started
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(planRepository.findById(4L)).thenReturn(Optional.of(goldMonthly));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.upgradeSubscription(active.getId(), 4L);

            // Pro-rated adjustment must increase paidAmount above the original Silver price
            assertThat(result.getPaidAmount()).isGreaterThan(new BigDecimal("299.00"));
            // Adjustment cannot exceed the full Silver + Gold price (upper bound)
            assertThat(result.getPaidAmount()).isLessThanOrEqualTo(new BigDecimal("798.00"));
        }

        @Test @DisplayName("mid-period upgrade charges full new price less unused credit (not pro-rated over old period)")
        void upgradeMidPeriodChargesFullNewPriceLessCredit() {
            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold perks").build();

            MembershipPlan goldMonthly = MembershipPlan.builder()
                    .id(4L).name("Gold Monthly").description("Gold monthly")
                    .type(MembershipPlan.PlanType.MONTHLY).price(new BigDecimal("499.00"))
                    .durationInMonths(1).isActive(true).tier(goldTier).build();

            MembershipPlan goldYearly = MembershipPlan.builder()
                    .id(6L).name("Gold Yearly").description("Gold yearly")
                    .type(MembershipPlan.PlanType.YEARLY).price(new BigDecimal("5089.80"))
                    .durationInMonths(12).isActive(true).tier(goldTier).build();

            LocalDateTime now = LocalDateTime.now(fixedClock);
            // 30-day period with exactly 15 days remaining.
            Subscription active = Subscription.builder()
                    .id(300L).user(testUser).plan(goldMonthly)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .startDate(now.minusDays(15)).endDate(now.plusDays(15))
                    .nextBillingDate(now.plusDays(15)).paidAmount(new BigDecimal("499.00"))
                    .autoRenewal(true).version(0L).build();

            when(subscriptionRepository.findById(300L)).thenReturn(Optional.of(active));
            when(planRepository.findById(6L)).thenReturn(Optional.of(goldYearly));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.upgradeSubscription(300L, 6L);

            // unused credit = 499.00 × 15/30 = 249.50; charge = 5089.80 − 249.50 = 4840.30
            // paidAmount = 499.00 + 4840.30 = 5339.30  (old code under-charged: 2794.40)
            assertThat(result.getPaidAmount()).isEqualByComparingTo("5339.30");
            // Full new term granted from now, not pro-rated away.
            assertThat(result.getEndDate()).isEqualTo(now.plusMonths(12));
        }

        @Test @DisplayName("upgrade to higher tier with shorter duration never yields a past endDate")
        void upgradeNeverProducesPastEndDate() {
            MembershipTier silverTierYearly = silverTier;
            MembershipPlan silverYearly = MembershipPlan.builder()
                    .id(3L).name("Silver Yearly").description("Silver yearly")
                    .type(MembershipPlan.PlanType.YEARLY).price(new BigDecimal("3049.80"))
                    .durationInMonths(12).isActive(true).tier(silverTierYearly).build();

            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold perks").build();
            MembershipPlan goldMonthly = MembershipPlan.builder()
                    .id(4L).name("Gold Monthly").description("Gold monthly")
                    .type(MembershipPlan.PlanType.MONTHLY).price(new BigDecimal("499.00"))
                    .durationInMonths(1).isActive(true).tier(goldTier).build();

            LocalDateTime now = LocalDateTime.now(fixedClock);
            BigDecimal originalPaid = new BigDecimal("3049.80");
            // 11 months into a yearly plan — most of the term consumed, large unused-credit case.
            Subscription active = Subscription.builder()
                    .id(301L).user(testUser).plan(silverYearly)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .startDate(now.minusMonths(11)).endDate(now.plusMonths(1))
                    .nextBillingDate(now.plusMonths(1)).paidAmount(originalPaid)
                    .autoRenewal(true).version(0L).build();

            when(subscriptionRepository.findById(301L)).thenReturn(Optional.of(active));
            when(planRepository.findById(4L)).thenReturn(Optional.of(goldMonthly));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.upgradeSubscription(301L, 4L);

            assertThat(result.getEndDate()).isEqualTo(now.plusMonths(1));
            assertThat(result.getEndDate()).isAfter(now);
            // Charge is clamped at zero — a large credit never refunds / reduces paidAmount.
            assertThat(result.getPaidAmount()).isGreaterThanOrEqualTo(originalPaid);
        }

        @Test @DisplayName("downgrade attempt via upgrade path — throws MembershipException")
        void downgradeThrows() {
            Subscription active = buildActiveSubscription();
            active.setPlan(buildPlanForTier(MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold").build()));

            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));

            assertThatThrownBy(() -> subscriptionService.upgradeSubscription(active.getId(), 1L))
                    .isInstanceOf(MembershipException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // downgradeSubscription
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("downgradeSubscription")
    class DowngradeSubscription {

        @Test @DisplayName("downgrade to lower tier changes plan and tier")
        void success() {
            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10.00"))
                    .freeDelivery(true).exclusiveDeals(true)
                    .earlyAccess(true).prioritySupport(false)
                    .maxCouponsPerMonth(5).deliveryDays(3)
                    .additionalBenefits("Gold perks").build();

            Subscription active = buildActiveSubscription();
            active.setPlan(buildPlanForTier(goldTier)); // subscription is currently Gold

            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionDTO result = subscriptionService.downgradeSubscription(active.getId(), 1L);

            assertThat(result.getTier()).isEqualTo("SILVER");
            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            verify(subscriptionRepository).save(active);
        }

        @Test @DisplayName("downgrade to same or higher tier throws MembershipException")
        void sameOrHigherTierThrows() {
            Subscription active = buildActiveSubscription(); // Silver (level 1)
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly)); // same tier

            assertThatThrownBy(() -> subscriptionService.downgradeSubscription(active.getId(), 1L))
                    .isInstanceOf(MembershipException.class);

            verify(subscriptionRepository, never()).save(any());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // renewSubscription
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("renewSubscription")
    class RenewSubscription {

        @Test @DisplayName("renewal activates period from now, resets paidAmount to plan price")
        void renewalResetsToActivePeriod() {
            LocalDateTime oldEnd = LocalDateTime.now(fixedClock).minusDays(5);
            Subscription expired = Subscription.builder()
                    .id(200L)
                    .user(testUser)
                    .plan(silverMonthly)
                    .status(Subscription.SubscriptionStatus.EXPIRED)
                    .startDate(oldEnd.minusMonths(1))
                    .endDate(oldEnd)
                    .nextBillingDate(oldEnd)
                    .paidAmount(new BigDecimal("150.00")) // differs from plan price intentionally
                    .autoRenewal(false)
                    .version(0L)
                    .build();

            when(subscriptionRepository.findById(200L)).thenReturn(Optional.of(expired));
            when(subscriptionRepository.save(expired)).thenReturn(expired);

            SubscriptionDTO result = subscriptionService.renewSubscription(200L);

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            // new period starts after old expiry
            assertThat(result.getStartDate()).isAfter(oldEnd);
            // endDate is strictly after startDate
            assertThat(result.getEndDate()).isAfter(result.getStartDate());
            // nextBillingDate must equal endDate
            assertThat(result.getNextBillingDate()).isEqualTo(result.getEndDate());
            // paidAmount resets to current plan price (Fix 4 regression guard)
            assertThat(result.getPaidAmount()).isEqualByComparingTo(silverMonthly.getPrice());
        }

        @Test @DisplayName("cannot renew non-expired subscription")
        void renewActiveThrows() {
            Subscription active = buildActiveSubscription();
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));

            assertThatThrownBy(() -> subscriptionService.renewSubscription(active.getId()))
                    .isInstanceOf(MembershipException.class);

            verify(subscriptionRepository, never()).save(any());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // processRenewals (scheduler path)
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("processRenewals")
    class ProcessRenewals {

        @Test @DisplayName("delegates each due subscription to the renewal processor")
        void renewalDelegatesToProcessor() {
            Subscription due = buildActiveSubscription();
            when(subscriptionRepository.findSubscriptionsForRenewal(any())).thenReturn(List.of(due));

            subscriptionService.processRenewals();

            // Charged outside the tx, then applied.
            verify(paymentGateway).charge(eq(due.getUser().getId()), any(), any());
            verify(renewalProcessor).applyRenewal(eq(due), any());
        }

        // Test 4 — renewal batch isolation
        @Test @DisplayName("one failed renewal does not abort other renewals in the batch")
        void renewalBatchIsolation() {
            Subscription sub1 = buildActiveSubscription();
            Subscription sub2 = Subscription.builder()
                    .id(200L).user(testUser).plan(silverMonthly)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .startDate(LocalDateTime.now().minusMonths(1))
                    .endDate(LocalDateTime.now().plusHours(12))
                    .nextBillingDate(LocalDateTime.now().plusHours(12))
                    .paidAmount(new BigDecimal("299.00")).autoRenewal(true).version(0L).build();

            when(subscriptionRepository.findSubscriptionsForRenewal(any())).thenReturn(List.of(sub1, sub2));
            doThrow(new RuntimeException("apply failed")).when(renewalProcessor).applyRenewal(eq(sub1), any()); // sub1 fails
            doNothing().when(renewalProcessor).applyRenewal(eq(sub2), any());                                   // sub2 succeeds

            subscriptionService.processRenewals();

            // Both subscriptions must be attempted regardless of sub1's failure (it rolls back + refunds alone).
            verify(renewalProcessor).applyRenewal(eq(sub1), any());
            verify(renewalProcessor).applyRenewal(eq(sub2), any());
            verify(paymentGateway).refund(any(), any()); // sub1's charge compensated
        }

        @Test @DisplayName("bulk expiry records an EXPIRED event per expired subscription and expires by id")
        void bulkExpiryRecordsEvents() {
            Subscription expiring = buildActiveSubscription();
            // One bounded batch (smaller than the batch size) then the loop ends.
            when(subscriptionRepository.findExpiredActive(any(), any())).thenReturn(List.of(expiring));

            subscriptionService.processExpiredSubscriptions();

            verify(eventRepository).save(any());
            verify(outboxEventService).publish(eq("SUBSCRIPTION"), eq(expiring.getId()), eq("EXPIRED"), any());
            verify(subscriptionRepository).expireByIds(any());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // updateSubscription
    // ═══════════════════════════════════════════════════════════

    @Nested @DisplayName("updateSubscription")
    class UpdateSubscription {

        // Test 3 — EXPIRED->ACTIVE rejection
        @Test @DisplayName("EXPIRED->ACTIVE via updateSubscription is rejected — must use renewSubscription")
        void expiredToActiveRejected() {
            Subscription expired = buildActiveSubscription();
            expired.setStatus(Subscription.SubscriptionStatus.EXPIRED);
            when(subscriptionRepository.findById(expired.getId())).thenReturn(Optional.of(expired));

            SubscriptionUpdateDTO update = new SubscriptionUpdateDTO();
            update.setStatus(Subscription.SubscriptionStatus.ACTIVE);

            assertThatThrownBy(() -> subscriptionService.updateSubscription(expired.getId(), update))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("EXPIRED");

            verify(subscriptionRepository, never()).save(any());
        }

        @Test @DisplayName("cancelling via updateSubscription records a CANCELLED event + outbox (parity with cancel)")
        void cancelViaUpdateRecordsEvent() {
            Subscription active = buildActiveSubscription();
            when(subscriptionRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(subscriptionRepository.save(active)).thenReturn(active);

            SubscriptionUpdateDTO update = new SubscriptionUpdateDTO();
            update.setStatus(Subscription.SubscriptionStatus.CANCELLED);

            SubscriptionDTO result = subscriptionService.updateSubscription(active.getId(), update);

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            verify(eventRepository).save(any());
            verify(outboxEventService).publish(eq("SUBSCRIPTION"), eq(active.getId()), eq("CANCELLED"), any());
        }
    }

    // ─── SubscriptionRenewalProcessor (field-mutation correctness) ─────────────

    @Nested @DisplayName("SubscriptionRenewalProcessor")
    class RenewalProcessorTests {

        @Mock private SubscriptionRepository processorRepo;
        @Mock private SubscriptionEventRepository processorEventRepo;
        @Mock private OutboxEventService processorOutbox;
        @InjectMocks private SubscriptionRenewalProcessor processor;

        @Test @DisplayName("applyRenewal advances billing period and resets paidAmount (no charge inside the tx)")
        void applyRenewalCorrectly() {
            LocalDateTime periodEnd = LocalDateTime.now().plusHours(12);
            Subscription sub = Subscription.builder()
                    .id(400L).user(testUser).plan(silverMonthly)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .startDate(LocalDateTime.now().minusMonths(1)).endDate(periodEnd)
                    .nextBillingDate(periodEnd).paidAmount(new BigDecimal("150.00"))
                    .autoRenewal(true).version(0L).build();

            when(processorRepo.save(sub)).thenReturn(sub);

            processor.applyRenewal(sub, "pay_ref");

            assertThat(sub.getStartDate()).isEqualTo(periodEnd);
            assertThat(sub.getEndDate()).isEqualTo(periodEnd.plusMonths(1));
            assertThat(sub.getNextBillingDate()).isEqualTo(sub.getEndDate());
            assertThat(sub.getPaidAmount()).isEqualByComparingTo(silverMonthly.getPrice());
            verify(processorRepo).save(sub);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Subscription buildActiveSubscription() {
        LocalDateTime now = LocalDateTime.now(fixedClock);
        return Subscription.builder()
                .id(100L)
                .user(testUser)
                .plan(silverMonthly)
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(now)
                .endDate(now.plusMonths(1))
                .nextBillingDate(now.plusMonths(1))
                .paidAmount(new BigDecimal("299.00"))
                .autoRenewal(true)
                .version(0L)
                .build();
    }

    private MembershipPlan buildPlanForTier(MembershipTier tier) {
        return MembershipPlan.builder()
                .id(tier.getId() * 3)
                .name(tier.getName() + " Monthly")
                .description(tier.getName() + " monthly plan")
                .type(MembershipPlan.PlanType.MONTHLY)
                .price(new BigDecimal("499.00"))
                .durationInMonths(1)
                .isActive(true)
                .tier(tier).build();
    }
}
