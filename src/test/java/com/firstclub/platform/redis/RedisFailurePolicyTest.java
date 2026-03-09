package com.firstclub.platform.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RedisErrorClassification} and {@link RedisFailureBehavior}.
 *
 * <p>Verifies that exception types are correctly categorised and that
 * each enum variant carries the expected properties.
 */
@DisplayName("Redis Failure Policy — Unit Tests")
class RedisFailurePolicyTest {

    // ── RedisErrorClassification.classify() ───────────────────────────────────

    @Nested
    @DisplayName("RedisErrorClassification.classify()")
    class ClassifyTests {

        @Test
        @DisplayName("SerializationException → SERIALIZATION")
        void serializationException_classifiedAsSerialization() {
            SerializationException ex = new SerializationException("Bad JSON");
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.SERIALIZATION);
        }

        @Test
        @DisplayName("RedisConnectionFailureException → UNAVAILABLE")
        void connectionFailure_classifiedAsUnavailable() {
            RedisConnectionFailureException ex =
                    new RedisConnectionFailureException("Connection refused");
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.UNAVAILABLE);
        }

        @Test
        @DisplayName("QueryTimeoutException → TRANSIENT")
        void queryTimeout_classifiedAsTransient() {
            QueryTimeoutException ex = new QueryTimeoutException("Command timed out");
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.TRANSIENT);
        }

        @Test
        @DisplayName("RedisSystemException with 'connect' message → UNAVAILABLE")
        void redisSystemException_connectMessage_classifiedAsUnavailable() {
            RedisSystemException ex = new RedisSystemException(
                    "connect: connection refused", new RuntimeException());
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.UNAVAILABLE);
        }

        @Test
        @DisplayName("RedisSystemException with 'refused' message → UNAVAILABLE")
        void redisSystemException_refusedMessage_classifiedAsUnavailable() {
            RedisSystemException ex = new RedisSystemException(
                    "Connection refused to host", new RuntimeException());
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.UNAVAILABLE);
        }

        @Test
        @DisplayName("RedisSystemException with generic message → TRANSIENT")
        void redisSystemException_generic_classifiedAsTransient() {
            RedisSystemException ex = new RedisSystemException(
                    "Unknown system error", new RuntimeException());
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.TRANSIENT);
        }

        @Test
        @DisplayName("unknown RuntimeException defaults to TRANSIENT")
        void unknownException_defaultsToTransient() {
            RuntimeException ex = new RuntimeException("Something unexpected");
            assertThat(RedisErrorClassification.classify(ex))
                    .isEqualTo(RedisErrorClassification.TRANSIENT);
        }

        @Test
        @DisplayName("all classifications have non-blank descriptions")
        void allClassifications_haveNonBlankDescriptions() {
            for (RedisErrorClassification c : RedisErrorClassification.values()) {
                assertThat(c.getDescription())
                        .as("description for %s", c.name())
                        .isNotBlank();
            }
        }
    }

    // ── RedisFailureBehavior ───────────────────────────────────────────────────

    @Nested
    @DisplayName("RedisFailureBehavior enum")
    class FailureBehaviorTests {

        @Test
        @DisplayName("all three behavior values are defined")
        void allThreeBehaviors_areDefined() {
            assertThat(RedisFailureBehavior.values())
                    .containsExactlyInAnyOrder(
                            RedisFailureBehavior.ALLOW,
                            RedisFailureBehavior.REJECT,
                            RedisFailureBehavior.DEGRADE_TO_DB);
        }

        @Test
        @DisplayName("all behaviors have non-blank descriptions")
        void allBehaviors_haveNonBlankDescriptions() {
            for (RedisFailureBehavior b : RedisFailureBehavior.values()) {
                assertThat(b.getDescription())
                        .as("description for %s", b.name())
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("ALLOW description mentions DB or default fallback")
        void allow_descriptionMentionsDbOrDefault() {
            assertThat(RedisFailureBehavior.ALLOW.getDescription().toLowerCase())
                    .containsAnyOf("db", "fallback", "default");
        }

        @Test
        @DisplayName("REJECT description mentions propagate or exception")
        void reject_descriptionMentionsPropagateOrException() {
            assertThat(RedisFailureBehavior.REJECT.getDescription().toLowerCase())
                    .containsAnyOf("propagate", "exception", "fail");
        }

        @Test
        @DisplayName("DEGRADE_TO_DB description mentions database or source of truth")
        void degradeToDb_descriptionMentionsDatabaseOrTruth() {
            assertThat(RedisFailureBehavior.DEGRADE_TO_DB.getDescription().toLowerCase())
                    .containsAnyOf("database", "db", "truth");
        }
    }
}
