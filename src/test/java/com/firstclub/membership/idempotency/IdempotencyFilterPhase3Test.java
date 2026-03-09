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
import com.firstclub.platform.idempotency.IdempotencyProcessingMarker;
import com.firstclub.platform.idempotency.IdempotencyResponseEnvelope;
import com.firstclub.platform.idempotency.IdempotencyService;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 tests for {@link IdempotencyFilter}: Redis fast-path behaviour.
 *
 * <p>All tests here set {@code redisStore.isEnabled()} to {@code true} so they
 * exercise the Redis-specific code paths (cache HIT, lock contention, endpoint
 * mismatch via Redis, etc.).  Graceful fallback when Redis is disabled is tested
 * in a dedicated nested class where {@code isEnabled()} returns {@code false}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter Phase-3 Redis tests")
class IdempotencyFilterPhase3Test {

    private static final String POST_URL        = "/api/v2/subscriptions";
    private static final String IDEMPOTENCY_HDR = "Idempotency-Key";
    private static final String TEST_KEY        = "phase3-key-abc123";
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
        IdempotencyFilter filter = new IdempotencyFilter(idempotencyService, redisStore, mapper);
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

    // ── Redis enabled: cache HIT scenarios ───────────────────────────────────

    @Nested
    @DisplayName("Redis HIT — response already cached")
    class RedisCacheHitTests {

        @BeforeEach
        void enableRedis() {
            when(redisStore.isEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Cache HIT + hash match: replays response without touching DB")
        void cacheHit_hashMatch_replaysWithoutDB() throws Exception {
            String hash = computeHash("POST", POST_URL, VALID_BODY.getBytes(StandardCharsets.UTF_8));
            IdempotencyResponseEnvelope envelope =
                    new IdempotencyResponseEnvelope(hash, ENDPOINT_SIG,
                            201, "{\"subscriptionId\":77}", MediaType.APPLICATION_JSON_VALUE);

            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(envelope));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().is(201))
                    .andExpect(content().json("{\"subscriptionId\":77}"));

            // DB must NOT be consulted when Redis cache hit
            verify(idempotencyService, never()).findByMerchantAndKey(any(), any());
            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }

        @Test
        @DisplayName("Cache HIT + hash mismatch: returns 422 IDEMPOTENCY_CONFLICT")
        void cacheHit_hashMismatch_returns422Conflict() throws Exception {
            // Envelope carries a hash computed from a DIFFERENT body
            String differentHash = computeHash("POST", POST_URL, "different-body".getBytes(StandardCharsets.UTF_8));
            IdempotencyResponseEnvelope envelope =
                    new IdempotencyResponseEnvelope(differentHash, ENDPOINT_SIG,
                            201, "{}", MediaType.APPLICATION_JSON_VALUE);

            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(envelope));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

            verify(idempotencyService, never()).findByMerchantAndKey(any(), any());
            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }

        @Test
        @DisplayName("Cache HIT + endpoint signature mismatch: returns 422 IDEMPOTENCY_CONFLICT")
        void cacheHit_endpointMismatch_returns422Conflict() throws Exception {
            String hash = computeHash("POST", POST_URL, VALID_BODY.getBytes(StandardCharsets.UTF_8));
            // Envelope claims the key was originally used against a DIFFERENT endpoint
            IdempotencyResponseEnvelope envelope =
                    new IdempotencyResponseEnvelope(hash, "POST:/api/v2/other-endpoint",
                            200, "{}", MediaType.APPLICATION_JSON_VALUE);

            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(envelope));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

