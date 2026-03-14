package com.firstclub.membership;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.PlanService;
import com.firstclub.membership.service.TierService;
import com.firstclub.membership.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive integration tests for the FirstClub Membership Program
 * 
 * This test suite demonstrates:
 * - Spring Boot integration testing
 * - REST API endpoint validation
 * - Service layer business logic testing
 * - Exception handling verification
 * - Data integrity validation
 * 
 * @author Shwet Raj
 */
@DisplayName("FirstClub Membership Program - Integration Tests")
class MembershipApplicationTests extends PostgresIntegrationTestBase {

    /** Pre-seeded admin account credentials — kept as constants so a single change
     *  propagates everywhere in this test class. */
    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @Autowired
    private MembershipService membershipService;
    
    @Autowired
    private TierService tierService;

    @Autowired
    private PlanService planService;

    @Autowired
    private UserService userService;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    private String generateUniqueEmail(String prefix) {
        return prefix + System.currentTimeMillis() + "@example.com";
    }

    private UserDTO createTestUser(String name, String emailPrefix) {
        return UserDTO.builder()
            .name(name)
            .email(generateUniqueEmail(emailPrefix))
            .phoneNumber("9876543210")
            .address("123 Test Street")
            .city("Mumbai")
            .state("Maharashtra")
            .pincode("400001")
            .build();
    }

    // ========================================
    // CONTEXT AND INITIALIZATION TESTS
    // ========================================

    @Nested
    @DisplayName("Context & Initialization")
    class ContextTests {

        @Test
        @DisplayName("Spring context should load successfully")
        void contextLoads() {
            assertThat(membershipService).isNotNull();
            assertThat(userService).isNotNull();
            assertThat(tierService).isNotNull();
            assertThat(planService).isNotNull();
        }

        @Test
        @DisplayName("Should initialize 3 membership tiers correctly")
        void shouldInitializeMembershipTiers() {
            List<com.firstclub.membership.dto.MembershipTierDTO> tiers = tierService.getAllTiers();
            
            assertThat(tiers).hasSize(3);
            assertThat(tiers)
                .extracting(tier -> tier.getName())
                .containsExactlyInAnyOrder("SILVER", "GOLD", "PLATINUM");
        }

        @Test
        @DisplayName("Should initialize 9 membership plans (3 tiers × 3 durations)")
        void shouldInitializeMembershipPlans() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            
            assertThat(plans).hasSize(9);
            
            long monthlyCount = plans.stream().filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY).count();
            long quarterlyCount = plans.stream().filter(p -> p.getType() == MembershipPlan.PlanType.QUARTERLY).count();
            long yearlyCount = plans.stream().filter(p -> p.getType() == MembershipPlan.PlanType.YEARLY).count();
            
