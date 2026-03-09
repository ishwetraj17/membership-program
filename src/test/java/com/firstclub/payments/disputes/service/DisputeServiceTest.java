package com.firstclub.payments.disputes.service;

import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.capacity.DisputeCapacityService;
import com.firstclub.payments.capacity.PaymentCapacityInvariantService;
import com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.disputes.service.impl.DisputeServiceImpl;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DisputeService — Unit Tests")
class DisputeServiceTest {

    private static final Long MERCHANT_ID   = 1L;
    private static final Long OTHER_MERCHANT = 99L;
    private static final Long PAYMENT_ID    = 42L;
    private static final Long DISPUTE_ID    = 10L;
    private static final Long CUSTOMER_ID   = 5L;

    @Mock private PaymentRepository        paymentRepository;
    @Mock private DisputeRepository        disputeRepository;
    @Mock private DisputeAccountingService disputeAccountingService;
    @Mock private DomainEventLog           domainEventLog;
    @Mock private DisputeCapacityService   disputeCapacityService;
    @Mock private PaymentCapacityInvariantService invariantService;

    @InjectMocks
    private DisputeServiceImpl disputeService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Payment capturedPayment() {
        return Payment.builder()
                .id(PAYMENT_ID)
                .merchantId(MERCHANT_ID)
                .status(PaymentStatus.CAPTURED)
                .capturedAmount(new BigDecimal("1000.00"))
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("1000.00"))
                .currency("INR")
                .build();
    }

    private Dispute savedDispute(DisputeStatus status) {
        return Dispute.builder()
                .id(DISPUTE_ID)
                .merchantId(MERCHANT_ID)
                .paymentId(PAYMENT_ID)
                .customerId(CUSTOMER_ID)
                .amount(new BigDecimal("500.00"))
                .reasonCode("FRAUDULENT_CHARGE")
                .status(status)
                .build();
    }

    private DisputeCreateRequestDTO openRequest() {
        return DisputeCreateRequestDTO.builder()
                .customerId(CUSTOMER_ID)
                .amount(new BigDecimal("500.00"))
                .reasonCode("FRAUDULENT_CHARGE")
                .build();
    }

    // ── OpenDispute ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("openDispute")
    class OpenDisputeTests {

        @BeforeEach
        void setUpHappyPath() {
            lenient().when(paymentRepository.findByIdForUpdate(PAYMENT_ID))
                    .thenReturn(Optional.of(capturedPayment()));
            lenient().when(disputeRepository.existsByPaymentIdAndStatusIn(eq(PAYMENT_ID), anyList()))
                    .thenReturn(false);
            lenient().when(disputeRepository.save(any(Dispute.class)))
                    .thenAnswer(inv -> {
                        Dispute d = inv.getArgument(0);
                        return Dispute.builder()
                                .id(DISPUTE_ID)
                                .merchantId(d.getMerchantId())
                                .paymentId(d.getPaymentId())
                                .customerId(d.getCustomerId())
                                .amount(d.getAmount())
                                .reasonCode(d.getReasonCode())
                                .status(d.getStatus())
                                .dueBy(d.getDueBy())
                                .build();
                    });
            lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("success — payment moves to DISPUTED, response DTO populated")
        void openDispute_success() {
            DisputeResponseDTO result = disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest());

            assertThat(result.getId()).isEqualTo(DISPUTE_ID);
            assertThat(result.getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(result.getPaymentId()).isEqualTo(PAYMENT_ID);
            assertThat(result.getStatus()).isEqualTo(DisputeStatus.OPEN);
            assertThat(result.getAmount()).isEqualByComparingTo("500.00");
            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.DISPUTED);
        }

        @Test
        @DisplayName("success — payment.disputedAmount and status updated")
        void openDispute_updatesPaymentDisputedAmount() {
            disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest());

            verify(paymentRepository).save(argThat(p ->
                    p.getDisputedAmount().compareTo(new BigDecimal("500.00")) == 0
                    && p.getStatus() == PaymentStatus.DISPUTED
                    && p.getNetAmount().compareTo(new BigDecimal("500.00")) == 0));
        }

        @Test
        @DisplayName("success — accounting service called exactly once")
        void openDispute_callsAccountingOnce() {
            disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest());
            verify(disputeAccountingService, times(1)).postDisputeOpen(any(Dispute.class), any(Payment.class));
        }

        @Test
        @DisplayName("success — domain event logged")
        void openDispute_logsDomainEvent() {
            disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest());
            verify(domainEventLog, times(1)).record(eq("DISPUTE_OPENED"), anyMap());
        }

        @Test
        @DisplayName("duplicate active dispute → 409 ACTIVE_DISPUTE_EXISTS")
        void openDispute_duplicateActive_rejected() {
            when(disputeRepository.existsByPaymentIdAndStatusIn(eq(PAYMENT_ID), anyList()))
                    .thenReturn(true);

            assertThatThrownBy(() -> disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("ACTIVE_DISPUTE_EXISTS");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("amount > disputable → 422 DISPUTE_AMOUNT_EXCEEDS_LIMIT")
        void openDispute_amountExceedsLimit_rejected() {
            DisputeCreateRequestDTO bigRequest = DisputeCreateRequestDTO.builder()
                    .customerId(CUSTOMER_ID)
                    .amount(new BigDecimal("2000.00")) // exceeds capturedAmount=1000
                    .reasonCode("FRAUD")
                    .build();            doThrow(new MembershipException("Dispute amount exceeds disputable capacity",
                    "DISPUTE_AMOUNT_EXCEEDS_LIMIT", HttpStatus.UNPROCESSABLE_ENTITY))
                    .when(disputeCapacityService).checkDisputeCapacity(any(Payment.class), any(BigDecimal.class));
            assertThatThrownBy(() -> disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, bigRequest))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("DISPUTE_AMOUNT_EXCEEDS_LIMIT");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        @DisplayName("REFUNDED payment → 422 PAYMENT_NOT_DISPUTABLE")
        void openDispute_refundedPayment_rejected() {
            Payment refunded = capturedPayment();
            refunded.setStatus(PaymentStatus.REFUNDED);
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(refunded));

            assertThatThrownBy(() -> disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> assertThat(((MembershipException) ex).getErrorCode())
                            .isEqualTo("PAYMENT_NOT_DISPUTABLE"));
        }

        @Test
        @DisplayName("DISPUTED payment → 422 PAYMENT_NOT_DISPUTABLE")
        void openDispute_alreadyDisputedPayment_rejected() {
            Payment disputed = capturedPayment();
            disputed.setStatus(PaymentStatus.DISPUTED);
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(disputed));

            assertThatThrownBy(() -> disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> assertThat(((MembershipException) ex).getErrorCode())
                            .isEqualTo("PAYMENT_NOT_DISPUTABLE"));
        }

        @Test
        @DisplayName("null merchantId on payment → 422 PAYMENT_MERCHANT_UNRESOLVED")
        void openDispute_noMerchant_rejected() {
            Payment noMerchant = capturedPayment();
            noMerchant.setMerchantId(null);
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(noMerchant));

            assertThatThrownBy(() -> disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("PAYMENT_MERCHANT_UNRESOLVED");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        @DisplayName("wrong merchantId → 403 PAYMENT_MERCHANT_MISMATCH")
        void openDispute_wrongMerchant_rejected() {
            assertThatThrownBy(() ->
                    disputeService.openDispute(OTHER_MERCHANT, PAYMENT_ID, openRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("PAYMENT_MERCHANT_MISMATCH");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("payment not found → 404 PAYMENT_NOT_FOUND")
        void openDispute_paymentNotFound() {
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, openRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("PAYMENT_NOT_FOUND");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }

    // ── moveToUnderReview ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("moveToUnderReview")
    class TransitionTests {

        @BeforeEach
        void setUp() {
            lenient().when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(capturedPayment()));
        }

        @Test
        @DisplayName("OPEN → UNDER_REVIEW success")
        void moveToUnderReview_success() {
            Dispute open = savedDispute(DisputeStatus.OPEN);
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(open));
            when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

            DisputeResponseDTO result = disputeService.moveToUnderReview(MERCHANT_ID, DISPUTE_ID);
            assertThat(result.getStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);
        }

        @Test
        @DisplayName("UNDER_REVIEW status → 422 INVALID_DISPUTE_TRANSITION")
        void moveToUnderReview_alreadyUnderReview_rejected() {
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(savedDispute(DisputeStatus.UNDER_REVIEW)));

            assertThatThrownBy(() -> disputeService.moveToUnderReview(MERCHANT_ID, DISPUTE_ID))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("INVALID_DISPUTE_TRANSITION");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        @DisplayName("LOST status → 422 INVALID_DISPUTE_TRANSITION")
        void moveToUnderReview_terminalState_rejected() {
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(savedDispute(DisputeStatus.LOST)));

            assertThatThrownBy(() -> disputeService.moveToUnderReview(MERCHANT_ID, DISPUTE_ID))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> assertThat(((MembershipException) ex).getErrorCode())
                            .isEqualTo("INVALID_DISPUTE_TRANSITION"));
        }
    }

    // ── resolveDispute ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveDispute")
    class ResolveTests {

        private Payment disputedPayment;

        @BeforeEach
        void setUp() {
            disputedPayment = Payment.builder()
                    .id(PAYMENT_ID)
                    .merchantId(MERCHANT_ID)
                    .status(PaymentStatus.DISPUTED)
                    .capturedAmount(new BigDecimal("1000.00"))
                    .refundedAmount(BigDecimal.ZERO)
                    .disputedAmount(new BigDecimal("500.00"))
                    .netAmount(new BigDecimal("500.00"))
                    .currency("INR")
                    .build();

            lenient().when(paymentRepository.findByIdForUpdate(PAYMENT_ID))
                    .thenReturn(Optional.of(disputedPayment));
            lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("WON — payment restored to CAPTURED, reserve released")
        void resolveDispute_won_success() {
            Dispute underReview = savedDispute(DisputeStatus.UNDER_REVIEW);
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(underReview));

            DisputeResponseDTO result = disputeService.resolveDispute(
                    MERCHANT_ID, DISPUTE_ID, new DisputeResolveRequestDTO("WON", null));

            assertThat(result.getStatus()).isEqualTo(DisputeStatus.WON);
            assertThat(result.getResolvedAt()).isNotNull();
            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.CAPTURED);
            verify(disputeAccountingService).postDisputeWon(any(), any());
            verify(disputeAccountingService, never()).postDisputeLost(any(), any());
        }

        @Test
        @DisplayName("WON with prior refund — payment restored to PARTIALLY_REFUNDED")
        void resolveDispute_won_withPriorRefund_partiallyRefunded() {
            disputedPayment.setRefundedAmount(new BigDecimal("200.00"));
            Dispute open = savedDispute(DisputeStatus.OPEN);
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(open));

            DisputeResponseDTO result = disputeService.resolveDispute(
                    MERCHANT_ID, DISPUTE_ID, new DisputeResolveRequestDTO("WON", null));

            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        }

        @Test
        @DisplayName("LOST — chargeback posted, capturedAmount reduced, status stays DISPUTED")
        void resolveDispute_lost_success() {
            Dispute open = savedDispute(DisputeStatus.OPEN);
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(open));

            DisputeResponseDTO result = disputeService.resolveDispute(
                    MERCHANT_ID, DISPUTE_ID, new DisputeResolveRequestDTO("LOST", "Customer confirmed fraud"));

            assertThat(result.getStatus()).isEqualTo(DisputeStatus.LOST);
            assertThat(result.getPaymentStatusAfter()).isEqualTo(PaymentStatus.DISPUTED);
            verify(disputeAccountingService).postDisputeLost(any(), any());
            verify(disputeAccountingService, never()).postDisputeWon(any(), any());
            // capturedAmount should be reduced by dispute amount
            verify(paymentRepository).save(argThat(p ->
                    p.getCapturedAmount().compareTo(new BigDecimal("500.00")) == 0));
        }

        @Test
        @DisplayName("outcome=OPEN (invalid) → 422 INVALID_DISPUTE_OUTCOME")
        void resolveDispute_invalidOutcomeOpen_rejected() {
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(savedDispute(DisputeStatus.OPEN)));

            assertThatThrownBy(() -> disputeService.resolveDispute(
                    MERCHANT_ID, DISPUTE_ID, new DisputeResolveRequestDTO("OPEN", null)))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("INVALID_DISPUTE_OUTCOME");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        @DisplayName("garbage outcome → 422 INVALID_DISPUTE_OUTCOME")
        void resolveDispute_garbageOutcome_rejected() {
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(savedDispute(DisputeStatus.OPEN)));

            assertThatThrownBy(() -> disputeService.resolveDispute(
                    MERCHANT_ID, DISPUTE_ID, new DisputeResolveRequestDTO("BANANA", null)))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> assertThat(((MembershipException) ex).getErrorCode())
                            .isEqualTo("INVALID_DISPUTE_OUTCOME"));
        }

        @Test
        @DisplayName("already WON → 422 DISPUTE_ALREADY_RESOLVED")
        void resolveDispute_alreadyResolved_rejected() {
            when(disputeRepository.findByIdForUpdate(DISPUTE_ID))
                    .thenReturn(Optional.of(savedDispute(DisputeStatus.WON)));

            assertThatThrownBy(() -> disputeService.resolveDispute(
                    MERCHANT_ID, DISPUTE_ID, new DisputeResolveRequestDTO("LOST", null)))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("DISPUTE_ALREADY_RESOLVED");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }
    }

    // ── Read operations ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Read operations")
    class ReadTests {

        @Test
        @DisplayName("getDisputeById — returns DTO for correct merchant")
        void getDisputeById_success() {
            when(disputeRepository.findByMerchantIdAndId(MERCHANT_ID, DISPUTE_ID))
                    .thenReturn(Optional.of(savedDispute(DisputeStatus.OPEN)));
            when(paymentRepository.findById(PAYMENT_ID))
                    .thenReturn(Optional.of(capturedPayment()));

            DisputeResponseDTO result = disputeService.getDisputeById(MERCHANT_ID, DISPUTE_ID);
            assertThat(result.getId()).isEqualTo(DISPUTE_ID);
            assertThat(result.getStatus()).isEqualTo(DisputeStatus.OPEN);
        }

        @Test
        @DisplayName("getDisputeById — wrong merchant → 404 DISPUTE_NOT_FOUND")
        void getDisputeById_wrongMerchant_notFound() {
            when(disputeRepository.findByMerchantIdAndId(OTHER_MERCHANT, DISPUTE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> disputeService.getDisputeById(OTHER_MERCHANT, DISPUTE_ID))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("DISPUTE_NOT_FOUND");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("listDisputesByPayment — returns merchant-scoped disputes")
        void listDisputesByPayment_returnsList() {
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(capturedPayment()));
            when(disputeRepository.findByMerchantIdAndPaymentId(MERCHANT_ID, PAYMENT_ID))
                    .thenReturn(List.of(savedDispute(DisputeStatus.OPEN)));

            List<DisputeResponseDTO> result = disputeService.listDisputesByPayment(MERCHANT_ID, PAYMENT_ID);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPaymentId()).isEqualTo(PAYMENT_ID);
        }

        @Test
        @DisplayName("listDisputes with status filter — delegates to repo")
        void listDisputes_withStatusFilter() {
            when(disputeRepository.findByMerchantIdAndStatus(MERCHANT_ID, DisputeStatus.OPEN))
                    .thenReturn(List.of(savedDispute(DisputeStatus.OPEN)));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(capturedPayment()));

            List<DisputeResponseDTO> result = disputeService.listDisputes(MERCHANT_ID, DisputeStatus.OPEN);
            assertThat(result).hasSize(1);
        }
    }
}
