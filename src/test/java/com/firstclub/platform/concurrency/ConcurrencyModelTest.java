package com.firstclub.platform.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Phase 9 concurrency model classes:
 * {@link ConcurrencyConflictException}, {@link ConcurrencyGuard},
 * and {@link BusinessLockScope}.
 *
 * All tests are pure unit tests — no Spring context required.
 */
@DisplayName("Concurrency Model Unit Tests")
class ConcurrencyModelTest {

    // =========================================================================
    // ConcurrencyConflictException
    // =========================================================================

    @Nested
    @DisplayName("ConcurrencyConflictException")
    class ConcurrencyConflictExceptionTests {

        @Test
        @DisplayName("optimisticLock factory — fields populated correctly")
        void optimisticLock_fieldsPopulated() {
            ConcurrencyConflictException ex =
                    ConcurrencyConflictException.optimisticLock("SubscriptionV2", 42L);

            assertThat(ex.getEntityType()).isEqualTo("SubscriptionV2");
            assertThat(ex.getEntityId()).isEqualTo("42");
            assertThat(ex.getReason()).isEqualTo(ConcurrencyConflictException.ConflictReason.OPTIMISTIC_LOCK);
            assertThat(ex.getErrorCode()).isEqualTo("CONCURRENCY_CONFLICT_OPTIMISTIC_LOCK");
        }

        @Test
        @DisplayName("optimisticLock factory — message contains entity context")
        void optimisticLock_messageContainsContext() {
            ConcurrencyConflictException ex =
                    ConcurrencyConflictException.optimisticLock("PaymentIntentV2", 99L);

            assertThat(ex.getMessage())
                    .contains("OPTIMISTIC_LOCK")
                    .contains("PaymentIntentV2")
                    .contains("99");
        }

        @Test
        @DisplayName("terminalState factory — reason is TERMINAL_STATE and message contains state")
        void terminalState_fieldsPopulated() {
            ConcurrencyConflictException ex =
                    ConcurrencyConflictException.terminalState("Subscription", 7L, "CANCELLED");

            assertThat(ex.getReason())
                    .isEqualTo(ConcurrencyConflictException.ConflictReason.TERMINAL_STATE);
            assertThat(ex.getMessage()).contains("CANCELLED");
            assertThat(ex.getErrorCode()).isEqualTo("CONCURRENCY_CONFLICT_TERMINAL_STATE");
        }

        @Test
        @DisplayName("duplicateCreate factory — reason is DUPLICATE_CREATE")
        void duplicateCreate_fieldsPopulated() {
            ConcurrencyConflictException ex =
                    ConcurrencyConflictException.duplicateCreate("Invoice", "INV-2024-001");

            assertThat(ex.getReason())
                    .isEqualTo(ConcurrencyConflictException.ConflictReason.DUPLICATE_CREATE);
            assertThat(ex.getEntityId()).isEqualTo("INV-2024-001");
            assertThat(ex.getMessage()).contains("INV-2024-001");
        }

