package com.firstclub.membership.idempotency;

import com.firstclub.platform.idempotency.IdempotencyKeyEntity;
import com.firstclub.platform.idempotency.IdempotencyStatus;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector.ConflictKind;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector.ConflictResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdempotencyConflictDetector}.
 *
 * <p>No Spring context — the detector is stateless and constructed directly.
 */
@DisplayName("IdempotencyConflictDetector")
class IdempotencyConflictDetectorTest {

    private static final String ENDPOINT = "POST:/api/v2/subscriptions";
    private static final String HASH     = "abc123def456";

    private final IdempotencyConflictDetector detector = new IdempotencyConflictDetector();

    private IdempotencyKeyEntity completedRecord(String hash, String endpoint) {
        return IdempotencyKeyEntity.builder()
                .key("merchant1:" + "key1")
                .requestHash(hash)
                .endpointSignature(endpoint)
                .responseBody("{\"id\":1}")
                .statusCode(201)
                .status(IdempotencyStatus.COMPLETED)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
    }

    private IdempotencyKeyEntity processingRecord(String hash, String endpoint) {
        return IdempotencyKeyEntity.builder()
                .key("merchant1:key1")
                .requestHash(hash)
                .endpointSignature(endpoint)
                .status(IdempotencyStatus.PROCESSING)
                .processingStartedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
    }

    // ── No conflict ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("No conflict cases")
    class NoConflict {

        @Test
        @DisplayName("Same hash, same endpoint, COMPLETED → no conflict")
        void sameHashSameEndpointCompleted_returnsEmpty() {
            Optional<ConflictResult> result =
                    detector.detect(HASH, ENDPOINT, completedRecord(HASH, ENDPOINT));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("FAILED_RETRYABLE record is not a conflict (client may retry)")
        void failedRetryable_returnsEmpty() {
            IdempotencyKeyEntity record = IdempotencyKeyEntity.builder()
                    .key("m1:k1")
                    .requestHash(HASH)
                    .endpointSignature(ENDPOINT)
                    .status(IdempotencyStatus.FAILED_RETRYABLE)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();

            Optional<ConflictResult> result = detector.detect(HASH, ENDPOINT, record);
            assertThat(result).isEmpty();
        }
    }

    // ── Endpoint mismatch ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("ENDPOINT_MISMATCH — same key used on different URL")
    class EndpointMismatch {

        @Test
        @DisplayName("Different endpoint → ENDPOINT_MISMATCH")
        void differentEndpoint_returnsEndpointMismatch() {
            Optional<ConflictResult> result =
                    detector.detect(HASH, "POST:/api/v2/refunds", completedRecord(HASH, ENDPOINT));

            assertThat(result).isPresent();
            assertThat(result.get().kind()).isEqualTo(ConflictKind.ENDPOINT_MISMATCH);
        }

        @Test
        @DisplayName("Endpoint mismatch takes precedence over body mismatch")
        void endpointMismatchBeforeBodyMismatch() {
            Optional<ConflictResult> result =
                    detector.detect("different-hash", "POST:/api/v2/other",
                            completedRecord(HASH, ENDPOINT));

            assertThat(result).isPresent();
            assertThat(result.get().kind()).isEqualTo(ConflictKind.ENDPOINT_MISMATCH);
        }

        @Test
        @DisplayName("Null stored endpoint (legacy record) is skipped — no false positive")
        void nullStoredEndpoint_skipsCheck() {
            IdempotencyKeyEntity legacy = IdempotencyKeyEntity.builder()
                    .key("m1:k1")
                    .requestHash(HASH)
                    .endpointSignature(null) // legacy record — no endpoint stored
                    .status(IdempotencyStatus.COMPLETED)
                    .responseBody("{}")
                    .statusCode(200)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();

            Optional<ConflictResult> result = detector.detect(HASH, ENDPOINT, legacy);
            assertThat(result).isEmpty();
        }
    }

    // ── Body mismatch ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BODY_MISMATCH — same endpoint, different body")
    class BodyMismatch {

        @Test
        @DisplayName("Different hash, same endpoint → BODY_MISMATCH")
        void differentHash_sameEndpoint_returnsBodyMismatch() {
            Optional<ConflictResult> result =
                    detector.detect("different-hash", ENDPOINT, completedRecord(HASH, ENDPOINT));

            assertThat(result).isPresent();
            assertThat(result.get().kind()).isEqualTo(ConflictKind.BODY_MISMATCH);
        }
    }

    // ── In-flight ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IN_FLIGHT — request still processing")
    class InFlight {

        @Test
        @DisplayName("PROCESSING status + matching hash and endpoint → IN_FLIGHT")
        void processingRecord_returnsInFlight() {
            Optional<ConflictResult> result =
                    detector.detect(HASH, ENDPOINT, processingRecord(HASH, ENDPOINT));

            assertThat(result).isPresent();
            assertThat(result.get().kind()).isEqualTo(ConflictKind.IN_FLIGHT);
        }

        @Test
        @DisplayName("Null status (legacy) + no responseBody → IN_FLIGHT via legacy inference")
        void nullStatusNoBody_inferredAsProcessing() {
            IdempotencyKeyEntity legacy = IdempotencyKeyEntity.builder()
                    .key("m1:k1")
                    .requestHash(HASH)
                    .endpointSignature(ENDPOINT)
                    .status(null)
                    .responseBody(null)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();

            Optional<ConflictResult> result = detector.detect(HASH, ENDPOINT, legacy);
            assertThat(result).isPresent();
            assertThat(result.get().kind()).isEqualTo(ConflictKind.IN_FLIGHT);
        }

        @Test
        @DisplayName("Message includes context about in-flight state")
        void inFlight_messageIsDescriptive() {
            Optional<ConflictResult> result =
                    detector.detect(HASH, ENDPOINT, processingRecord(HASH, ENDPOINT));

            assertThat(result.get().message()).containsIgnoringCase("process");
        }
    }
}
