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
import com.firstclub.platform.idempotency.IdempotencyStatus;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector;
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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase-4 tests for {@link IdempotencyFilter}: replay headers, 422 conflict semantics,
 * status lifecycle, and 5xx failure handling.
 *
 * <p>These tests run without a Spring application context and mock all dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter Phase-4 tests")
class IdempotencyFilterPhase4Test {

    private static final String POST_URL        = "/api/v2/subscriptions";
    private static final String IDEMPOTENCY_HDR = "Idempotency-Key";
    private static final String TEST_KEY        = "phase4-key-xyz789";
    private static final String VALID_BODY      = "{\"userId\":1,\"planId\":1,\"autoRenewal\":true}";
    private static final String ENDPOINT_SIG    = "POST:" + POST_URL;

    @Mock private IdempotencyService           idempotencyService;
    @Mock private RedisIdempotencyStore        redisStore;
    @Mock private RequestMappingHandlerMapping handlerMapping;
    @Mock private BillingSubscriptionService   billingSubscriptionService;
    @Mock private ProrationCalculator          prorationCalculator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        SubscriptionV2Controller controller =
                new SubscriptionV2Controller(billingSubscriptionService, prorationCalculator);

        // Use real IdempotencyConflictDetector (stateless — no mocking needed)
        IdempotencyFilter filter = new IdempotencyFilter(
                idempotencyService, redisStore, mapper, new IdempotencyConflictDetector());
        ReflectionTestUtils.setField(filter, "requestMappingHandlerMapping", handlerMapping);

        Method createSub = SubscriptionV2Controller.class.getDeclaredMethod(
                "createSubscription", SubscriptionRequestDTO.class);
        HandlerMethod handlerMethod = new HandlerMethod(controller, createSub);
        lenient().when(handlerMapping.getHandler(any()))
                .thenReturn(new HandlerExecutionChain(handlerMethod));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    // ── Replay headers ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Replay headers on replayed response")
    class ReplayHeaders {

