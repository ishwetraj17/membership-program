package com.firstclub.membership.service;

import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
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
import java.time.LocalDateTime;
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

    @InjectMocks private SubscriptionServiceImpl subscriptionService;

    private User testUser;
    private MembershipTier silverTier;
    private MembershipPlan silverMonthly;

    @BeforeEach
    void setUp() {
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

            SubscriptionDTO result = subscriptionService.createSubscription(
                    SubscriptionRequestDTO.builder().userId(10L).planId(1L).autoRenewal(true).build());

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(result.getPaidAmount()).isEqualByComparingTo("299.00");
            assertThat(result.getTier()).isEqualTo("SILVER");
            verify(subscriptionRepository).save(any(Subscription.class));
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
            LocalDateTime oldEnd = LocalDateTime.now().minusDays(5);
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

        @Test @DisplayName("auto-renewal advances startDate to previous endDate and resets paidAmount")
        void renewalAdvancesBillingPeriod() {
            LocalDateTime periodStart = LocalDateTime.now().minusMonths(1);
            LocalDateTime periodEnd = LocalDateTime.now().plusHours(12);

            Subscription due = Subscription.builder()
                    .id(300L)
                    .user(testUser)
                    .plan(silverMonthly)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .startDate(periodStart)
                    .endDate(periodEnd)
                    .nextBillingDate(periodEnd)
                    .paidAmount(new BigDecimal("150.00")) // differs from plan price intentionally
                    .autoRenewal(true)
                    .version(0L)
                    .build();

            when(subscriptionRepository.findSubscriptionsForRenewal(any())).thenReturn(List.of(due));
            when(subscriptionRepository.saveAll(anyList())).thenReturn(List.of(due));

            subscriptionService.processRenewals();

            // startDate advances to the previous period's end (Fix 1 regression guard)
            assertThat(due.getStartDate()).isEqualTo(periodEnd);
            // endDate extends by plan duration from the new startDate
            assertThat(due.getEndDate()).isEqualTo(periodEnd.plusMonths(1));
            // nextBillingDate always equals endDate
            assertThat(due.getNextBillingDate()).isEqualTo(due.getEndDate());
            // paidAmount resets to plan price (Fix 4 regression guard)
            assertThat(due.getPaidAmount()).isEqualByComparingTo(silverMonthly.getPrice());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Subscription buildActiveSubscription() {
        LocalDateTime now = LocalDateTime.now();
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
