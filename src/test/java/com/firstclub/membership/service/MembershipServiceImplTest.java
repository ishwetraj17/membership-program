package com.firstclub.membership.service;

import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.mapper.MembershipPlanMapper;
import com.firstclub.membership.mapper.MembershipTierMapper;
import com.firstclub.membership.mapper.SubscriptionMapper;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.SubscriptionHistoryRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.impl.MembershipServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipServiceImpl Unit Tests")
class MembershipServiceImplTest {

    @Mock
    private MembershipTierRepository tierRepository;

    @Mock
    private MembershipPlanRepository planRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private SubscriptionHistoryRepository subscriptionHistoryRepository;

    @Mock
    private UserService userService;

    @Mock
    private MembershipPlanMapper planMapper;

    @Mock
    private MembershipTierMapper tierMapper;

    @Mock
    private SubscriptionMapper subscriptionMapper;

    @InjectMocks
    private MembershipServiceImpl membershipService;

    private User testUser;
    private MembershipTier silverTier;
    private MembershipPlan silverMonthly;
    private Subscription activeSubscription;

    @BeforeEach
    void setUp() {
        // Inject @Value fields that Mockito doesn't populate
        ReflectionTestUtils.setField(membershipService, "silverBasePrice", new BigDecimal("299"));
        ReflectionTestUtils.setField(membershipService, "goldBasePrice", new BigDecimal("499"));
        ReflectionTestUtils.setField(membershipService, "platinumBasePrice", new BigDecimal("799"));

        testUser = User.builder()
                .id(1L)
                .name("Test User")
                .email("test@example.com")
                .password("$2a$10$encodedTestPassword")
                .phoneNumber("9876543210")
                .address("123 Street")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400001")
                .status(User.UserStatus.ACTIVE)
                .roles(Set.of("ROLE_USER"))
                .isDeleted(false)
                .build();

        silverTier = MembershipTier.builder()
                .id(1L)
                .name("SILVER")
                .level(1)
                .discountPercentage(new BigDecimal("5"))
                .freeDelivery(false)
                .exclusiveDeals(false)
                .earlyAccess(false)
                .prioritySupport(false)
                .maxCouponsPerMonth(2)
                .deliveryDays(5)
                .build();

        silverMonthly = MembershipPlan.builder()
                .id(1L)
                .name("Silver Monthly")
                .type(MembershipPlan.PlanType.MONTHLY)
                .price(new BigDecimal("299"))
                .durationInMonths(1)
                .tier(silverTier)
                .isActive(true)
                .build();

        activeSubscription = Subscription.builder()
                .id(10L)
                .user(testUser)
                .plan(silverMonthly)
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(LocalDateTime.now().minusDays(5))
                .endDate(LocalDateTime.now().plusDays(25))
                .nextBillingDate(LocalDateTime.now().plusDays(25))
                .paidAmount(new BigDecimal("299"))
                .autoRenewal(true)
                .build();
    }

    @Nested
    @DisplayName("createSubscription()")
    class CreateSubscriptionTests {

        @Test
        @DisplayName("Should create subscription successfully when user has no active subscription")
        void shouldCreateSubscriptionSuccessfully() {
            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                    .userId(1L)
                    .planId(1L)
                    .autoRenewal(true)
                    .build();

            when(userService.findUserEntityById(1L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.findActiveSubscriptionByUser(eq(testUser), any())).thenReturn(Optional.empty());
            when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
                Subscription s = inv.getArgument(0);
                ReflectionTestUtils.setField(s, "id", 100L);
                return s;
            });
            SubscriptionDTO expectedDTO = SubscriptionDTO.builder()
                    .id(100L)
                    .userId(1L)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .paidAmount(new BigDecimal("299"))
                    .isActive(true)
                    .build();
            when(subscriptionMapper.toDTO(any(Subscription.class))).thenReturn(expectedDTO);

            SubscriptionDTO result = membershipService.createSubscription(request);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(result.getPaidAmount()).isEqualByComparingTo("299");
            verify(subscriptionRepository).save(any(Subscription.class));
        }