            verify(idempotencyService, never()).findByMerchantAndKey(any(), any());
        }
    }

    // ── Redis enabled: in-flight lock scenarios ───────────────────────────────

    @Nested
    @DisplayName("Redis in-flight lock")
    class RedisLockTests {

        @BeforeEach
        void enableRedis() {
            when(redisStore.isEnabled()).thenReturn(true);
        }

        @Test
        @DisplayName("Processing marker present (cache miss + lock present): returns 409 IN_PROGRESS")
        void lockPresent_returns409InProgress() throws Exception {
            // No cached response…
            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            // …but the in-flight lock IS present
            IdempotencyProcessingMarker marker =
                    new IdempotencyProcessingMarker("hash", ENDPOINT_SIG,
                            LocalDateTime.now().toString(), "req-concurrent");
            when(redisStore.getProcessingMarker(anyString(), eq(TEST_KEY)))
                    .thenReturn(Optional.of(marker));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_IN_PROGRESS"));

            verify(idempotencyService, never()).findByMerchantAndKey(any(), any());
            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }

        @Test
        @DisplayName("NX lock fails (race): returns 409 IN_PROGRESS without calling handler")
        void lockNotAcquired_returns409InProgress() throws Exception {
            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(redisStore.getProcessingMarker(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            // NX lock fails — another thread won the race
            when(redisStore.tryAcquireLock(anyString(), eq(TEST_KEY), any())).thenReturn(false);

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_IN_PROGRESS"));

            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }

        @Test
        @DisplayName("Successful first request: acquires lock, caches response, releases lock")
        void firstRequest_acquiresLock_cachesAndReleasesOnSuccess() throws Exception {
            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(redisStore.getProcessingMarker(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(redisStore.tryAcquireLock(anyString(), eq(TEST_KEY), any())).thenReturn(true);

            SubscriptionV2Response dto = SubscriptionV2Response.builder()
                    .subscriptionId(55L)
                    .amountDue(BigDecimal.TEN)
                    .currency("INR")
                    .status(Subscription.SubscriptionStatus.PENDING)
                    .build();
            when(billingSubscriptionService.createSubscriptionV2(any(SubscriptionRequestDTO.class)))
                    .thenReturn(dto);

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.subscriptionId").value(55));

            // DB placeholder + DB store must be called
            verify(idempotencyService).createPlaceholder(any(), any(), any(), any(), any(Integer.class));
            verify(idempotencyService).storeResponse(any(), any(), any(), any(Integer.class), any(), any(), any());

            // Redis response cache must be populated (status 201 < 500)
            ArgumentCaptor<IdempotencyResponseEnvelope> envCaptor =
                    ArgumentCaptor.forClass(IdempotencyResponseEnvelope.class);
            verify(redisStore).cacheResponse(anyString(), eq(TEST_KEY), envCaptor.capture(), anyLong());
            assertThat(envCaptor.getValue().statusCode()).isEqualTo(201);

            // Lock must be released in the finally block
            verify(redisStore).releaseLock(anyString(), eq(TEST_KEY));
        }

        @Test
        @DisplayName("Unhandled handler exception: lock still released in finally")
        void handlerThrows_lockAlwaysReleased() {
            when(redisStore.tryGetCachedResponse(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(redisStore.getProcessingMarker(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(idempotencyService.findByMerchantAndKey(anyString(), eq(TEST_KEY))).thenReturn(Optional.empty());
            when(redisStore.tryAcquireLock(anyString(), eq(TEST_KEY), any())).thenReturn(true);
            when(billingSubscriptionService.createSubscriptionV2(any()))
                    .thenThrow(new RuntimeException("upstream failure"));

            // standalone MockMvc re-throws unhandled exceptions as NestedServletException — that is expected
            try {
                mockMvc.perform(post(POST_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENCY_HDR, TEST_KEY)
                        .content(VALID_BODY));
            } catch (Exception ignored) {}

            // Lock must be released even when the handler throws
            verify(redisStore).releaseLock(anyString(), eq(TEST_KEY));
        }
    }

    // ── Redis disabled: falls through to DB ───────────────────────────────────

    @Nested
    @DisplayName("Redis disabled — falls through gracefully to DB")
    class RedisDisabledFallbackTests {

        @Test
        @DisplayName("Redis disabled + DB has processed record: replays response without Redis calls")
        void redisDisabled_dbHasProcessedRecord_replays() throws Exception {
            // Redis is disabled — isEnabled() returns false by Mockito default
            String hash = computeHash("POST", POST_URL, VALID_BODY.getBytes(StandardCharsets.UTF_8));
            IdempotencyKeyEntity processed = IdempotencyKeyEntity.builder()
                    .key("anonymous:" + TEST_KEY)
                    .requestHash(hash)
                    .responseBody("{\"subscriptionId\":999}")
                    .statusCode(201)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            when(idempotencyService.findByMerchantAndKey(any(), any())).thenReturn(Optional.of(processed));

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().is(201))
                    .andExpect(content().json("{\"subscriptionId\":999}"));

            // No Redis interaction when disabled
            verify(redisStore, never()).tryGetCachedResponse(any(), any());
            verify(redisStore, never()).tryAcquireLock(any(), any(), any());
            verify(billingSubscriptionService, never()).createSubscriptionV2(any());
        }

        @Test
        @DisplayName("Redis disabled: no Redis calls at all, even for a first-time request")
        void redisDisabled_firstRequest_noRedisInteraction() throws Exception {
            when(idempotencyService.findByMerchantAndKey(any(), any())).thenReturn(Optional.empty());
            SubscriptionV2Response dto = SubscriptionV2Response.builder()
                    .subscriptionId(1L)
                    .amountDue(BigDecimal.ONE)
                    .currency("INR")
                    .status(Subscription.SubscriptionStatus.PENDING)
                    .build();
            when(billingSubscriptionService.createSubscriptionV2(any())).thenReturn(dto);

            mockMvc.perform(post(POST_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(IDEMPOTENCY_HDR, TEST_KEY)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated());

            // No Redis calls at all when disabled
            verify(redisStore, never()).tryGetCachedResponse(any(), any());
            verify(redisStore, never()).tryAcquireLock(any(), any(), any());
            verify(redisStore, never()).cacheResponse(any(), any(), any(), anyLong());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

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
