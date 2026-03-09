package com.firstclub.platform.dedup;

import com.firstclub.platform.redis.RedisKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BusinessEffectDedupService}.
 *
 * Covers: Redis HIT → DUPLICATE, Redis MISS + DB HIT → DUPLICATE,
 * Redis MISS + DB MISS → NEW + INSERT, concurrent INSERT race → DUPLICATE,
 * Redis unavailable → DB fallback.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BusinessEffectDedupService Unit Tests")
class BusinessEffectDedupServiceTest {

    @Mock private ObjectProvider<StringRedisTemplate> templateProvider;
    @Mock private RedisKeyFactory                     keyFactory;
    @Mock private BusinessEffectFingerprintRepository repository;
    @Mock private StringRedisTemplate                 template;
    @Mock private ValueOperations<String, String>     valueOps;

    @InjectMocks
    private BusinessEffectDedupService dedupService;

    private static final String EFFECT   = BusinessEffectType.PAYMENT_CAPTURE_SUCCESS;
    private static final String FP       = "abc123fingerprint456789012345678901234567890123456789012345";
    private static final String REDIS_KEY= "dev:firstclub:dedup:biz:payment_capture_success:" + FP;

    @BeforeEach
    void setUp() {
        when(keyFactory.bizEffectDedupKey(anyString(), anyString())).thenReturn(REDIS_KEY);
        when(templateProvider.getIfAvailable()).thenReturn(template);
        when(template.opsForValue()).thenReturn(valueOps);
    }

    // ── Redis HIT ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns DUPLICATE when Redis SET NX returns false (key already exists)")
    void redisFastPath_returnsDistinct() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), eq("1"), any())).thenReturn(false);

        DedupResult result = dedupService.checkAndRecord(EFFECT, FP, "PAYMENT", 1L);

        assertThat(result).isEqualTo(DedupResult.DUPLICATE);
        verifyNoInteractions(repository);
    }

    // ── Redis MISS + DB HIT ────────────────────────────────────────────────

    @Test
    @DisplayName("returns DUPLICATE when Redis is new but DB already has the fingerprint")
    void redisMiss_dbHit_returnsDuplicate() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), eq("1"), any())).thenReturn(true);
        when(repository.existsByEffectTypeAndFingerprint(EFFECT, FP)).thenReturn(true);

        DedupResult result = dedupService.checkAndRecord(EFFECT, FP, "PAYMENT", 1L);

        assertThat(result).isEqualTo(DedupResult.DUPLICATE);
        verify(repository, never()).save(any());
    }

    // ── Redis MISS + DB MISS → NEW ─────────────────────────────────────────

    @Test
    @DisplayName("returns NEW and saves fingerprint when neither Redis nor DB has seen it")
    void redisMiss_dbMiss_returnsNewAndSaves() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), eq("1"), any())).thenReturn(true);
        when(repository.existsByEffectTypeAndFingerprint(EFFECT, FP)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DedupResult result = dedupService.checkAndRecord(EFFECT, FP, "PAYMENT", 42L);

        assertThat(result).isEqualTo(DedupResult.NEW);
        verify(repository).save(argThat(saved ->
                EFFECT.equals(saved.getEffectType()) &&
                FP.equals(saved.getFingerprint()) &&
                "PAYMENT".equals(saved.getReferenceType()) &&
                Long.valueOf(42L).equals(saved.getReferenceId())
        ));
    }

    // ── Concurrent INSERT race ─────────────────────────────────────────────

    @Test
    @DisplayName("handles concurrent INSERT: DataIntegrityViolationException → DUPLICATE")
    void concurrentInsertRace_returnsDuplicate() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), eq("1"), any())).thenReturn(true);
        when(repository.existsByEffectTypeAndFingerprint(EFFECT, FP)).thenReturn(false);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        DedupResult result = dedupService.checkAndRecord(EFFECT, FP, "PAYMENT", 99L);

        assertThat(result).isEqualTo(DedupResult.DUPLICATE);
    }

    // ── Redis unavailable ─────────────────────────────────────────────────

    @Test
    @DisplayName("falls through to DB when Redis is unavailable (returns null from ObjectProvider)")
    void redisUnavailable_fallsThroughToDb() {
        when(templateProvider.getIfAvailable()).thenReturn(null);
        when(repository.existsByEffectTypeAndFingerprint(EFFECT, FP)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DedupResult result = dedupService.checkAndRecord(EFFECT, FP, "PAYMENT", 1L);

        assertThat(result).isEqualTo(DedupResult.NEW);
        verify(repository).save(any());
    }

    // ── isKnownDuplicate ──────────────────────────────────────────────────

    @Test
    @DisplayName("isKnownDuplicate returns true when Redis marker exists")
    void isKnownDuplicate_redisHit() {
        when(template.hasKey(REDIS_KEY)).thenReturn(true);

        assertThat(dedupService.isKnownDuplicate(EFFECT, FP)).isTrue();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("isKnownDuplicate returns false when Redis and DB both miss")
    void isKnownDuplicate_bothMiss() {
        when(template.hasKey(REDIS_KEY)).thenReturn(false);
        when(repository.existsByEffectTypeAndFingerprint(EFFECT, FP)).thenReturn(false);

        assertThat(dedupService.isKnownDuplicate(EFFECT, FP)).isFalse();
    }
}