        @Test
        @DisplayName("Should throw when user already has an active subscription")
        void shouldThrowWhenActiveSubscriptionExists() {
            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                    .userId(1L)
                    .planId(1L)
                    .autoRenewal(true)
                    .build();

            when(userService.findUserEntityById(1L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));
            when(subscriptionRepository.findActiveSubscriptionByUser(eq(testUser), any()))
                    .thenReturn(Optional.of(activeSubscription));

            assertThatThrownBy(() -> membershipService.createSubscription(request))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("active subscription");

            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw when plan is inactive")
        void shouldThrowWhenPlanIsInactive() {
            silverMonthly.setIsActive(false);
            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                    .userId(1L).planId(1L).autoRenewal(true).build();

            when(userService.findUserEntityById(1L)).thenReturn(testUser);
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));

            assertThatThrownBy(() -> membershipService.createSubscription(request))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("inactive plan");
        }

        @Test
        @DisplayName("Should throw for invalid user ID")
        void shouldThrowForInvalidUserId() {
            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                    .userId(0L).planId(1L).autoRenewal(true).build();

            assertThatThrownBy(() -> membershipService.createSubscription(request))
                    .isInstanceOf(MembershipException.class);
        }
    }

    @Nested
    @DisplayName("cancelSubscription()")
    class CancelSubscriptionTests {

        @Test
        @DisplayName("Should cancel active subscription successfully")
        void shouldCancelActiveSubscription() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));
            when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionDTO expectedDTO = SubscriptionDTO.builder()
                    .id(10L)
                    .status(Subscription.SubscriptionStatus.CANCELLED)
                    .cancellationReason("No longer needed")
                    .isActive(false)
                    .build();
            when(subscriptionMapper.toDTO(any(Subscription.class))).thenReturn(expectedDTO);

            SubscriptionDTO result = membershipService.cancelSubscription(10L, "No longer needed");

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            assertThat(result.getCancellationReason()).isEqualTo("No longer needed");
            assertThat(result.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("Should throw when cancelling a non-active subscription")
        void shouldThrowWhenCancellingNonActive() {
            activeSubscription.setStatus(Subscription.SubscriptionStatus.EXPIRED);
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));

            assertThatThrownBy(() -> membershipService.cancelSubscription(10L, "reason"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Cannot cancel non-active");
        }

        @Test
        @DisplayName("Should throw when subscription not found")
        void shouldThrowWhenNotFound() {
            when(subscriptionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> membershipService.cancelSubscription(999L, "reason"))
                    .isInstanceOf(MembershipException.class);
        }
    }

    @Nested
    @DisplayName("renewSubscription()")
    class RenewSubscriptionTests {

        @Test
        @DisplayName("Should renew expired subscription successfully")
        void shouldRenewExpiredSubscription() {
            Subscription expired = Subscription.builder()
                    .id(11L)
                    .user(testUser)
                    .plan(silverMonthly)
                    .status(Subscription.SubscriptionStatus.EXPIRED)
                    .startDate(LocalDateTime.now().minusDays(35))
                    .endDate(LocalDateTime.now().minusDays(5))
                    .nextBillingDate(LocalDateTime.now().minusDays(5))
                    .paidAmount(new BigDecimal("299"))
                    .autoRenewal(true)
                    .build();

            when(subscriptionRepository.findById(11L)).thenReturn(Optional.of(expired));
            when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionDTO expectedDTO = SubscriptionDTO.builder()
                    .id(11L)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .endDate(LocalDateTime.now().plusDays(25))
                    .build();
            when(subscriptionMapper.toDTO(any(Subscription.class))).thenReturn(expectedDTO);

            SubscriptionDTO result = membershipService.renewSubscription(11L);

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(result.getEndDate()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("Should throw when trying to renew non-expired subscription")
        void shouldThrowWhenRenewingNonExpired() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));

            assertThatThrownBy(() -> membershipService.renewSubscription(10L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("expired");
        }
    }

    @Nested
    @DisplayName("getSubscriptionById()")
    class GetSubscriptionByIdTests {

        @Test
        @DisplayName("Should return DTO when subscription exists")
        void shouldReturnSubscriptionDTO() {
            SubscriptionDTO expectedDTO = SubscriptionDTO.builder()
                    .id(10L)
                    .userEmail("test@example.com")
                    .build();
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));
            when(subscriptionMapper.toDTO(activeSubscription)).thenReturn(expectedDTO);

            SubscriptionDTO result = membershipService.getSubscriptionById(10L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(10L);
            assertThat(result.getUserEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should throw when subscription not found")
        void shouldThrowWhenNotFound() {
            when(subscriptionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> membershipService.getSubscriptionById(999L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Subscription not found");
        }
    }

    @Nested
    @DisplayName("upgradeSubscription()")
    class UpgradeSubscriptionTests {

        private MembershipTier goldTier;
        private MembershipPlan silverYearly;
        private MembershipPlan goldMonthly;

        @BeforeEach
        void setUpUpgradeFixtures() {
            goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2)
                    .discountPercentage(new BigDecimal("10")).build();

            silverYearly = MembershipPlan.builder()
                    .id(4L).name("Silver Yearly").type(MembershipPlan.PlanType.YEARLY)
                    .price(new BigDecimal("3048")).durationInMonths(12)
                    .tier(silverTier).isActive(true).build();

            goldMonthly = MembershipPlan.builder()
                    .id(2L).name("Gold Monthly").type(MembershipPlan.PlanType.MONTHLY)
                    .price(new BigDecimal("499")).durationInMonths(1)
                    .tier(goldTier).isActive(true).build();
        }

        @Test
        @DisplayName("Should upgrade from Silver to Gold tier")
        void shouldUpgradeToHigherTier() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));
            when(planRepository.findById(2L)).thenReturn(Optional.of(goldMonthly));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionDTO expected = SubscriptionDTO.builder().id(10L)
                    .status(Subscription.SubscriptionStatus.ACTIVE).build();
            when(subscriptionMapper.toDTO(any())).thenReturn(expected);

            SubscriptionDTO result = membershipService.upgradeSubscription(10L, 2L);

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            verify(subscriptionHistoryRepository).save(any());
        }

        @Test
        @DisplayName("Should throw when upgrading to same plan (no change)")
        void shouldThrowWhenUpgradingToSamePlan() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));
            // Same plan: same tier level (1) and same duration (1 month)
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly));

            assertThatThrownBy(() -> membershipService.upgradeSubscription(10L, 1L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Invalid upgrade");
        }

        @Test
        @DisplayName("Should throw when upgrading a non-active subscription")
        void shouldThrowWhenUpgradingNonActiveSubscription() {
            activeSubscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));

            assertThatThrownBy(() -> membershipService.upgradeSubscription(10L, 2L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Cannot upgrade non-active");
        }
    }

    @Nested
    @DisplayName("downgradeSubscription()")
    class DowngradeSubscriptionTests {

        private MembershipPlan silverYearly;

        @BeforeEach
        void setUpDowngradeFixtures() {
            // Subscription is on Silver Yearly (12 months)
            silverYearly = MembershipPlan.builder()
                    .id(3L).name("Silver Yearly").type(MembershipPlan.PlanType.YEARLY)
                    .price(new BigDecimal("3048")).durationInMonths(12)
                    .tier(silverTier).isActive(true).build();

            activeSubscription = Subscription.builder()
                    .id(10L).user(testUser).plan(silverYearly)
                    .status(Subscription.SubscriptionStatus.ACTIVE)
                    .startDate(LocalDateTime.now().minusDays(5))
                    .endDate(LocalDateTime.now().plusDays(360))
                    .paidAmount(new BigDecimal("3048")).autoRenewal(true).build();
        }

        @Test
        @DisplayName("Should allow same-tier shorter-duration downgrade (yearly → monthly)")
        void shouldAllowSameTierDowngradeYearlyToMonthly() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));
            when(planRepository.findById(1L)).thenReturn(Optional.of(silverMonthly)); // same tier, 1 month
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionDTO expected = SubscriptionDTO.builder().id(10L)
                    .status(Subscription.SubscriptionStatus.ACTIVE).build();
            when(subscriptionMapper.toDTO(any())).thenReturn(expected);

            SubscriptionDTO result = membershipService.downgradeSubscription(10L, 1L);

            assertThat(result.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            verify(subscriptionHistoryRepository).save(any());
        }

        @Test
        @DisplayName("Should throw when attempting to downgrade to a higher-tier plan")
        void shouldThrowWhenDowngradingToHigherTier() {
            MembershipTier goldTier = MembershipTier.builder()
                    .id(2L).name("GOLD").level(2).build();
            MembershipPlan goldMonthly = MembershipPlan.builder()
                    .id(2L).type(MembershipPlan.PlanType.MONTHLY)
                    .price(new BigDecimal("499")).durationInMonths(1)
                    .tier(goldTier).isActive(true).build();

            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSubscription));
            when(planRepository.findById(2L)).thenReturn(Optional.of(goldMonthly));

            assertThatThrownBy(() -> membershipService.downgradeSubscription(10L, 2L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Invalid downgrade");
        }
    }
}
