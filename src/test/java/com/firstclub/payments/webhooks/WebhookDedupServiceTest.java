package com.firstclub.payments.webhooks;

import com.firstclub.payments.entity.WebhookEvent;
import com.firstclub.payments.repository.WebhookEventRepository;
import com.firstclub.platform.dedup.DedupResult;
import com.firstclub.platform.redis.RedisKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebhookDedupService}.
 *
 * Covers: event-id Redis HIT, event-id Redis MISS + DB HIT, event-id Redis MISS + DB MISS,
 * payload-hash Redis HIT, payload-hash Redis MISS, Redis unavailable graceful degradation.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("WebhookDedupService Unit Tests")
class WebhookDedupServiceTest {

    @Mock private ObjectProvider<StringRedisTemplate> templateProvider;
    @Mock private RedisKeyFactory                     keyFactory;
    @Mock private WebhookEventRepository              webhookEventRepository;
    @Mock private StringRedisTemplate                 template;
    @Mock private ValueOperations<String, String>     valueOps;

    @InjectMocks
    private WebhookDedupService dedupService;

    private static final String PROVIDER  = "razorpay";
    private static final String EVENT_ID  = "evt_12345";
    private static final String EVENT_KEY = "dev:firstclub:dedup:webhook:razorpay:evt_12345";
    private static final String HASH_KEY  = "dev:firstclub:dedup:webhookfp:razorpay:abc123";
    private static final String HASH      = "abc123";

    @BeforeEach
    void setUp() {
        when(templateProvider.getIfAvailable()).thenReturn(template);
        when(template.opsForValue()).thenReturn(valueOps);
    }

    // ── checkByEventId: Redis HIT ──────────────────────────────────────────

    @Test
    @DisplayName("checkByEventId returns DUPLICATE when Redis SET NX returns false")
    void eventIdCheck_redisHit_returnsDuplicate() {
        when(keyFactory.webhookEventDedupKey(PROVIDER, EVENT_ID)).thenReturn(EVENT_KEY);
        when(valueOps.setIfAbsent(eq(EVENT_KEY), eq("1"), any())).thenReturn(false);

        DedupResult result = dedupService.checkByEventId(PROVIDER, EVENT_ID);

        assertThat(result).isEqualTo(DedupResult.DUPLICATE);
        verifyNoInteractions(webhookEventRepository);
    }

    // ── checkByEventId: Redis MISS + DB HIT ───────────────────────────────

    @Test
    @DisplayName("checkByEventId returns DUPLICATE when Redis is new but DB row is processed")
    void eventIdCheck_redisMiss_dbHit_returnsDuplicate() {
        when(keyFactory.webhookEventDedupKey(PROVIDER, EVENT_ID)).thenReturn(EVENT_KEY);
        when(valueOps.setIfAbsent(eq(EVENT_KEY), eq("1"), any())).thenReturn(true);

        WebhookEvent processedEvent = WebhookEvent.builder()
                .eventId(EVENT_ID).processed(true).build();
        when(webhookEventRepository.findByEventId(EVENT_ID))
                .thenReturn(Optional.of(processedEvent));

        DedupResult result = dedupService.checkByEventId(PROVIDER, EVENT_ID);

        assertThat(result).isEqualTo(DedupResult.DUPLICATE);
    }

    // ── checkByEventId: Redis MISS + DB MISS ──────────────────────────────

    @Test
    @DisplayName("checkByEventId returns NEW when neither Redis nor DB has seen event")
    void eventIdCheck_redisMiss_dbMiss_returnsNew() {
        when(keyFactory.webhookEventDedupKey(PROVIDER, EVENT_ID)).thenReturn(EVENT_KEY);
        when(valueOps.setIfAbsent(eq(EVENT_KEY), eq("1"), any())).thenReturn(true);
        when(webhookEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());

        DedupResult result = dedupService.checkByEventId(PROVIDER, EVENT_ID);

        assertThat(result).isEqualTo(DedupResult.NEW);
    }

    // ── checkByPayloadHash ────────────────────────────────────────────────

    @Test
    @DisplayName("checkByPayloadHash returns DUPLICATE when Redis SET NX returns false")
    void payloadHash_redisHit_returnsDuplicate() {
        when(keyFactory.webhookPayloadFingerprintKey(PROVIDER, HASH)).thenReturn(HASH_KEY);
        when(valueOps.setIfAbsent(eq(HASH_KEY), eq("1"), any())).thenReturn(false);

        DedupResult result = dedupService.checkByPayloadHash(PROVIDER, HASH);

        assertThat(result).isEqualTo(DedupResult.DUPLICATE);
    }

    @Test
    @DisplayName("checkByPayloadHash returns NEW when Redis SET NX succeeds")
    void payloadHash_redisMiss_returnsNew() {
        when(keyFactory.webhookPayloadFingerprintKey(PROVIDER, HASH)).thenReturn(HASH_KEY);
        when(valueOps.setIfAbsent(eq(HASH_KEY), eq("1"), any())).thenReturn(true);

        DedupResult result = dedupService.checkByPayloadHash(PROVIDER, HASH);

        assertThat(result).isEqualTo(DedupResult.NEW);
    }

    // ── Redis unavailable ─────────────────────────────────────────────────

    @Test
    @DisplayName("checkByEventId falls back to DB-only check when Redis is unavailable")
    void eventIdCheck_redisUnavailable_fallsBackToDb() {
        when(templateProvider.getIfAvailable()).thenReturn(null);
        when(webhookEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());

        DedupResult result = dedupService.checkByEventId(PROVIDER, EVENT_ID);

        assertThat(result).isEqualTo(DedupResult.NEW);
    }

    @Test
    @DisplayName("checkByPayloadHash returns NEW (skip) when Redis is unavailable")
    void payloadHash_redisUnavailable_returnsNew() {
        when(templateProvider.getIfAvailable()).thenReturn(null);

        DedupResult result = dedupService.checkByPayloadHash(PROVIDER, "somehash");

        assertThat(result).isEqualTo(DedupResult.NEW);
    }

    // ── computePayloadHash ────────────────────────────────────────────────

    @Test
    @DisplayName("computePayloadHash is deterministic for same input")
    void computePayloadHash_isDeterministic() {
        String h1 = dedupService.computePayloadHash("{\"key\":\"value\"}");
        String h2 = dedupService.computePayloadHash("{\"key\":\"value\"}");
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    @DisplayName("computePayloadHash differs for different payloads")
    void computePayloadHash_differentInputs_differentOutput() {
        String h1 = dedupService.computePayloadHash("payload_a");
        String h2 = dedupService.computePayloadHash("payload_b");
        assertThat(h1).isNotEqualTo(h2);
    }
}
