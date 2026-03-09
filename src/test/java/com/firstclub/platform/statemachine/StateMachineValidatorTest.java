package com.firstclub.platform.statemachine;

import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.model.PaymentIntentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StateMachineValidator Unit Tests")
class StateMachineValidatorTest {

    private StateMachineValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StateMachineValidator();
    }

    // =========================================================================
    // SUBSCRIPTION
    // =========================================================================

    @Nested
    @DisplayName("SUBSCRIPTION transitions")
    class SubscriptionTransitions {

        @Test
        @DisplayName("PENDING → ACTIVE is allowed")
        void pendingToActiveAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PENDING → CANCELLED is allowed")
        void pendingToCancelledAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.PENDING, SubscriptionStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ACTIVE → PAST_DUE is allowed")
        void activeToPastDueAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ACTIVE → CANCELLED is allowed")
        void activeToCancelledAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ACTIVE → SUSPENDED is allowed")
        void activeToSuspendedAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.ACTIVE, SubscriptionStatus.SUSPENDED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ACTIVE → EXPIRED is allowed")
        void activeToExpiredAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.ACTIVE, SubscriptionStatus.EXPIRED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PAST_DUE → ACTIVE is allowed")
        void pastDueToActiveAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.PAST_DUE, SubscriptionStatus.ACTIVE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PAST_DUE → SUSPENDED is allowed")
        void pastDueToSuspendedAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.PAST_DUE, SubscriptionStatus.SUSPENDED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SUSPENDED → ACTIVE is allowed")
        void suspendedToActiveAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.SUSPENDED, SubscriptionStatus.ACTIVE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("EXPIRED → ACTIVE (renewal) is allowed")
        void expiredToActiveAllowed() {
            assertThatCode(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.EXPIRED, SubscriptionStatus.ACTIVE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("CANCELLED is a terminal state — no transitions allowed")
        void cancelledIsTerminal() {
            for (SubscriptionStatus target : SubscriptionStatus.values()) {
                assertThatThrownBy(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.CANCELLED, target))
                        .isInstanceOf(MembershipException.class)
                        .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                                .isEqualTo("INVALID_STATUS_TRANSITION"));
            }
        }

        @Test
        @DisplayName("ACTIVE → ACTIVE is not allowed (already active)")
        void activeToActiveDenied() {
            assertThatThrownBy(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("ACTIVE -> ACTIVE");
        }

        @Test
        @DisplayName("ACTIVE → PENDING is not allowed")
        void activeToPendingDenied() {
            assertThatThrownBy(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.ACTIVE, SubscriptionStatus.PENDING))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                            .isEqualTo("INVALID_STATUS_TRANSITION"));
        }

        @Test
        @DisplayName("EXPIRED → CANCELLED is not allowed (must be renewed first)")
        void expiredToCancelledDenied() {
            assertThatThrownBy(() -> validator.validate("SUBSCRIPTION", SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("EXPIRED -> CANCELLED");
        }
    }

    // =========================================================================
    // INVOICE
    // =========================================================================

    @Nested
    @DisplayName("INVOICE transitions")
    class InvoiceTransitions {

        @Test
        @DisplayName("DRAFT → OPEN is allowed")
        void draftToOpenAllowed() {
            assertThatCode(() -> validator.validate("INVOICE", InvoiceStatus.DRAFT, InvoiceStatus.OPEN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DRAFT → VOID is allowed")
        void draftToVoidAllowed() {
            assertThatCode(() -> validator.validate("INVOICE", InvoiceStatus.DRAFT, InvoiceStatus.VOID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPEN → PAID is allowed")
        void openToPaidAllowed() {
            assertThatCode(() -> validator.validate("INVOICE", InvoiceStatus.OPEN, InvoiceStatus.PAID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPEN → VOID is allowed")
        void openToVoidAllowed() {
            assertThatCode(() -> validator.validate("INVOICE", InvoiceStatus.OPEN, InvoiceStatus.VOID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OPEN → UNCOLLECTIBLE is allowed")
        void openToUncollectibleAllowed() {
            assertThatCode(() -> validator.validate("INVOICE", InvoiceStatus.OPEN, InvoiceStatus.UNCOLLECTIBLE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PAID is a terminal state — no transitions allowed")
        void paidIsTerminal() {
            for (InvoiceStatus target : InvoiceStatus.values()) {
                assertThatThrownBy(() -> validator.validate("INVOICE", InvoiceStatus.PAID, target))
                        .isInstanceOf(MembershipException.class)
                        .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                                .isEqualTo("INVALID_STATUS_TRANSITION"));
            }
        }

        @Test
        @DisplayName("DRAFT → PAID is not allowed (must go through OPEN)")
        void draftToPaidDenied() {
            assertThatThrownBy(() -> validator.validate("INVOICE", InvoiceStatus.DRAFT, InvoiceStatus.PAID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("DRAFT -> PAID");
        }

        @Test
        @DisplayName("VOID is a terminal state — no transitions allowed")
        void voidIsTerminal() {
            for (InvoiceStatus target : InvoiceStatus.values()) {
                assertThatThrownBy(() -> validator.validate("INVOICE", InvoiceStatus.VOID, target))
                        .isInstanceOf(MembershipException.class)
                        .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                                .isEqualTo("INVALID_STATUS_TRANSITION"));
            }
        }
    }

    // =========================================================================
    // PAYMENT_INTENT
    // =========================================================================

    @Nested
    @DisplayName("PAYMENT_INTENT transitions")
    class PaymentIntentTransitions {

        @Test
        @DisplayName("REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION is allowed")
        void requiresPaymentMethodToRequiresConfirmationAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
                    PaymentIntentStatus.REQUIRES_CONFIRMATION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("REQUIRES_PAYMENT_METHOD → FAILED is allowed")
        void requiresPaymentMethodToFailedAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
                    PaymentIntentStatus.FAILED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("REQUIRES_CONFIRMATION → PROCESSING is allowed")
        void requiresConfirmationToProcessingAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.REQUIRES_CONFIRMATION,
                    PaymentIntentStatus.PROCESSING))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("REQUIRES_ACTION → PROCESSING is allowed")
        void requiresActionToProcessingAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.REQUIRES_ACTION,
                    PaymentIntentStatus.PROCESSING))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PROCESSING → SUCCEEDED is allowed")
        void processingToSucceededAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.PROCESSING,
                    PaymentIntentStatus.SUCCEEDED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PROCESSING → FAILED is allowed")
        void processingToFailedAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.PROCESSING,
                    PaymentIntentStatus.FAILED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FAILED → REQUIRES_PAYMENT_METHOD is allowed (retry path)")
        void failedToRequiresPaymentMethodAllowed() {
            assertThatCode(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.FAILED,
                    PaymentIntentStatus.REQUIRES_PAYMENT_METHOD))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SUCCEEDED is a terminal state — no transitions allowed")
        void succeededIsTerminal() {
            for (PaymentIntentStatus target : PaymentIntentStatus.values()) {
                assertThatThrownBy(() -> validator.validate("PAYMENT_INTENT", PaymentIntentStatus.SUCCEEDED, target))
                        .isInstanceOf(MembershipException.class)
                        .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                                .isEqualTo("INVALID_STATUS_TRANSITION"));
            }
        }

        @Test
        @DisplayName("REQUIRES_PAYMENT_METHOD → SUCCEEDED directly is not allowed")
        void requiresPaymentMethodToSucceededDenied() {
            assertThatThrownBy(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
                    PaymentIntentStatus.SUCCEEDED))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("REQUIRES_PAYMENT_METHOD -> SUCCEEDED");
        }

        @Test
        @DisplayName("PROCESSING → REQUIRES_PAYMENT_METHOD is not allowed")
        void processingToRequiresPaymentMethodDenied() {
            assertThatThrownBy(() -> validator.validate("PAYMENT_INTENT",
                    PaymentIntentStatus.PROCESSING,
                    PaymentIntentStatus.REQUIRES_PAYMENT_METHOD))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                            .isEqualTo("INVALID_STATUS_TRANSITION"));
        }
    }

    // =========================================================================
    // Unknown entity
    // =========================================================================

    @Nested
    @DisplayName("Unknown entity")
    class UnknownEntity {

        @Test
        @DisplayName("Unknown entity key throws with INVALID_STATE_MACHINE_ENTITY")
        void unknownEntityThrows() {
            assertThatThrownBy(() -> validator.validate("UNKNOWN_ENTITY", SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(e -> assertThat(((MembershipException) e).getErrorCode())
                            .isEqualTo("INVALID_STATE_MACHINE_ENTITY"));
        }
    }
}