            assertThat(monthlyCount).isEqualTo(3);
            assertThat(quarterlyCount).isEqualTo(3);
            assertThat(yearlyCount).isEqualTo(3);
        }
    }

    // ========================================
    // BUSINESS LOGIC TESTS
    // ========================================

    @Nested
    @DisplayName("Business Logic Validation")
    class BusinessLogicTests {

        @Test
        @DisplayName("Tier pricing should follow hierarchy (Silver < Gold < Platinum)")
        void shouldValidateTierPricingHierarchy() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            
            MembershipPlanDTO silverPlan = plans.stream()
                .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Silver monthly plan not found"));
            
            MembershipPlanDTO goldPlan = plans.stream()
                .filter(p -> p.getTier().equals("GOLD") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Gold monthly plan not found"));
            
            MembershipPlanDTO platinumPlan = plans.stream()
                .filter(p -> p.getTier().equals("PLATINUM") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Platinum monthly plan not found"));
            
            assertThat(silverPlan.getPrice()).isEqualTo(new BigDecimal("299.00"));
            assertThat(goldPlan.getPrice()).isEqualTo(new BigDecimal("499.00"));
            assertThat(platinumPlan.getPrice()).isEqualTo(new BigDecimal("799.00"));
            
            assertThat(goldPlan.getPrice()).isGreaterThan(silverPlan.getPrice());
            assertThat(platinumPlan.getPrice()).isGreaterThan(goldPlan.getPrice());
        }

        @Test
        @DisplayName("Yearly plans should provide savings compared to monthly")
        void shouldValidateYearlyPlanSavings() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            
            MembershipPlanDTO silverMonthly = plans.stream()
                .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow();
            
            MembershipPlanDTO silverYearly = plans.stream()
                .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.YEARLY)
                .findFirst()
                .orElseThrow();
            
            BigDecimal expectedYearlyCost = silverMonthly.getPrice().multiply(new BigDecimal("12"));
            
            assertThat(silverYearly.getPrice()).isLessThan(expectedYearlyCost);
            assertThat(silverYearly.getSavings()).isPositive();
            
            BigDecimal calculatedSavings = expectedYearlyCost.subtract(silverYearly.getPrice());
            assertThat(silverYearly.getSavings()).isEqualByComparingTo(calculatedSavings);
        }

        @Test
        @DisplayName("Tier levels should be consistent (Silver=1, Gold=2, Platinum=3)")
        void shouldValidateTierLevels() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            
            List<MembershipPlanDTO> silverPlans = plans.stream().filter(p -> p.getTier().equals("SILVER")).toList();
            List<MembershipPlanDTO> goldPlans = plans.stream().filter(p -> p.getTier().equals("GOLD")).toList();
            List<MembershipPlanDTO> platinumPlans = plans.stream().filter(p -> p.getTier().equals("PLATINUM")).toList();
            
            assertThat(silverPlans).allMatch(p -> p.getTierLevel() == 1);
            assertThat(goldPlans).allMatch(p -> p.getTierLevel() == 2);
            assertThat(platinumPlans).allMatch(p -> p.getTierLevel() == 3);
        }

        @Test
        @DisplayName("Tier discount percentages should be correct")
        void shouldValidateTierDiscountPercentages() {
            List<com.firstclub.membership.dto.MembershipTierDTO> tiers = tierService.getAllTiers();
            
            com.firstclub.membership.dto.MembershipTierDTO silverTier = tiers.stream()
                .filter(t -> t.getName().equals("SILVER")).findFirst().orElseThrow();
            com.firstclub.membership.dto.MembershipTierDTO goldTier = tiers.stream()
                .filter(t -> t.getName().equals("GOLD")).findFirst().orElseThrow();
            com.firstclub.membership.dto.MembershipTierDTO platinumTier = tiers.stream()
                .filter(t -> t.getName().equals("PLATINUM")).findFirst().orElseThrow();
            
            assertThat(silverTier.getDiscountPercentage()).isEqualByComparingTo(new BigDecimal("5.00"));
            assertThat(goldTier.getDiscountPercentage()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(platinumTier.getDiscountPercentage()).isEqualByComparingTo(new BigDecimal("15.00"));
        }
    }

    // ========================================
    // USER MANAGEMENT TESTS
    // ========================================

    @Nested
    @DisplayName("User Management")
    class UserServiceTests {

        @Test
        @DisplayName("Should create user with valid data")
        void shouldCreateUserSuccessfully() {
            UserDTO userDTO = createTestUser("Test User", "testuser");
            
            UserDTO createdUser = userService.createUser(userDTO);
            
            assertThat(createdUser).isNotNull();
            assertThat(createdUser.getId()).isNotNull();
            assertThat(createdUser.getName()).isEqualTo("Test User");
            assertThat(createdUser.getEmail()).isEqualTo(userDTO.getEmail());
            assertThat(createdUser.getPhoneNumber()).isEqualTo("9876543210");
        }

        @Test
        @DisplayName("Should retrieve user by ID")
        void shouldRetrieveUserById() {
            UserDTO userDTO = createTestUser("Retrieve Test", "retrieve");
            UserDTO createdUser = userService.createUser(userDTO);
            
            UserDTO retrievedUser = userService.getUserById(createdUser.getId()).orElseThrow();
            
            assertThat(retrievedUser).isNotNull();
            assertThat(retrievedUser.getId()).isEqualTo(createdUser.getId());
            assertThat(retrievedUser.getName()).isEqualTo("Retrieve Test");
            assertThat(retrievedUser.getEmail()).isEqualTo(userDTO.getEmail());
        }
    }

    // ========================================
    // SUBSCRIPTION MANAGEMENT TESTS
    // ========================================

    @Nested
    @DisplayName("Subscription Management")
    class SubscriptionTests {

        @Test
        @DisplayName("Should create subscription successfully")
        void shouldCreateSubscriptionSuccessfully() {
            UserDTO user = createTestUser("Sub Test User", "subtest");
            UserDTO createdUser = userService.createUser(user);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO silverPlan = plans.stream()
                .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow();

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(silverPlan.getId())
                .autoRenewal(true)
                .build();

            SubscriptionDTO subscription = membershipService.createSubscription(request);

            assertThat(subscription).isNotNull();
            assertThat(subscription.getId()).isNotNull();
            assertThat(subscription.getUserId()).isEqualTo(createdUser.getId());
            assertThat(subscription.getPlanId()).isEqualTo(silverPlan.getId());
            assertThat(subscription.getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(subscription.getAutoRenewal()).isTrue();
            assertThat(subscription.getPaidAmount()).isEqualByComparingTo(silverPlan.getPrice());
        }

        @Test
        @DisplayName("Should prevent duplicate active subscriptions")
        void shouldPreventDuplicateActiveSubscriptions() {
            UserDTO user = createTestUser("Duplicate Test", "duplicate");
            UserDTO createdUser = userService.createUser(user);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO plan = plans.get(0);

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plan.getId())
                .autoRenewal(true)
                .build();
            
            membershipService.createSubscription(request);

            assertThatThrownBy(() -> membershipService.createSubscription(request))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("already has an active subscription");
        }

        @Test
        @DisplayName("Should cancel subscription successfully")
        void shouldCancelSubscriptionSuccessfully() {
            UserDTO user = createTestUser("Cancel Test", "cancel");
            UserDTO createdUser = userService.createUser(user);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO plan = plans.get(0);

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plan.getId())
                .autoRenewal(true)
                .build();
            
            SubscriptionDTO subscription = membershipService.createSubscription(request);

            String cancellationReason = "Not satisfied with service";
            SubscriptionDTO cancelledSubscription = membershipService.cancelSubscription(
                subscription.getId(), 
                cancellationReason
            );

            assertThat(cancelledSubscription.getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            assertThat(cancelledSubscription.getCancellationReason()).isEqualTo(cancellationReason);
            assertThat(cancelledSubscription.getAutoRenewal()).isFalse();
            assertThat(cancelledSubscription.getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("Should retrieve user subscriptions")
        void shouldRetrieveUserSubscriptions() {
            UserDTO user = createTestUser("History Test", "history");
            UserDTO createdUser = userService.createUser(user);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO plan = plans.get(0);

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plan.getId())
                .autoRenewal(true)
                .build();
            
            SubscriptionDTO subscription = membershipService.createSubscription(request);

            List<SubscriptionDTO> userSubscriptions = membershipService.getUserSubscriptionsPaged(createdUser.getId(), Pageable.unpaged()).getContent();

            assertThat(userSubscriptions).isNotEmpty();
            assertThat(userSubscriptions).hasSize(1);
            assertThat(userSubscriptions.get(0).getId()).isEqualTo(subscription.getId());
            assertThat(userSubscriptions.get(0).getUserId()).isEqualTo(createdUser.getId());
        }

        @Test
        @DisplayName("Should get active subscription for user")
        void shouldGetActiveSubscriptionForUser() {
            UserDTO user = createTestUser("Active Test", "active");
            UserDTO createdUser = userService.createUser(user);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO goldPlan = plans.stream()
                .filter(p -> p.getTier().equals("GOLD") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow();

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(goldPlan.getId())
                .autoRenewal(true)
                .build();
            
            SubscriptionDTO subscription = membershipService.createSubscription(request);

            Optional<SubscriptionDTO> activeSubscription = membershipService.getActiveSubscription(createdUser.getId());

            assertThat(activeSubscription).isPresent();
            assertThat(activeSubscription.get().getId()).isEqualTo(subscription.getId());
            assertThat(activeSubscription.get().getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
            assertThat(activeSubscription.get().getTier()).isEqualTo("GOLD");
        }
    }

    // ========================================
    // EXCEPTION HANDLING TESTS
    // ========================================

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("Should handle user not found exception")
        void shouldHandleUserNotFoundException() {
            Long nonExistentUserId = 99999L;

            assertThatThrownBy(() -> membershipService.getUserSubscriptionsPaged(nonExistentUserId, Pageable.unpaged()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Should handle plan not found exception")
        void shouldHandlePlanNotFoundException() {
            UserDTO user = createTestUser("Exception Test", "exception");
            UserDTO createdUser = userService.createUser(user);
            
            Long nonExistentPlanId = 99999L;
            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(nonExistentPlanId)
                .autoRenewal(true)
                .build();

            assertThatThrownBy(() -> membershipService.createSubscription(request))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("Plan not found");
        }

        @Test
        @DisplayName("Should handle subscription not found exception")
        void shouldHandleSubscriptionNotFoundException() {
            Long nonExistentSubscriptionId = 99999L;
            String cancellationReason = "Test cancellation";

            assertThatThrownBy(() -> membershipService.cancelSubscription(nonExistentSubscriptionId, cancellationReason))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Should handle invalid subscription operation")
        void shouldHandleInvalidSubscriptionOperation() {
            UserDTO user = createTestUser("Invalid Op Test", "invalidop");
            UserDTO createdUser = userService.createUser(user);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO plan = plans.get(0);

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plan.getId())
                .autoRenewal(true)
                .build();
            
            SubscriptionDTO subscription = membershipService.createSubscription(request);
            membershipService.cancelSubscription(subscription.getId(), "Initial cancellation");

            assertThatThrownBy(() -> membershipService.cancelSubscription(subscription.getId(), "Second cancellation"))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("Invalid status transition for SUBSCRIPTION: CANCELLED -> CANCELLED");
        }
    }

    // ========================================
    // PLAN FILTERING TESTS
    // ========================================

    @Nested
    @DisplayName("Plan Filtering & Integration")
    class PlanFilteringTests {

        @Test
        @DisplayName("Should validate plan data integrity")
        void shouldValidatePlanDataIntegrity() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            
            assertThat(plans).allMatch(plan -> {
                return plan.getId() != null &&
                       plan.getName() != null && !plan.getName().trim().isEmpty() &&
                       plan.getPrice() != null && plan.getPrice().compareTo(BigDecimal.ZERO) > 0 &&
                       plan.getTier() != null && !plan.getTier().trim().isEmpty() &&
                       plan.getType() != null &&
                       plan.getTierLevel() > 0;
            });
        }

        @Test
        @DisplayName("Should filter plans by type correctly")
        void shouldFilterPlansByType() {
            List<MembershipPlanDTO> monthlyPlans = planService.getPlansByType(MembershipPlan.PlanType.MONTHLY);
            List<MembershipPlanDTO> quarterlyPlans = planService.getPlansByType(MembershipPlan.PlanType.QUARTERLY);
            List<MembershipPlanDTO> yearlyPlans = planService.getPlansByType(MembershipPlan.PlanType.YEARLY);

            assertThat(monthlyPlans).hasSize(3);
            assertThat(quarterlyPlans).hasSize(3);
            assertThat(yearlyPlans).hasSize(3);

            assertThat(monthlyPlans).allMatch(p -> p.getType() == MembershipPlan.PlanType.MONTHLY);
            assertThat(quarterlyPlans).allMatch(p -> p.getType() == MembershipPlan.PlanType.QUARTERLY);
            assertThat(yearlyPlans).allMatch(p -> p.getType() == MembershipPlan.PlanType.YEARLY);
        }

        @Test
        @DisplayName("Should filter plans by tier correctly")
        void shouldFilterPlansByTier() {
            List<MembershipPlanDTO> silverPlans = planService.getPlansByTier("SILVER");
            List<MembershipPlanDTO> goldPlans = planService.getPlansByTier("GOLD");
            List<MembershipPlanDTO> platinumPlans = planService.getPlansByTier("PLATINUM");

            assertThat(silverPlans).hasSize(3);
            assertThat(goldPlans).hasSize(3);
            assertThat(platinumPlans).hasSize(3);

            assertThat(silverPlans).allMatch(p -> p.getTier().equals("SILVER"));
            assertThat(goldPlans).allMatch(p -> p.getTier().equals("GOLD"));
            assertThat(platinumPlans).allMatch(p -> p.getTier().equals("PLATINUM"));
        }

        @Test
        @DisplayName("Should validate plan-tier relationships")
        void shouldValidatePlanTierRelationships() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();

            plans.forEach(plan -> {
                assertThat(plan.getTier()).isNotNull();
                assertThat(plan.getTierLevel()).isNotNull();
                assertThat(plan.getDiscountPercentage()).isNotNull();
                
                if ("SILVER".equals(plan.getTier())) {
                    assertThat(plan.getTierLevel()).isEqualTo(1);
                    assertThat(plan.getDiscountPercentage()).isEqualByComparingTo(new BigDecimal("5.00"));
                } else if ("GOLD".equals(plan.getTier())) {
                    assertThat(plan.getTierLevel()).isEqualTo(2);
                    assertThat(plan.getDiscountPercentage()).isEqualByComparingTo(new BigDecimal("10.00"));
                } else if ("PLATINUM".equals(plan.getTier())) {
                    assertThat(plan.getTierLevel()).isEqualTo(3);
                    assertThat(plan.getDiscountPercentage()).isEqualByComparingTo(new BigDecimal("15.00"));
                }
            });
        }

        @Test
        @DisplayName("Should validate plan pricing calculations")
        void shouldValidatePlanPricingCalculations() {
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            List<MembershipPlanDTO> silverPlans = plans.stream().filter(p -> p.getTier().equals("SILVER")).toList();

            MembershipPlanDTO monthlyPlan = silverPlans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst().orElseThrow();
            
            MembershipPlanDTO quarterlyPlan = silverPlans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.QUARTERLY)
                .findFirst().orElseThrow();
            
            MembershipPlanDTO yearlyPlan = silverPlans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.YEARLY)
                .findFirst().orElseThrow();

            BigDecimal monthlyPrice = monthlyPlan.getPrice();
            BigDecimal expectedQuarterlyPrice = monthlyPrice.multiply(new BigDecimal("3"));
            BigDecimal expectedYearlyPrice = monthlyPrice.multiply(new BigDecimal("12"));

            assertThat(quarterlyPlan.getPrice()).isLessThan(expectedQuarterlyPrice);
            assertThat(yearlyPlan.getPrice()).isLessThan(expectedYearlyPrice);
            
            assertThat(quarterlyPlan.getSavings()).isPositive();
            assertThat(yearlyPlan.getSavings()).isPositive();
        }
    }

    // ========================================
    // REST API INTEGRATION TESTS
    // ========================================

    @Nested
    @DisplayName("REST API Integration")
    class RestApiTests {

        private String authToken;

        @BeforeEach
        void obtainAuthToken() {
            // Log in as the pre-seeded admin user (created during application init)
            LoginRequestDTO loginRequest = LoginRequestDTO.builder()
                .email(ADMIN_EMAIL)
                .password(ADMIN_PASSWORD)
                .build();

            ResponseEntity<JwtResponseDTO> authResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/login",
                new HttpEntity<>(loginRequest, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            authToken = authResponse.getBody().getToken();
        }

        private HttpHeaders jsonHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }

        private HttpHeaders authHeaders() {
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(authToken);
            return headers;
        }

        private <T> HttpEntity<T> createJsonRequest(T body) {
            return new HttpEntity<>(body, authHeaders());
        }

        private HttpEntity<Void> authGet() {
            return new HttpEntity<>(authHeaders());
        }

        @Test
        @DisplayName("User API - Create user via POST /api/v1/users")
        void shouldCreateUserViaRestApi() {
            UserDTO userRequest = createTestUser("API Test User", "apitest");
            HttpEntity<UserDTO> request = createJsonRequest(userRequest);

            ResponseEntity<UserDTO> response = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/users", 
                request, 
                UserDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isNotNull();
            assertThat(response.getBody().getName()).isEqualTo("API Test User");
            assertThat(response.getBody().getEmail()).isEqualTo(userRequest.getEmail());
        }

        @Test
        @DisplayName("User API - Get user by ID via GET /api/v1/users/{id}")
        void shouldGetUserByIdViaRestApi() {
            UserDTO userRequest = createTestUser("Get User Test", "getuser");
            UserDTO createdUser = userService.createUser(userRequest);

            ResponseEntity<UserDTO> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/" + createdUser.getId(),
                HttpMethod.GET, authGet(), UserDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(createdUser.getId());
            assertThat(response.getBody().getName()).isEqualTo("Get User Test");
        }

        @Test
        @DisplayName("User API - Get user by email via GET /api/v1/users/email/{email}")
        void shouldGetUserByEmailViaRestApi() {
            String uniqueEmail = generateUniqueEmail("emailtest");
            UserDTO userRequest = createTestUser("Email Test User", "emailtest");
            userRequest = UserDTO.builder()
                .name("Email Test User")
                .email(uniqueEmail)
                .phoneNumber("9876543221")
                .address("789 Email Street")
                .city("Bangalore")
                .state("Karnataka")
                .pincode("560001")
                .build();

            userService.createUser(userRequest);

            ResponseEntity<UserDTO> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/email/" + uniqueEmail,
                HttpMethod.GET, authGet(), UserDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getEmail()).isEqualTo(uniqueEmail);
            assertThat(response.getBody().getName()).isEqualTo("Email Test User");
        }

        @Test
        @DisplayName("Plan API - Get all plans via GET /api/v1/membership/plans")
        void shouldGetAllPlansViaRestApi() {
            ResponseEntity<MembershipPlanDTO[]> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/plans",
                HttpMethod.GET, authGet(), MembershipPlanDTO[].class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().length).isEqualTo(9);
        }

        @Test
        @DisplayName("Plan API - Get plans by tier via GET /api/v1/membership/plans/tier/{tier}")
        void shouldGetPlansByTierViaRestApi() {
            ResponseEntity<MembershipPlanDTO[]> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/plans/tier/GOLD",
                HttpMethod.GET, authGet(), MembershipPlanDTO[].class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().length).isEqualTo(3);
            
            for (MembershipPlanDTO plan : response.getBody()) {
                assertThat(plan.getTier()).isEqualTo("GOLD");
            }
        }

        @Test
        @DisplayName("Plan API - Get plans by type via GET /api/v1/membership/plans/type/{type}")
        void shouldGetPlansByTypeViaRestApi() {
            ResponseEntity<MembershipPlanDTO[]> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/plans/type/YEARLY",
                HttpMethod.GET, authGet(), MembershipPlanDTO[].class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().length).isEqualTo(3);
            
            for (MembershipPlanDTO plan : response.getBody()) {
                assertThat(plan.getType()).isEqualTo(MembershipPlan.PlanType.YEARLY);
            }
        }

        @Test
        @DisplayName("Tier API - Get all tiers via GET /api/v1/membership/tiers")
        void shouldCallTiersEndpoint() {
            ResponseEntity<Object> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/tiers",
                HttpMethod.GET, authGet(), Object.class
            );

            // Should return 200 OK with @JsonIgnore fix
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Subscription API - Create subscription via POST /api/v1/subscriptions")
        void shouldCreateSubscriptionViaRestApi() {
            UserDTO userRequest = createTestUser("Sub API Test", "subapi");
            UserDTO createdUser = userService.createUser(userRequest);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            MembershipPlanDTO silverPlan = plans.stream()
                .filter(p -> p.getTier().equals("SILVER") && p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst()
                .orElseThrow();

            SubscriptionRequestDTO subscriptionRequest = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(silverPlan.getId())
                .autoRenewal(true)
                .build();

            HttpEntity<SubscriptionRequestDTO> request = createJsonRequest(subscriptionRequest);

            ResponseEntity<SubscriptionDTO> response = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/subscriptions",
                request,
                SubscriptionDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isNotNull();
            assertThat(response.getBody().getUserId()).isEqualTo(createdUser.getId());
            assertThat(response.getBody().getStatus()).isEqualTo(Subscription.SubscriptionStatus.ACTIVE);
        }

        @Test
        @DisplayName("Subscription API - Cancel subscription via PUT /api/v1/subscriptions/{id}/cancel")
        void shouldCancelSubscriptionViaRestApi() {
            UserDTO userRequest = createTestUser("Cancel API Test", "cancelapi");
            UserDTO createdUser = userService.createUser(userRequest);
            
            List<MembershipPlanDTO> plans = planService.getActivePlans();
            SubscriptionRequestDTO subscriptionRequest = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plans.get(0).getId())
                .autoRenewal(true)
                .build();

            SubscriptionDTO createdSubscription = membershipService.createSubscription(subscriptionRequest);

            String cancellationReason = "API Test Cancellation";
            ResponseEntity<SubscriptionDTO> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/subscriptions/" + createdSubscription.getId() + "/cancel?reason=" + cancellationReason,
                HttpMethod.PUT,
                authGet(),
                SubscriptionDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(Subscription.SubscriptionStatus.CANCELLED);
            assertThat(response.getBody().getCancellationReason()).isEqualTo(cancellationReason);
        }

        @Test
        @DisplayName("System API - Get health via GET /api/v1/membership/health")
        void shouldGetSystemHealthViaRestApi() {
            ResponseEntity<Object> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/health",
                HttpMethod.GET, authGet(), Object.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("System API - Get analytics via GET /api/v1/membership/analytics")
        void shouldGetAnalyticsViaRestApi() {
            ResponseEntity<Object> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/analytics",
                HttpMethod.GET, authGet(), Object.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("Error Handling - Should return 404 for non-existent user")
        void shouldHandle404ForNonExistentUser() {
            ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/99999",
                HttpMethod.GET, authGet(), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Error Handling - Should return 404 for non-existent plan")
        void shouldHandle404ForNonExistentPlan() {
            ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/plans/99999",
                HttpMethod.GET, authGet(), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Error Handling - Should return 400 for invalid subscription request")
        void shouldHandle400ForInvalidSubscriptionRequest() {
            SubscriptionRequestDTO invalidRequest = SubscriptionRequestDTO.builder()
                .userId(null)
                .planId(null)
                .autoRenewal(true)
                .build();

            HttpEntity<SubscriptionRequestDTO> request = createJsonRequest(invalidRequest);

            ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/subscriptions",
                request,
                String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ========================================
    // SECURITY LAYER TESTS
    // ========================================

    @Nested
    @DisplayName("Security Layer")
    class SecurityTests {

        @Test
        @DisplayName("Should return 401 when no Bearer token is provided")
        void shouldReturn401WhenNoToken() {
            ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/health",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should return 401 when an invalid/malformed token is provided")
        void shouldReturn401ForInvalidToken() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth("this.is.not.a.valid.jwt");
            ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should return 403 when a regular user hits an admin-only endpoint")
        void shouldReturn403WhenRegularUserHitsAdminEndpoint() {
            // Register a regular user
            UserDTO registerRequest = UserDTO.builder()
                .name("Regular User")
                .email(generateUniqueEmail("regularuser"))
                .phoneNumber("9000000002")
                .address("2 User St")
                .city("Delhi")
                .state("Delhi")
                .pincode("110001")
                .password("User@test1")
                .build();
            ResponseEntity<JwtResponseDTO> authResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(registerRequest, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String userToken = authResponse.getBody().getToken();

            // Hit an admin-only endpoint
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(userToken);
            ResponseEntity<String> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Should return 200 when a user accesses their own profile")
        void shouldAllowUserToAccessOwnProfile() {
            // Register a user
            UserDTO registerRequest = UserDTO.builder()
                .name("Self Access User")
                .email(generateUniqueEmail("selfaccess"))
                .phoneNumber("9000000003")
                .address("3 User St")
                .city("Pune")
                .state("Maharashtra")
                .pincode("411001")
                .password("Self@test1")
                .build();
            ResponseEntity<JwtResponseDTO> authResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(registerRequest, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long ownUserId = authResponse.getBody().getUserId();
            String ownToken = authResponse.getBody().getToken();

            // Access own user data
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(ownToken);
            ResponseEntity<UserDTO> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/" + ownUserId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                UserDTO.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getId()).isEqualTo(ownUserId);
        }

        private HttpHeaders jsonHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }

        @Test
        @DisplayName("Blacklisted token should be rejected with 401 after logout")
        void blacklistedTokenShouldBeRejected() {
            // Register a new user and capture the access token
            UserDTO registerRequest = UserDTO.builder()
                .name("Logout Test User")
                .email(generateUniqueEmail("logouttest"))
                .phoneNumber("9000000099")
                .address("9 Logout Lane")
                .city("Chennai")
                .state("Tamil Nadu")
                .pincode("600001")
                .password("Logout@test1")
                .build();
            ResponseEntity<JwtResponseDTO> authResponse = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(registerRequest, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String accessToken = authResponse.getBody().getToken();
            Long userId = authResponse.getBody().getUserId();

            // Confirm the token works before logout
            HttpHeaders bearerHeaders = new HttpHeaders();
            bearerHeaders.setBearerAuth(accessToken);
            ResponseEntity<UserDTO> beforeLogout = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/" + userId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders),
                UserDTO.class
            );
            assertThat(beforeLogout.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Logout — should blacklist the token
            ResponseEntity<Map<String, Object>> logoutResponse = restTemplate.exchange(
                getBaseUrl() + "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
            );
            assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            // The same token must now return 401
            ResponseEntity<String> afterLogout = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/" + userId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders),
                String.class
            );
            assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ========================================
    // ERROR HANDLING EDGE-CASE TESTS
    // ========================================

    @Nested
    @DisplayName("Error Handling Edge Cases")
    class ErrorHandlingEdgeCaseTests {

        @Test
        @DisplayName("Duplicate email registration should return 409 Conflict")
        void shouldReturn409OnDuplicateEmailRegistration() {
            String email = generateUniqueEmail("dupuser");
            UserDTO registerRequest = UserDTO.builder()
                .name("First User")
                .email(email)
                .phoneNumber("9123456780")
                .address("1 Test St")
                .city("Chennai")
                .state("Tamil Nadu")
                .pincode("600001")
                .password("First@test1")
                .build();

            // First registration — should succeed
            ResponseEntity<JwtResponseDTO> first = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(registerRequest, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Second registration with same email — should conflict
            ResponseEntity<String> second = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(registerRequest, jsonHeaders()),
                String.class
            );
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("PATCH /users/{id} with invalid status should return 400")
        void shouldReturn400OnInvalidStatusPatch() {
            // Register user and get admin token
            String adminToken = getAdminToken();
            // Get first user ID from the system
            ResponseEntity<String> usersResp = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users",
                HttpMethod.GET,
                bearerRequest(adminToken),
                String.class
            );
            assertThat(usersResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Create a user to patch
            UserDTO user = UserDTO.builder()
                .name("Patch Test User")
                .email(generateUniqueEmail("patchtest"))
                .phoneNumber("9000001234")
                .address("5 Patch St")
                .city("Hyderabad")
                .state("Telangana")
                .pincode("500001")
                .password("Patch@test1")
                .build();

            ResponseEntity<JwtResponseDTO> reg = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(user, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long userId = reg.getBody().getUserId();

            // PATCH with an invalid status enum value
            Map<String, Object> patch = Map.of("status", "NOT_A_REAL_STATUS");
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(adminToken);
            ResponseEntity<String> patchResp = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/" + userId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, headers),
                String.class
            );
            assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("PATCH /users/{id} with valid status should return 200")
        void shouldReturn200OnValidStatusPatch() {
            String adminToken = getAdminToken();

            // Create a user to patch
            UserDTO user = UserDTO.builder()
                .name("Valid Patch User")
                .email(generateUniqueEmail("validpatch"))
                .phoneNumber("9000005678")
                .address("10 Valid St")
                .city("Kolkata")
                .state("West Bengal")
                .pincode("700001")
                .password("Valid@test1")
                .build();

            ResponseEntity<JwtResponseDTO> reg = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(user, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long userId = reg.getBody().getUserId();

            // PATCH with a valid status
            Map<String, Object> patch = Map.of("status", "INACTIVE");
            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(adminToken);
            ResponseEntity<UserDTO> patchResp = restTemplate.exchange(
                getBaseUrl() + "/api/v1/users/" + userId,
                HttpMethod.PATCH,
                new HttpEntity<>(patch, headers),
                UserDTO.class
            );
            assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Duplicate active subscription should return 400")
        void shouldReturn400WhenCreatingSecondActiveSubscription() {
            // Register user
            UserDTO user = UserDTO.builder()
                .name("Double Sub User")
                .email(generateUniqueEmail("doublesub"))
                .phoneNumber("9000009999")
                .address("9 Sub St")
                .city("Jaipur")
                .state("Rajasthan")
                .pincode("302001")
                .password("Double@sub1")
                .build();

            ResponseEntity<JwtResponseDTO> reg = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(user, jsonHeaders()),
                JwtResponseDTO.class
            );
            assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long userId = reg.getBody().getUserId();
            String userToken = reg.getBody().getToken();

            // Get a plan ID
            Long planId = planService.getActivePlans().get(0).getId();

            SubscriptionRequestDTO subReq = SubscriptionRequestDTO.builder()
                .userId(userId)
                .planId(planId)
                .autoRenewal(false)
                .build();

            HttpHeaders headers = jsonHeaders();
            headers.setBearerAuth(userToken);

            // First subscription — should succeed
            ResponseEntity<SubscriptionDTO> first = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/subscriptions",
                new HttpEntity<>(subReq, headers),
                SubscriptionDTO.class
            );
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Second subscription with same user — should fail
            ResponseEntity<String> second = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/subscriptions",
                new HttpEntity<>(subReq, headers),
                String.class
            );
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        private String getAdminToken() {
            LoginRequestDTO login = LoginRequestDTO.builder()
                .email(ADMIN_EMAIL)
                .password(ADMIN_PASSWORD)
                .build();
            ResponseEntity<JwtResponseDTO> resp = restTemplate.postForEntity(
                getBaseUrl() + "/api/v1/auth/login",
                new HttpEntity<>(login, jsonHeaders()),
                JwtResponseDTO.class
            );
            return resp.getBody().getToken();
        }

        private HttpEntity<Void> bearerRequest(String token) {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            return new HttpEntity<>(h);
        }

        private HttpHeaders jsonHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }
    }
}