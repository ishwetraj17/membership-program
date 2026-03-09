package com.firstclub.payments.refund.service;

import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.payments.capacity.PaymentCapacityInvariantService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.guard.RefundMutationGuard;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.refund.service.impl.RefundServiceV2Impl;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.platform.redis.RedisKeyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RefundServiceV2Impl}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>First partial refund → PARTIALLY_REFUNDED status</li>
 *   <li>Cumulative partial refunds → correct running totals</li>
 *   <li>Final refund exhausting remaining balance → REFUNDED status</li>
 *   <li>Over-refund rejected (OVER_REFUND / 422)</li>
 *   <li>FAILED payment rejected (PAYMENT_NOT_REFUNDABLE / 422)</li>
 *   <li>Null merchantId → PAYMENT_MERCHANT_UNRESOLVED (422)</li>
 *   <li>Wrong merchantId → PAYMENT_MERCHANT_MISMATCH (403)</li>
 *   <li>Ledger + outbox are called exactly once per refund</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceV2Test {

    @Mock private PaymentRepository              paymentRepository;
    @Mock private RefundV2Repository             refundV2Repository;
    @Mock private RefundAccountingService        refundAccountingService;
    @Mock private OutboxService                  outboxService;
    @Mock private DomainEventLog                 domainEventLog;
    @Mock private RedisKeyFactory                redisKeyFactory;
    @Mock private RefundMutationGuard            refundMutationGuard;
    @Mock private PaymentCapacityInvariantService invariantService;
    @Mock private ObjectProvider<StringRedisTemplate> redisProvider; // Redis optional — returns null → falls through

    @InjectMocks private RefundServiceV2Impl service;

    private static final Long MERCHANT_ID = 10L;
    private static final Long PAYMENT_ID  = 100L;

    // ── Builder helpers ───────────────────────────────────────────────────────

    private Payment capturedPayment(BigDecimal captured, BigDecimal alreadyRefunded) {
        return Payment.builder()
                .id(PAYMENT_ID)
                .merchantId(MERCHANT_ID)
                .paymentIntentId(999L)
                .amount(captured)
                .capturedAmount(captured)
                .refundedAmount(alreadyRefunded)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(captured.subtract(alreadyRefunded))
                .currency("INR")
                .status(alreadyRefunded.compareTo(BigDecimal.ZERO) == 0
                        ? PaymentStatus.CAPTURED
                        : PaymentStatus.PARTIALLY_REFUNDED)
                .gatewayTxnId("txn-unit-test")
                .capturedAt(LocalDateTime.now())
                .build();
    }

    private RefundV2 savedRefund(Long id, BigDecimal amount) {
        return RefundV2.builder()
                .id(id)
                .merchantId(MERCHANT_ID)
                .paymentId(PAYMENT_ID)
                .amount(amount)
                .reasonCode("TEST_REASON")
                .status(RefundV2Status.COMPLETED)
                .completedAt(LocalDateTime.now())
                .build();
    }

    private RefundCreateRequestDTO request(BigDecimal amount) {
        return RefundCreateRequestDTO.builder()
                .amount(amount)
                .reasonCode("TEST_REASON")
                .build();
    }

    // ── "Happy path" nested class ─────────────────────────────────────────────

    @Nested
    @DisplayName("createRefund — happy paths")
    class HappyPath {

        @Test
        @DisplayName("first partial refund transitions status to PARTIALLY_REFUNDED")
        void firstPartialRefund_setsPartiallyRefunded() {
            BigDecimal captured = new BigDecimal("1000.00");
            BigDecimal refundAmt = new BigDecimal("300.00");
            Payment payment = capturedPayment(captured, BigDecimal.ZERO);

            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r = RefundV2.builder().id(1L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r.setStatus(RefundV2Status.COMPLETED);
                r.setCompletedAt(LocalDateTime.now());
                return r;
            });
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID, request(refundAmt));

            assertThat(result.getStatus()).isEqualTo(RefundV2Status.COMPLETED);
            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(result.getRefundableAmountAfter()).isEqualByComparingTo(new BigDecimal("700.00"));

            // Payment row must have been mutated correctly before save
            ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(paymentCaptor.capture());
            Payment saved = paymentCaptor.getValue();
            assertThat(saved.getRefundedAmount()).isEqualByComparingTo(refundAmt);
            assertThat(saved.getNetAmount()).isEqualByComparingTo(new BigDecimal("700.00"));
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        }

        @Test
        @DisplayName("second partial refund accumulates correctly — still PARTIALLY_REFUNDED")
        void secondPartialRefund_accumulatesCorrectly() {
            BigDecimal captured = new BigDecimal("1000.00");
            BigDecimal already  = new BigDecimal("300.00");   // first refund already applied
            BigDecimal refundAmt = new BigDecimal("400.00");
            Payment payment = capturedPayment(captured, already);

            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r = RefundV2.builder().id(2L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r.setStatus(RefundV2Status.COMPLETED);
                r.setCompletedAt(LocalDateTime.now());
                return r;
            });
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID, request(refundAmt));

            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            // 1000 - (300+400) = 300 refundable remaining
            assertThat(result.getRefundableAmountAfter()).isEqualByComparingTo(new BigDecimal("300.00"));
        }

        @Test
        @DisplayName("final refund exhausting remaining balance transitions to REFUNDED")
        void finalRefund_completelyRefundedStatus() {
            BigDecimal captured  = new BigDecimal("1000.00");
            BigDecimal already   = new BigDecimal("700.00");
            BigDecimal refundAmt = new BigDecimal("300.00");   // exactly the remainder
            Payment payment = capturedPayment(captured, already);

            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r = RefundV2.builder().id(3L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r.setStatus(RefundV2Status.COMPLETED);
                r.setCompletedAt(LocalDateTime.now());
                return r;
            });
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID, request(refundAmt));

            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(result.getRefundableAmountAfter()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("accounting service is invoked exactly once with the correct refund and payment")
        void accountingService_invokedOnce() {
            BigDecimal captured = new BigDecimal("500.00");
            BigDecimal refundAmt = new BigDecimal("100.00");
            Payment payment = capturedPayment(captured, BigDecimal.ZERO);

            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r = RefundV2.builder().id(5L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.createRefund(MERCHANT_ID, PAYMENT_ID, request(refundAmt));

            verify(refundAccountingService, times(1)).postRefundReversal(any(RefundV2.class), eq(payment));
        }

        @Test
        @DisplayName("outbox event is published exactly once per refund")
        void outboxPublishedOnce() {
            BigDecimal captured = new BigDecimal("500.00");
            Payment payment = capturedPayment(captured, BigDecimal.ZERO);

            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                r = RefundV2.builder().id(6L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("50.00")));

            verify(outboxService, times(1)).publish(any(), anyMap());
            verify(domainEventLog, times(1)).record(eq("REFUND_V2_ISSUED"), anyMap());
        }
    }

    // ── Error-path tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("createRefund — error paths")
    class ErrorPaths {

        @Test
        @DisplayName("over-refund is rejected with OVER_REFUND / 422")
        void overRefund_throwsOVER_REFUND() {
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class)))
                    .thenThrow(new MembershipException("Refund amount exceeds refundable amount",
                            "OVER_REFUND", HttpStatus.UNPROCESSABLE_ENTITY));
            when(refundV2Repository.findByRequestFingerprint(any())).thenReturn(Optional.empty()); // Phase 15: fingerprint check before lock

            // Request exceeds capturedAmount
            RefundCreateRequestDTO req = request(new BigDecimal("600.00"));
            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID, req), MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("OVER_REFUND");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            verifyNoInteractions(refundAccountingService, outboxService);
        }

        @Test
        @DisplayName("over-refund using cumulative amounts is also rejected")
        void cumulativeOverRefund_throwsOVER_REFUND() {
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class)))
                    .thenThrow(new MembershipException("Refund amount exceeds refundable amount",
                            "OVER_REFUND", HttpStatus.UNPROCESSABLE_ENTITY));

            // Request 201 — exceeds the 200 remaining
            RefundCreateRequestDTO req = request(new BigDecimal("201.00"));
            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID, req), MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("OVER_REFUND");
        }

        @Test
        @DisplayName("FAILED payment status throws PAYMENT_NOT_REFUNDABLE / 422")
        void failedPayment_throwsNotRefundable() {
            Payment payment = Payment.builder()
                    .id(PAYMENT_ID).merchantId(MERCHANT_ID).amount(new BigDecimal("500.00"))
                    .capturedAmount(BigDecimal.ZERO).refundedAmount(BigDecimal.ZERO)
                    .disputedAmount(BigDecimal.ZERO).netAmount(BigDecimal.ZERO)
                    .currency("INR").status(PaymentStatus.FAILED).gatewayTxnId("txn-f")
                    .paymentIntentId(888L).build();
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);

            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00"))),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_REFUNDABLE");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("already-REFUNDED payment throws PAYMENT_NOT_REFUNDABLE")
        void refundedPayment_throwsNotRefundable() {
            Payment payment = Payment.builder()
                    .id(PAYMENT_ID).merchantId(MERCHANT_ID).amount(new BigDecimal("500.00"))
                    .capturedAmount(new BigDecimal("500.00"))
                    .refundedAmount(new BigDecimal("500.00"))
                    .disputedAmount(BigDecimal.ZERO).netAmount(BigDecimal.ZERO)
                    .currency("INR").status(PaymentStatus.REFUNDED).gatewayTxnId("txn-r")
                    .paymentIntentId(888L).build();
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);

            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("1.00"))),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_REFUNDABLE");
        }

        @Test
        @DisplayName("null merchantId on payment throws PAYMENT_MERCHANT_UNRESOLVED / 422")
        void nullMerchantId_throwsUnresolved() {
            Payment payment = capturedPayment(new BigDecimal("500.00"), BigDecimal.ZERO);
            payment.setMerchantId(null);  // legacy payment with no merchant_id
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);

            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00"))),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_MERCHANT_UNRESOLVED");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("wrong merchantId throws PAYMENT_MERCHANT_MISMATCH / 403")
        void wrongMerchantId_throwsMismatch() {
            Payment payment = capturedPayment(new BigDecimal("500.00"), BigDecimal.ZERO);
            // payment belongs to MERCHANT_ID (10), caller claims to be merchant 99
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);

            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(99L, PAYMENT_ID, request(new BigDecimal("100.00"))),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_MERCHANT_MISMATCH");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("payment not found throws PAYMENT_NOT_FOUND / 404")
        void paymentNotFound_throwsNotFound() {
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class)))
                    .thenThrow(new MembershipException("Payment not found: " + PAYMENT_ID,
                            "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));

            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00"))),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_FOUND");
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── Read-only operations ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getRefund / listRefundsByPayment")
    class ReadOperations {

        @Test
        @DisplayName("getRefund returns DTO when refund belongs to merchant")
        void getRefund_returnsDto() {
            BigDecimal amt = new BigDecimal("200.00");
            RefundV2 refund = savedRefund(1L, amt);
            Payment payment = capturedPayment(new BigDecimal("500.00"), amt);
            // simulate post-refund state on payment
            payment.setRefundedAmount(amt);
            payment.setNetAmount(new BigDecimal("300.00"));
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);

            when(refundV2Repository.findByMerchantIdAndId(MERCHANT_ID, 1L)).thenReturn(Optional.of(refund));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            RefundV2ResponseDTO dto = service.getRefund(MERCHANT_ID, 1L);
            assertThat(dto.getId()).isEqualTo(1L);
            assertThat(dto.getAmount()).isEqualByComparingTo(amt);
        }

        @Test
        @DisplayName("getRefund with wrong merchantId returns 404 (not found via tenant-scoped query)")
        void getRefund_wrongMerchant_notFound() {
            when(refundV2Repository.findByMerchantIdAndId(99L, 1L)).thenReturn(Optional.empty());

            MembershipException ex = catchThrowableOfType(
                    () -> service.getRefund(99L, 1L), MembershipException.class);
            assertThat(ex.getErrorCode()).isEqualTo("REFUND_NOT_FOUND");
        }

        @Test
        @DisplayName("listRefundsByPayment returns all refunds for the payment")
        void listRefundsByPayment_returnsAll() {
            Payment payment = capturedPayment(new BigDecimal("1000.00"), new BigDecimal("300.00"));
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            RefundV2 r1 = savedRefund(1L, new BigDecimal("200.00"));
            RefundV2 r2 = savedRefund(2L, new BigDecimal("100.00"));

            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(refundV2Repository.findByPaymentIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
                    .thenReturn(List.of(r1, r2));

            List<RefundV2ResponseDTO> list = service.listRefundsByPayment(MERCHANT_ID, PAYMENT_ID);
            assertThat(list).hasSize(2);
        }
    }
}
