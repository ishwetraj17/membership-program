package com.firstclub.membership.idempotency;

import com.firstclub.platform.idempotency.IdempotencyKeyEntity;
import com.firstclub.platform.idempotency.IdempotencyKeyRepository;
import com.firstclub.platform.idempotency.IdempotencyStatus;
import com.firstclub.platform.idempotency.service.IdempotencyRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdempotencyRecordService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyRecordService")
class IdempotencyRecordServiceTest {

    @Mock private IdempotencyKeyRepository repository;
    @InjectMocks private IdempotencyRecordService service;

    // ── createPlaceholder ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("createPlaceholder")
    class CreatePlaceholder {

        @Test
        @DisplayName("Saves entity with PROCESSING status and processing_started_at")
        void savesProcessingEntityWithTimestamp() {
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            IdempotencyKeyEntity result = service.createPlaceholder(
                    "merchant1", "key1", "hash1", "POST:/api/v2/subs",
                    "req-id", "corr-id", 24);

            assertThat(result.getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
            assertThat(result.getIdempotencyKey()).isEqualTo("key1");
            assertThat(result.getMerchantId()).isEqualTo("merchant1");
            assertThat(result.getKey()).isEqualTo("merchant1:key1");
            assertThat(result.getProcessingStartedAt()).isNotNull();
            assertThat(result.getRequestId()).isEqualTo("req-id");
            assertThat(result.getCorrelationId()).isEqualTo("corr-id");
            assertThat(result.getExpiresAt()).isAfter(LocalDateTime.now());
        }
    }

    // ── markCompleted ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("Sets COMPLETED status, response body, and completedAt")
        void setsCompletedFields() {
            IdempotencyKeyEntity entity = processingEntity();
            when(repository.findByMerchantAndKey("m1", "k1")).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markCompleted("m1", "k1", 201, "{\"id\":1}", "m1", "application/json");

            ArgumentCaptor<IdempotencyKeyEntity> captor =
                    ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
            verify(repository).save(captor.capture());
            IdempotencyKeyEntity saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(saved.getStatusCode()).isEqualTo(201);
            assertThat(saved.getResponseBody()).isEqualTo("{\"id\":1}");
            assertThat(saved.getCompletedAt()).isNotNull();
        }
    }

    // ── markFailed ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markFailed")
    class MarkFailed {

        @Test
        @DisplayName("retryable=true sets FAILED_RETRYABLE")
        void retryable_setsFailedRetryable() {
            IdempotencyKeyEntity entity = processingEntity();
            when(repository.findByMerchantAndKey("m1", "k1")).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markFailed("m1", "k1", true);

            ArgumentCaptor<IdempotencyKeyEntity> captor =
                    ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyStatus.FAILED_RETRYABLE);
        }

        @Test
        @DisplayName("retryable=false sets FAILED_FINAL")
        void notRetryable_setsFailedFinal() {
            IdempotencyKeyEntity entity = processingEntity();
            when(repository.findByMerchantAndKey("m1", "k1")).thenReturn(Optional.of(entity));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markFailed("m1", "k1", false);

            ArgumentCaptor<IdempotencyKeyEntity> captor =
                    ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyStatus.FAILED_FINAL);
        }
    }

    // ── resetStuckProcessing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("resetStuckProcessing")
    class ResetStuck {

        @Test
        @DisplayName("Delegates to repository with PROCESSING→FAILED_RETRYABLE and computed cutoff")
        void delegatesWithCorrectParameters() {
            when(repository.resetStuckProcessing(any(), any(), any(), any())).thenReturn(3);

            int count = service.resetStuckProcessing(Duration.ofMinutes(5));

            assertThat(count).isEqualTo(3);
            verify(repository).resetStuckProcessing(
                    eq(IdempotencyStatus.PROCESSING),
                    any(LocalDateTime.class),    // cutoff = now - 5 min
                    eq(IdempotencyStatus.FAILED_RETRYABLE),
                    any(LocalDateTime.class));  // now
        }

        @Test
        @DisplayName("Returns 0 when no stuck records found")
        void returnsZeroWhenNoneFound() {
            when(repository.resetStuckProcessing(any(), any(), any(), any())).thenReturn(0);

            int count = service.resetStuckProcessing(Duration.ofMinutes(10));
            assertThat(count).isZero();
        }
    }

    // ── findByMerchantAndKey ──────────────────────────────────────────────────

    @Nested
    @DisplayName("findByMerchantAndKey")
    class FindByMerchantAndKey {

        @Test
        @DisplayName("Uses new (merchantId, idempotencyKey) index first")
        void triesNewIndexFirst() {
            IdempotencyKeyEntity entity = processingEntity();
            when(repository.findByMerchantIdAndIdempotencyKey("m1", "k1"))
                    .thenReturn(Optional.of(entity));

            Optional<IdempotencyKeyEntity> result = service.findByMerchantAndKey("m1", "k1");

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Falls back to composite PK when new index returns empty")
        void fallsBackToCompositePk() {
            when(repository.findByMerchantIdAndIdempotencyKey("m1", "k1"))
                    .thenReturn(Optional.empty());
            IdempotencyKeyEntity entity = processingEntity();
            when(repository.findByMerchantAndKey("m1", "k1")).thenReturn(Optional.of(entity));

            Optional<IdempotencyKeyEntity> result = service.findByMerchantAndKey("m1", "k1");

            assertThat(result).isPresent();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static IdempotencyKeyEntity processingEntity() {
        return IdempotencyKeyEntity.builder()
                .key("m1:k1")
                .idempotencyKey("k1")
                .merchantId("m1")
                .requestHash("hash1")
                .endpointSignature("POST:/api/v2/subs")
                .status(IdempotencyStatus.PROCESSING)
                .processingStartedAt(LocalDateTime.now().minusMinutes(2))
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
    }
}