        @Test
        @DisplayName("httpStatus — returns 409 CONFLICT")
        void httpStatus_isConflict() {
            ConcurrencyConflictException ex =
                    ConcurrencyConflictException.optimisticLock("Any", 1L);

            assertThat(ex.httpStatus().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("ConflictReason enum — all expected values present")
        void conflictReason_allValuesPresent() {
            ConcurrencyConflictException.ConflictReason[] reasons =
                    ConcurrencyConflictException.ConflictReason.values();

            assertThat(reasons).extracting(Enum::name).contains(
                    "OPTIMISTIC_LOCK",
                    "TERMINAL_STATE",
                    "DUPLICATE_CREATE",
                    "SEQUENCE_RACE",
                    "PROCESSING_LOCKED"
            );
        }
    }

    // =========================================================================
    // ConcurrencyGuard
    // =========================================================================

    @Nested
    @DisplayName("ConcurrencyGuard")
    class ConcurrencyGuardTests {

        @Test
        @DisplayName("withOptimisticLock — passes through return value on success")
        void withOptimisticLock_successPassthrough() {
            String result = ConcurrencyGuard.withOptimisticLock("Entity", 1L, () -> "ok");
            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("withOptimisticLock — translates ObjectOptimisticLockingFailureException to ConcurrencyConflictException")
        void withOptimisticLock_translatesOccException() {
            ObjectOptimisticLockingFailureException occ =
                    new ObjectOptimisticLockingFailureException("com.firstclub.Entity", 5L);

            assertThatThrownBy(() ->
                    ConcurrencyGuard.withOptimisticLock("Entity", 5L, () -> { throw occ; })
            )
                    .isInstanceOf(ConcurrencyConflictException.class)
                    .satisfies(e -> {
                        ConcurrencyConflictException cce = (ConcurrencyConflictException) e;
                        assertThat(cce.getReason())
                                .isEqualTo(ConcurrencyConflictException.ConflictReason.OPTIMISTIC_LOCK);
                        assertThat(cce.getEntityType()).isEqualTo("Entity");
                        assertThat(cce.getEntityId()).isEqualTo("5");
                    });
        }

        @Test
        @DisplayName("withOptimisticLock — does NOT swallow non-OCC exceptions")
        void withOptimisticLock_otherExceptionsPropagate() {
            RuntimeException other = new IllegalStateException("some other error");

            assertThatThrownBy(() ->
                    ConcurrencyGuard.withOptimisticLock("Entity", 1L, () -> { throw other; })
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("some other error");
        }

        @Test
        @DisplayName("withUniqueConstraintGuard — passes through return value on success")
        void withUniqueConstraintGuard_successPassthrough() {
            Integer result = ConcurrencyGuard.withUniqueConstraintGuard("Invoice", "INV-001", () -> 42);
            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("withUniqueConstraintGuard — translates DataIntegrityViolationException to DUPLICATE_CREATE")
        void withUniqueConstraintGuard_translatesDataIntegrityException() {
            DataIntegrityViolationException dive =
                    new DataIntegrityViolationException("Unique constraint violation",
                            new RuntimeException("uq_invoice_number"));

            assertThatThrownBy(() ->
                    ConcurrencyGuard.withUniqueConstraintGuard("Invoice", "INV-001", () -> { throw dive; })
            )
                    .isInstanceOf(ConcurrencyConflictException.class)
                    .satisfies(e -> {
                        ConcurrencyConflictException cce = (ConcurrencyConflictException) e;
                        assertThat(cce.getReason())
                                .isEqualTo(ConcurrencyConflictException.ConflictReason.DUPLICATE_CREATE);
                        assertThat(cce.getEntityType()).isEqualTo("Invoice");
                    });
        }

        @Test
        @DisplayName("withUniqueConstraintGuard — translates OCC to OPTIMISTIC_LOCK")
        void withUniqueConstraintGuard_translatesOcc() {
            ObjectOptimisticLockingFailureException occ =
                    new ObjectOptimisticLockingFailureException("Invoice", "INV-001");

            assertThatThrownBy(() ->
                    ConcurrencyGuard.withUniqueConstraintGuard("Invoice", "INV-001", () -> { throw occ; })
            )
                    .isInstanceOf(ConcurrencyConflictException.class)
                    .satisfies(e -> {
                        ConcurrencyConflictException cce = (ConcurrencyConflictException) e;
                        assertThat(cce.getReason())
                                .isEqualTo(ConcurrencyConflictException.ConflictReason.OPTIMISTIC_LOCK);
                    });
        }
    }

    // =========================================================================
    // BusinessLockScope
    // =========================================================================

    @Nested
    @DisplayName("BusinessLockScope")
    class BusinessLockScopeTests {

        @Test
        @DisplayName("enum — all expected scopes are defined")
        void allExpectedScopesDefined() {
            assertThat(BusinessLockScope.values()).extracting(Enum::name).contains(
                    "SUBSCRIPTION_STATE_TRANSITION",
                    "SUBSCRIPTION_DUPLICATE_CREATE",
                    "PAYMENT_INTENT_CONFIRM",
                    "PAYMENT_ATTEMPT_NUMBERING",
                    "REFUND_CEILING_CHECK",
                    "DUNNING_ATTEMPT_PROCESSING",
                    "REVENUE_RECOGNITION_SINGLE_POST",
                    "RECON_REPORT_UPSERT",
                    "WEBHOOK_DELIVERY_PROCESSING",
                    "OUTBOX_EVENT_PROCESSING",
                    "INVOICE_SEQUENCE"
            );
        }
    }
}
