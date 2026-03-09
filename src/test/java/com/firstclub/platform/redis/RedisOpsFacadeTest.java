package com.firstclub.platform.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisOpsFacade}.
 *
 * <p>Uses Mockito to simulate the {@link StringRedisTemplate} — no Redis
 * connection required. Every method is tested against both the happy path
 * and a Redis failure path to verify safe degradation.
 */
@DisplayName("RedisOpsFacade — Unit Tests")
class RedisOpsFacadeTest {

    private StringRedisTemplate template;
    private ValueOperations<String, String> valueOps;
    private RedisOpsFacade facade;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        facade = new RedisOpsFacade(template);
    }

    // ── setIfAbsent ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setIfAbsent")
    class SetIfAbsentTests {

        @Test
        @DisplayName("returns true when Redis reports key was set")
        void returnsTrue_whenKeyWasSet() {
            when(valueOps.setIfAbsent("key:1", "val", Duration.ofSeconds(30)))
                    .thenReturn(Boolean.TRUE);
            assertThat(facade.setIfAbsent("key:1", "val", Duration.ofSeconds(30))).isTrue();
        }

        @Test
        @DisplayName("returns false when key already exists")
        void returnsFalse_whenKeyAlreadyExists() {
            when(valueOps.setIfAbsent("key:1", "val", Duration.ofSeconds(30)))
                    .thenReturn(Boolean.FALSE);
            assertThat(facade.setIfAbsent("key:1", "val", Duration.ofSeconds(30))).isFalse();
        }

        @Test
        @DisplayName("returns false on RedisConnectionFailureException (safe degradation)")
        void returnsFalse_onConnectionFailure() {
            when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                    .thenThrow(new RedisConnectionFailureException("refused"));
            assertThat(facade.setIfAbsent("key:1", "val", Duration.ofSeconds(30))).isFalse();
        }

        @Test
        @DisplayName("returns false when template returns null")
        void returnsFalse_whenTemplateReturnsNull() {
            when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                    .thenReturn(null);
            assertThat(facade.setIfAbsent("key:1", "val", Duration.ofSeconds(30))).isFalse();
        }
    }

    // ── set ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("set")
    class SetTests {

        @Test
        @DisplayName("calls template.set with key, value, and ttl")
        void callsTemplate_withCorrectArgs() {
            facade.set("key:2", "value", Duration.ofMinutes(5));
            verify(valueOps).set("key:2", "value", Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("does not throw on Redis failure")
        void noException_onRedisFailure() {
            doThrow(new RedisConnectionFailureException("down"))
                    .when(valueOps).set(any(), any(), any(Duration.class));
            // must not throw
            facade.set("key:2", "value", Duration.ofMinutes(5));
        }
    }

    // ── get ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("returns Optional with value when key exists")
        void returnsOptional_whenKeyExists() {
            when(valueOps.get("key:3")).thenReturn("cached-value");
            assertThat(facade.get("key:3")).contains("cached-value");
        }

        @Test
        @DisplayName("returns empty Optional when key is absent")
        void returnsEmpty_whenKeyAbsent() {
            when(valueOps.get("key:3")).thenReturn(null);
            assertThat(facade.get("key:3")).isEmpty();
        }

        @Test
        @DisplayName("returns empty Optional on Redis failure")
        void returnsEmpty_onRedisFailure() {
            when(valueOps.get(any()))
                    .thenThrow(new RedisConnectionFailureException("refused"));
            assertThat(facade.get("key:3")).isEmpty();
        }
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("returns true when key was deleted")
        void returnsTrue_whenDeleted() {
            when(template.delete("key:4")).thenReturn(Boolean.TRUE);
            assertThat(facade.delete("key:4")).isTrue();
        }

        @Test
        @DisplayName("returns false when key not found")
        void returnsFalse_whenNotFound() {
            when(template.delete("key:4")).thenReturn(Boolean.FALSE);
            assertThat(facade.delete("key:4")).isFalse();
        }

        @Test
        @DisplayName("returns false on Redis failure")
        void returnsFalse_onRedisFailure() {
            when(template.delete(any(String.class)))
                    .thenThrow(new RedisConnectionFailureException("refused"));
            assertThat(facade.delete("key:4")).isFalse();
        }
    }

    // ── increment ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("increment")
    class IncrementTests {

        @Test
        @DisplayName("returns incremented value")
        void returnsIncrementedValue() {
            when(valueOps.increment("counter:1")).thenReturn(7L);
            assertThat(facade.increment("counter:1")).isEqualTo(7L);
        }

        @Test
        @DisplayName("returns -1 on Redis failure")
        void returnsMinusOne_onRedisFailure() {
            when(valueOps.increment(any()))
                    .thenThrow(new RedisConnectionFailureException("down"));
            assertThat(facade.increment("counter:1")).isEqualTo(-1L);
        }

        @Test
        @DisplayName("returns -1 when template returns null")
        void returnsMinusOne_whenTemplateReturnsNull() {
            when(valueOps.increment(any())).thenReturn(null);
            assertThat(facade.increment("counter:1")).isEqualTo(-1L);
        }
    }

    // ── expire ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("expire")
    class ExpireTests {

        @Test
        @DisplayName("returns true when TTL was updated")
        void returnsTrue_whenTtlUpdated() {
            when(template.expire("key:5", 30_000L, TimeUnit.MILLISECONDS))
                    .thenReturn(Boolean.TRUE);
            assertThat(facade.expire("key:5", Duration.ofSeconds(30))).isTrue();
        }

        @Test
        @DisplayName("returns false on Redis failure")
        void returnsFalse_onRedisFailure() {
            when(template.expire(any(), anyLong(), any()))
                    .thenThrow(new RedisConnectionFailureException("down"));
            assertThat(facade.expire("key:5", Duration.ofSeconds(30))).isFalse();
        }
    }

    // ── executePipelined ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("executePipelined")
    class PipelineTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("returns results from pipeline execution")
        void returnsResults_onSuccess() {
            SessionCallback<Object> callback = mock(SessionCallback.class);
            when(template.executePipelined(callback)).thenReturn(List.of("r1", "r2"));
            assertThat(facade.executePipelined(callback)).containsExactly("r1", "r2");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("returns empty list on Redis failure")
        void returnsEmptyList_onRedisFailure() {
            SessionCallback<Object> callback = mock(SessionCallback.class);
            when(template.executePipelined(any(SessionCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("down"));
            assertThat(facade.executePipelined(callback)).isEmpty();
        }
    }
}
