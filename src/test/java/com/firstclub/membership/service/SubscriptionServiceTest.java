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
