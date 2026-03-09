package com.firstclub.membership.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.firstclub.billing.dto.SubscriptionV2Response;
import com.firstclub.billing.service.BillingSubscriptionService;
import com.firstclub.billing.service.ProrationCalculator;
import com.firstclub.membership.controller.SubscriptionV2Controller;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.platform.idempotency.IdempotencyFilter;
import com.firstclub.platform.idempotency.IdempotencyKeyEntity;
import com.firstclub.platform.idempotency.IdempotencyService;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link IdempotencyFilter} using a standalone {@link MockMvc} setup.
 *
 * <p>No Spring application context is started — Mockito injects all
 * collaborators, and {@link ReflectionTestUtils} wires the
 * {@code @Lazy RequestMappingHandlerMapping} field that the filter uses to
 * detect the {@link com.firstclub.platform.idempotency.annotation.Idempotent}
 * annotation on handler methods.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter tests")
class IdempotencyFilterTest {

    private static final String POST_URL        = "/api/v2/subscriptions";
    private static final String IDEMPOTENCY_HDR = "Idempotency-Key";
    private static final String TEST_KEY        = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_BODY      = "{\"userId\":1,\"planId\":1,\"autoRenewal\":true}";

    @Mock private IdempotencyService          idempotencyService;
    @Mock private RedisIdempotencyStore       redisStore;
    @Mock private RequestMappingHandlerMapping handlerMapping;
    @Mock private BillingSubscriptionService  billingSubscriptionService;
    @Mock private ProrationCalculator         prorationCalculator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        SubscriptionV2Controller controller = new SubscriptionV2Controller(billingSubscriptionService, prorationCalculator);
        // redisStore.isEnabled() returns false by default (Mockito default for boolean)
        IdempotencyFilter filter = new IdempotencyFilter(idempotencyService, redisStore, mapper);

        // Inject the mocked RequestMappingHandlerMapping into the filter's lazy field.
        ReflectionTestUtils.setField(filter, "requestMappingHandlerMapping", handlerMapping);

        // Stub the handler mapping to return the @Idempotent-annotated method for all requests.
        Method createSub = SubscriptionV2Controller.class.getDeclaredMethod(
                "createSubscription", SubscriptionRequestDTO.class);
        HandlerMethod handlerMethod = new HandlerMethod(controller, createSub);
        lenient().when(handlerMapping.getHandler(any()))
                 .thenReturn(new HandlerExecutionChain(handlerMethod));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    // =========================================================================
    // Missing Idempotency-Key header
    // =========================================================================

    @Nested
    @DisplayName("Missing Idempotency-Key header")
    class MissingKeyTests {

        @Test
        @DisplayName("Returns 400 when Idempotency-Key header is absent")
        void missingKey_returns400() throws Exception {
            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));

            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }
    }

    // =========================================================================
    // First request — key does not exist yet
    // =========================================================================

    @Nested
    @DisplayName("New idempotency key (first request)")
    class NewKeyTests {

        @Test
        @DisplayName("Creates subscription and stores response when key is new")
        void newKey_createsSubscriptionAndStoresResponse() throws Exception {
            when(idempotencyService.findByMerchantAndKey(any(), any())).thenReturn(Optional.empty());

            SubscriptionV2Response dto = SubscriptionV2Response.builder()
                    .subscriptionId(42L)
                    .amountDue(new BigDecimal("299"))
                    .currency("INR")
                    .status(Subscription.SubscriptionStatus.PENDING)
                    .build();
            when(billingSubscriptionService.createSubscriptionV2(any(SubscriptionRequestDTO.class))).thenReturn(dto);

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.subscriptionId").value(42));

            verify(idempotencyService).createPlaceholder(any(), any(), any(), any(), any(Integer.class));
            verify(idempotencyService).storeResponse(any(), any(), any(), any(Integer.class), any(), any(), any());
        }
    }

    // =========================================================================
    // Duplicate request — same key, same payload → replay stored response
    // =========================================================================

    @Nested
    @DisplayName("Duplicate request (same key + hash) — replay stored response")
    class DuplicateKeyTests {

        @Test
        @DisplayName("Returns stored response without calling service")
        void duplicateKey_samePayload_replaysStoredResponse() throws Exception {
            // Compute the exact hash the filter will compute for this request.
            String actualHash = computeHash("POST", POST_URL,
                    VALID_BODY.getBytes(StandardCharsets.UTF_8));

            IdempotencyKeyEntity processed = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash(actualHash)
                    .responseBody("{\"id\":99,\"status\":\"ACTIVE\"}")
                    .statusCode(201)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            when(idempotencyService.findByMerchantAndKey(any(), any())).thenReturn(Optional.of(processed));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().is(201))
                    .andExpect(content().json("{\"id\":99,\"status\":\"ACTIVE\"}"));

            // Handler should never be called for a replayed response.
            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }
    }

    // =========================================================================
    // Conflicting request — same key, different payload → 409
    // =========================================================================

    @Nested
    @DisplayName("Conflicting request (same key, different payload) — 422")
    class ConflictingKeyTests {

        @Test
        @DisplayName("Returns 422 when same key is reused with a different body")
        void sameKey_differentPayload_returns422() throws Exception {
            // A stored record whose hash will NOT match the hash of VALID_BODY.
            IdempotencyKeyEntity existingRecord = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            when(idempotencyService.findByMerchantAndKey(any(), any())).thenReturn(Optional.of(existingRecord));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }
    }

    // =========================================================================
    // Helper — mirrors the SHA-256 hash logic in IdempotencyFilter
    // =========================================================================

    private static String computeHash(String method, String uri, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update(uri.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