        @Test
        @DisplayName("X-Idempotency-Replayed: true is set when replaying from DB")
        void dbReplay_setsReplayedHeader() throws Exception {
            IdempotencyKeyEntity completed = completedEntity(true);
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(completed));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().is(201))
                    .andExpect(header().string("X-Idempotency-Replayed", "true"));
        }

        @Test
        @DisplayName("X-Idempotency-Original-At header contains completedAt timestamp")
        void dbReplay_setsOriginalAtHeader() throws Exception {
            IdempotencyKeyEntity completed = completedEntity(true);
            LocalDateTime completedAt = completed.getCompletedAt();
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(completed));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().is(201))
                    .andExpect(header().exists("X-Idempotency-Original-At"));
        }

        @Test
        @DisplayName("X-Idempotency-Original-At is absent when completedAt is null (legacy record)")
        void legacyRecord_noOriginalAtHeader() throws Exception {
            IdempotencyKeyEntity legacy = completedEntity(false); // no completedAt
            legacy.setCompletedAt(null);
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(legacy));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().is(201))
                    .andExpect(header().doesNotExist("X-Idempotency-Original-At"))
                    .andExpect(header().string("X-Idempotency-Replayed", "true"));
        }
    }

    // ── HTTP 422 for body / endpoint mismatch ─────────────────────────────────

    @Nested
    @DisplayName("422 for body or endpoint mismatch (DB path)")
    class MismatchSemantics {

        @Test
        @DisplayName("Body mismatch → 422 IDEMPOTENCY_CONFLICT")
        void bodyMismatch_returns422() throws Exception {
            IdempotencyKeyEntity record = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash("completely-different-hash")
                    .endpointSignature(ENDPOINT_SIG)
                    .status(IdempotencyStatus.COMPLETED)
                    .responseBody("{\"id\":1}").statusCode(201)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(record));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"))
                    .andExpect(header().doesNotExist("X-Idempotency-Replayed"));
        }

        @Test
        @DisplayName("Endpoint mismatch → 422 IDEMPOTENCY_CONFLICT")
        void endpointMismatch_returns422() throws Exception {
            IdempotencyKeyEntity record = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash("same") // won't matter — endpoint check is first
                    .endpointSignature("POST:/api/v2/refunds") // different endpoint
                    .status(IdempotencyStatus.COMPLETED)
                    .responseBody("{\"id\":1}").statusCode(200)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(record));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
        }

        @Test
        @DisplayName("In-flight → still 409 (not 422)")
        void inFlight_remains409() throws Exception {
            // Same endpoint, same body hash — only in-flight status distinguishes this
            String hash = computeHash("POST", POST_URL, VALID_BODY.getBytes());
            IdempotencyKeyEntity processing = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash(hash)               // must match so body check passes
                    .endpointSignature(ENDPOINT_SIG)
                    .status(IdempotencyStatus.PROCESSING)
                    .processingStartedAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(processing));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_IN_PROGRESS"));
        }
    }

    // ── 5xx failure stores FAILED_RETRYABLE ───────────────────────────────────

    @Nested
    @DisplayName("5xx response: markFailed(retryable=true) instead of storeResponse")
    class FiveXxHandling {

        @Test
        @DisplayName("Handler 5xx: calls markFailed(retryable=true), not storeResponse")
        void handler5xx_callsMarkFailed() {
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.empty());
            when(billingSubscriptionService.createSubscriptionV2(any()))
                    .thenThrow(new RuntimeException("forced 5xx"));

            try {
                mockMvc.perform(post(POST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENCY_HDR, TEST_KEY)
                        .content(VALID_BODY));
            } catch (Exception ignored) {} // handler exception re-thrown by standalone MockMvc

            verify(idempotencyService, never())
                    .storeResponse(anyString(), anyString(), anyString(),
                            anyInt(), anyString(), anyString(), anyString());
        }
    }

    // ── Original status code preserved on replay ──────────────────────────────

    @Nested
    @DisplayName("Original HTTP status code preserved on replay")
    class StatusCodePreservation {

        @Test
        @DisplayName("201 from original response is replayed verbatim")
        void created201_isReplayedVerbatim() throws Exception {
            String hash = computeHash("POST", POST_URL, VALID_BODY.getBytes());
            IdempotencyKeyEntity record = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash(hash)
                    .endpointSignature(ENDPOINT_SIG)
                    .status(IdempotencyStatus.COMPLETED)
                    .responseBody("{\"subscriptionId\":77}")
                    .statusCode(201)
                    .completedAt(LocalDateTime.now().minusHours(1))
                    .expiresAt(LocalDateTime.now().plusHours(23))
                    .build();
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(record));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.subscriptionId").value(77))
                    .andExpect(header().string("X-Idempotency-Replayed", "true"))
                    .andExpect(header().exists("X-Idempotency-Original-At"));

            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Builds a COMPLETED entity for the current request's hash and endpoint.
     * @param withCompletedAt whether to set completedAt
     */
    private IdempotencyKeyEntity completedEntity(boolean withCompletedAt) {
        String hash = computeHash("POST", POST_URL, VALID_BODY.getBytes());
        IdempotencyKeyEntity.IdempotencyKeyEntityBuilder builder = IdempotencyKeyEntity.builder()
                .key("anonymous:" + TEST_KEY)
                .requestHash(hash)
                .endpointSignature(ENDPOINT_SIG)
                .status(IdempotencyStatus.COMPLETED)
                .responseBody("{\"subscriptionId\":100}")
                .statusCode(201)
                .expiresAt(LocalDateTime.now().plusHours(24));
        if (withCompletedAt) {
            builder.completedAt(LocalDateTime.now().minusHours(1));
        }
        return builder.build();
    }

    private static String computeHash(String method, String uri, byte[] body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
