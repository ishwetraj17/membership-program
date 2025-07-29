package com.firstclub.membership;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.MembershipService;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "logging.level.org.hibernate.SQL=WARN"
})
@DisplayName("FirstClub Membership Program - Integration Tests")
class MembershipApplicationTests {

    @Autowired
    private MembershipService membershipService;
    
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
        }

        @Test
        @DisplayName("Should initialize 3 membership tiers correctly")
        void shouldInitializeMembershipTiers() {
            List<com.firstclub.membership.entity.MembershipTier> tiers = membershipService.getAllTiers();
            
            assertThat(tiers).hasSize(3);
            assertThat(tiers)
                .extracting(tier -> tier.getName())
                .containsExactlyInAnyOrder("SILVER", "GOLD", "PLATINUM");
        }

        @Test
        @DisplayName("Should initialize 9 membership plans (3 tiers × 3 durations)")
        void shouldInitializeMembershipPlans() {
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            
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
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            
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
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            
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
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            
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
            List<com.firstclub.membership.entity.MembershipTier> tiers = membershipService.getAllTiers();
            
            com.firstclub.membership.entity.MembershipTier silverTier = tiers.stream()
                .filter(t -> t.getName().equals("SILVER")).findFirst().orElseThrow();
            com.firstclub.membership.entity.MembershipTier goldTier = tiers.stream()
                .filter(t -> t.getName().equals("GOLD")).findFirst().orElseThrow();
            com.firstclub.membership.entity.MembershipTier platinumTier = tiers.stream()
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
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            MembershipPlanDTO plan = plans.get(0);

            SubscriptionRequestDTO request = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plan.getId())
                .autoRenewal(true)
                .build();
            
            SubscriptionDTO subscription = membershipService.createSubscription(request);

            List<SubscriptionDTO> userSubscriptions = membershipService.getUserSubscriptions(createdUser.getId());

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
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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

            assertThatThrownBy(() -> membershipService.getUserSubscriptions(nonExistentUserId))
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
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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
                .hasMessageContaining("Cannot cancel non-active subscription");
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
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            
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
            List<MembershipPlanDTO> monthlyPlans = membershipService.getPlansByType(MembershipPlan.PlanType.MONTHLY);
            List<MembershipPlanDTO> quarterlyPlans = membershipService.getPlansByType(MembershipPlan.PlanType.QUARTERLY);
            List<MembershipPlanDTO> yearlyPlans = membershipService.getPlansByType(MembershipPlan.PlanType.YEARLY);

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
            List<MembershipPlanDTO> silverPlans = membershipService.getPlansByTier("SILVER");
            List<MembershipPlanDTO> goldPlans = membershipService.getPlansByTier("GOLD");
            List<MembershipPlanDTO> platinumPlans = membershipService.getPlansByTier("PLATINUM");

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
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();

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
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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

        private <T> HttpEntity<T> createJsonRequest(T body) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(body, headers);
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

            ResponseEntity<UserDTO> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/users/" + createdUser.getId(),
                UserDTO.class
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

            ResponseEntity<UserDTO> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/users/email/" + uniqueEmail,
                UserDTO.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getEmail()).isEqualTo(uniqueEmail);
            assertThat(response.getBody().getName()).isEqualTo("Email Test User");
        }

        @Test
        @DisplayName("Plan API - Get all plans via GET /api/v1/membership/plans")
        void shouldGetAllPlansViaRestApi() {
            ResponseEntity<MembershipPlanDTO[]> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/plans",
                MembershipPlanDTO[].class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().length).isEqualTo(9);
        }

        @Test
        @DisplayName("Plan API - Get plans by tier via GET /api/v1/membership/plans/tier/{tier}")
        void shouldGetPlansByTierViaRestApi() {
            ResponseEntity<MembershipPlanDTO[]> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/plans/tier/GOLD",
                MembershipPlanDTO[].class
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
            ResponseEntity<MembershipPlanDTO[]> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/plans/type/YEARLY",
                MembershipPlanDTO[].class
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
            ResponseEntity<Object> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/tiers",
                Object.class
            );

            // Should return 200 OK with @JsonIgnore fix
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Subscription API - Create subscription via POST /api/v1/membership/subscriptions")
        void shouldCreateSubscriptionViaRestApi() {
            UserDTO userRequest = createTestUser("Sub API Test", "subapi");
            UserDTO createdUser = userService.createUser(userRequest);
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
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
                getBaseUrl() + "/api/v1/membership/subscriptions",
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
        @DisplayName("Subscription API - Cancel subscription via PUT /api/v1/membership/subscriptions/{id}/cancel")
        void shouldCancelSubscriptionViaRestApi() {
            UserDTO userRequest = createTestUser("Cancel API Test", "cancelapi");
            UserDTO createdUser = userService.createUser(userRequest);
            
            List<MembershipPlanDTO> plans = membershipService.getActivePlans();
            SubscriptionRequestDTO subscriptionRequest = SubscriptionRequestDTO.builder()
                .userId(createdUser.getId())
                .planId(plans.get(0).getId())
                .autoRenewal(true)
                .build();

            SubscriptionDTO createdSubscription = membershipService.createSubscription(subscriptionRequest);

            String cancellationReason = "API Test Cancellation";
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("reason", cancellationReason);
            HttpEntity<Map<String, String>> request = createJsonRequest(requestBody);

            ResponseEntity<SubscriptionDTO> response = restTemplate.exchange(
                getBaseUrl() + "/api/v1/membership/subscriptions/" + createdSubscription.getId() + "/cancel",
                HttpMethod.PUT,
                request,
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
            ResponseEntity<Object> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/health",
                Object.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("System API - Get analytics via GET /api/v1/membership/analytics")
        void shouldGetAnalyticsViaRestApi() {
            ResponseEntity<Object> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/analytics",
                Object.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("Error Handling - Should return 404 for non-existent user")
        void shouldHandle404ForNonExistentUser() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/users/99999",
                String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Error Handling - Should return 404 for non-existent plan")
        void shouldHandle404ForNonExistentPlan() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/api/v1/membership/plans/99999",
                String.class
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
                getBaseUrl() + "/api/v1/membership/subscriptions",
                request,
                String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}