package com.firstclub.payments;

import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.payments.capacity.DisputeCapacityService;
import com.firstclub.payments.capacity.PaymentCapacityInvariantService;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.disputes.service.DisputeAccountingService;
import com.firstclub.payments.disputes.service.DisputeDueDateCheckerService;
import com.firstclub.payments.disputes.service.impl.DisputeDueDateCheckerServiceImpl;
import com.firstclub.payments.disputes.service.impl.DisputeServiceImpl;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.refund.service.RefundAccountingService;
import com.firstclub.payments.refund.service.impl.RefundServiceV2Impl;
import com.firstclub.payments.refund.guard.RefundMutationGuard;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.checks.payments.DisputeReservePostedIntegrityChecker;
import com.firstclub.platform.integrity.checks.payments.DisputeResolutionPostedIntegrityChecker;
import com.firstclub.platform.integrity.checks.payments.RefundCumulativeIntegrityChecker;
import com.firstclub.platform.redis.RedisKeyFactory;
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
 * Phase 15 — Refund &amp; Dispute Robustness Hardening test suite.
 *
 * <p>Covers:
 * <ul>
 *   <li>Refund fingerprint generation and idempotent replay</li>
 *   <li>Duplicate fingerprint blocked; different payload gets new refund</li>
 *   <li>Dispute {@code reservePosted} flag set after {@code openDispute}</li>
 *   <li>Dispute {@code resolutionPosted} flag set after {@code resolveDispute}</li>
 *   <li>Double resolution accounting blocked with {@code DISPUTE_RESOLUTION_ALREADY_POSTED}</li>
 *   <li>{@code DisputeDueDateCheckerService} — due-soon window filtering</li>
 *   <li>Integrity checkers — violation detection for cumulative refund mismatch,
 *       missing reserve post, missing resolution post</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RefundDisputeHardeningTest {

    // ── Shared constants ──────────────────────────────────────────────────────

    private static final Long MERCHANT_ID = 10L;
    private static final Long PAYMENT_ID  = 100L;
    private static final Long DISPUTE_ID  = 200L;

    // ── Shared builder helpers ─────────────────────────────────────────────────

    private static Payment capturedPayment() {
        return Payment.builder()
                .id(PAYMENT_ID)
                .merchantId(MERCHANT_ID)
                .paymentIntentId(999L)
                .amount(new BigDecimal("1000.00"))
                .capturedAmount(new BigDecimal("1000.00"))
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("1000.00"))
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("txn-phase15")
                .capturedAt(LocalDateTime.now())
                .build();
    }

    private static RefundCreateRequestDTO refundRequest(BigDecimal amount) {
        return RefundCreateRequestDTO.builder()
                .amount(amount)
                .reasonCode("TEST_REASON")
                .build();
    }

    private static RefundCreateRequestDTO refundRequestWithFingerprint(BigDecimal amount, String fp) {
        return RefundCreateRequestDTO.builder()
                .amount(amount)
                .reasonCode("TEST_REASON")
                .requestFingerprint(fp)
                .build();
    }

    private static Dispute openDispute(boolean reservePosted) {
        return Dispute.builder()
                .id(DISPUTE_ID)
                .merchantId(MERCHANT_ID)
                .paymentId(PAYMENT_ID)
                .customerId(55L)
                .amount(new BigDecimal("200.00"))
                .reasonCode("FRAUDULENT_CHARGE")
                .status(DisputeStatus.OPEN)
                .reservePosted(reservePosted)
                .resolutionPosted(false)
                .openedAt(LocalDateTime.now())
                .dueBy(LocalDateTime.now().plusDays(10))
                .build();
    }

    private static RefundV2 completedRefund(Long id, BigDecimal amount, String fingerprint) {
        return RefundV2.builder()
                .id(id)
                .merchantId(MERCHANT_ID)
                .paymentId(PAYMENT_ID)
                .amount(amount)
                .reasonCode("TEST_REASON")
                .status(RefundV2Status.COMPLETED)
                .requestFingerprint(fingerprint)
                .completedAt(LocalDateTime.now())
                .build();
    }

    // =========================================================================
    // 1. Refund Fingerprint Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Refund fingerprint idempotency (Phase 15)")
    class RefundFingerprintTests {

        @Mock private PaymentRepository       paymentRepository;
        @Mock private RefundV2Repository      refundV2Repository;
        @Mock private RefundAccountingService refundAccountingService;
        @Mock private OutboxService           outboxService;
        @Mock private DomainEventLog          domainEventLog;
        @Mock private RedisKeyFactory         redisKeyFactory;
        @Mock private ObjectProvider<StringRedisTemplate> redisProvider;
        @Mock private RefundMutationGuard              refundMutationGuard;
        @Mock private PaymentCapacityInvariantService  invariantService;

        @InjectMocks private RefundServiceV2Impl service;

        @Test
        @DisplayName("fingerprint is stored on the created refund row")
        void fingerprintStoredOnCreation() {
            Payment payment = capturedPayment();
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                if (r.getId() == null) r = RefundV2.builder().id(1L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).requestFingerprint(r.getRequestFingerprint())
                        .status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundV2ResponseDTO result = service.createRefund(
                    MERCHANT_ID, PAYMENT_ID, refundRequest(new BigDecimal("300.00")));

            // Verify fingerprint is captured in the persisted refund
            ArgumentCaptor<RefundV2> refundCaptor = ArgumentCaptor.forClass(RefundV2.class);
            verify(refundV2Repository, atLeastOnce()).save(refundCaptor.capture());
            RefundV2 firstSave = refundCaptor.getAllValues().get(0);
            assertThat(firstSave.getRequestFingerprint())
                    .isNotNull()
                    .hasSize(64); // SHA-256 hex = 64 chars
        }

        @Test
        @DisplayName("duplicate fingerprint replay returns the existing refund — no new row created")
        void duplicateFingerprintReturnsExisting() {
            String existingFingerprint = "abc123existingfingerprint0000000000000000000000000000000000000000";
            RefundV2 existingRefund = completedRefund(42L, new BigDecimal("300.00"), existingFingerprint);
            Payment payment = capturedPayment();
            payment.setRefundedAmount(new BigDecimal("300.00")); // already applied

            when(refundV2Repository.findByRequestFingerprint(existingFingerprint))
                    .thenReturn(Optional.of(existingRefund));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            RefundV2ResponseDTO result = service.createRefund(
                    MERCHANT_ID, PAYMENT_ID,
                    refundRequestWithFingerprint(new BigDecimal("300.00"), existingFingerprint));

            // Existing refund returned — no new DB row, no accounting posted
            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getRequestFingerprint()).isEqualTo(existingFingerprint);
            verify(refundV2Repository, never()).save(argThat(r -> r.getId() == null));
            verify(refundAccountingService, never()).postRefundReversal(any(), any());
        }

        @Test
        @DisplayName("different amount generates a different fingerprint — treated as new refund")
        void differentAmountProducesDifferentFingerprint() {
            Payment payment = capturedPayment();
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                if (r.getId() == null) r = RefundV2.builder().id(2L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).requestFingerprint(r.getRequestFingerprint())
                        .status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // First call — capture fingerprint
            service.createRefund(MERCHANT_ID, PAYMENT_ID, refundRequest(new BigDecimal("300.00")));
            ArgumentCaptor<RefundV2> cap1 = ArgumentCaptor.forClass(RefundV2.class);
            verify(refundV2Repository, atLeastOnce()).save(cap1.capture());
            String fp1 = cap1.getAllValues().get(0).getRequestFingerprint();

            reset(refundV2Repository, paymentRepository, refundMutationGuard);
            Payment payment2 = capturedPayment();
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment2);
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                if (r.getId() == null) r = RefundV2.builder().id(3L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).requestFingerprint(r.getRequestFingerprint())
                        .status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Second call with different amount
            service.createRefund(MERCHANT_ID, PAYMENT_ID, refundRequest(new BigDecimal("400.00")));
            ArgumentCaptor<RefundV2> cap2 = ArgumentCaptor.forClass(RefundV2.class);
            verify(refundV2Repository, atLeastOnce()).save(cap2.capture());
            String fp2 = cap2.getAllValues().get(0).getRequestFingerprint();

            assertThat(fp1).isNotEqualTo(fp2);
        }

        @Test
        @DisplayName("caller-provided fingerprint is stored as-is without recomputing")
        void callerProvidedFingerprintIsStoredAsIs() {
            String callerFp = "caller-provided-fingerprint-abc123";
            Payment payment = capturedPayment();
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any(BigDecimal.class))).thenReturn(payment);
            when(refundV2Repository.findByRequestFingerprint(callerFp)).thenReturn(Optional.empty());
            when(refundV2Repository.save(any())).thenAnswer(inv -> {
                RefundV2 r = inv.getArgument(0);
                if (r.getId() == null) r = RefundV2.builder().id(5L).merchantId(r.getMerchantId())
                        .paymentId(r.getPaymentId()).amount(r.getAmount())
                        .reasonCode(r.getReasonCode()).requestFingerprint(r.getRequestFingerprint())
                        .status(RefundV2Status.PENDING).build();
                return r;
            }).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    refundRequestWithFingerprint(new BigDecimal("100.00"), callerFp));

            ArgumentCaptor<RefundV2> cap = ArgumentCaptor.forClass(RefundV2.class);
            verify(refundV2Repository, atLeastOnce()).save(cap.capture());
            assertThat(cap.getAllValues().get(0).getRequestFingerprint()).isEqualTo(callerFp);
        }
    }

    // =========================================================================
    // 2. Dispute Reserve & Resolution Posted Guards
    // =========================================================================

    @Nested
    @DisplayName("Dispute reserve and resolution one-time posting guards (Phase 15)")
    class DisputePostedFlagTests {

        @Mock private PaymentRepository        paymentRepository;
        @Mock private DisputeRepository        disputeRepository;
        @Mock private DisputeAccountingService disputeAccountingService;
        @Mock private DomainEventLog           domainEventLog;
        @Mock private DisputeCapacityService         disputeCapacityService;
        @Mock private PaymentCapacityInvariantService invariantService;

        @InjectMocks private DisputeServiceImpl disputeService;

        private Dispute activeDispute(boolean reservePosted, boolean resolutionPosted) {
            Dispute d = openDispute(reservePosted);
            d.setResolutionPosted(resolutionPosted);
            return d;
        }

        @Test
        @DisplayName("openDispute sets reservePosted=true after accounting call")
        void openDispute_setsReservePosted() {
            Payment payment = capturedPayment();
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(disputeRepository.existsByPaymentIdAndStatusIn(any(), any())).thenReturn(false);
            when(disputeRepository.save(any())).thenAnswer(inv -> {
                Dispute d = inv.getArgument(0);
                if (d.getId() == null) d.setId(DISPUTE_ID);
                return d;
            });
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO.builder()
                    .customerId(55L).amount(new BigDecimal("200.00"))
                    .reasonCode("FRAUDULENT_CHARGE").dueBy(LocalDateTime.now().plusDays(10)).build();

            disputeService.openDispute(MERCHANT_ID, PAYMENT_ID, request);

            // The second save call (after setting reservePosted=true) must have reservePosted=true
            ArgumentCaptor<Dispute> captor = ArgumentCaptor.forClass(Dispute.class);
            verify(disputeRepository, atLeast(2)).save(captor.capture());
            Dispute lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSave.isReservePosted()).isTrue();
        }

        @Test
        @DisplayName("resolveDispute WON sets resolutionPosted=true after accounting call")
        void resolveDispute_won_setsResolutionPosted() {
            Dispute dispute = activeDispute(true, false);
            Payment payment = capturedPayment();
            payment.setDisputedAmount(new BigDecimal("200.00"));
            payment.setStatus(PaymentStatus.DISPUTED);

            when(disputeRepository.findByIdForUpdate(DISPUTE_ID)).thenReturn(Optional.of(dispute));
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var resolveReq = com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO.builder()
                    .outcome("WON").build();

            disputeService.resolveDispute(MERCHANT_ID, DISPUTE_ID, resolveReq);

            ArgumentCaptor<Dispute> captor = ArgumentCaptor.forClass(Dispute.class);
            verify(disputeRepository, atLeast(2)).save(captor.capture());
            Dispute lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(lastSave.isResolutionPosted()).isTrue();
        }

        @Test
        @DisplayName("resolveDispute LOST sets resolutionPosted=true after accounting call")
        void resolveDispute_lost_setsResolutionPosted() {
            Dispute dispute = activeDispute(true, false);
            Payment payment = capturedPayment();
            payment.setDisputedAmount(new BigDecimal("200.00"));
            payment.setStatus(PaymentStatus.DISPUTED);

            when(disputeRepository.findByIdForUpdate(DISPUTE_ID)).thenReturn(Optional.of(dispute));
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var resolveReq = com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO.builder()
                    .outcome("LOST").build();

            disputeService.resolveDispute(MERCHANT_ID, DISPUTE_ID, resolveReq);

            ArgumentCaptor<Dispute> captor = ArgumentCaptor.forClass(Dispute.class);
            verify(disputeRepository, atLeast(2)).save(captor.capture());
            assertThat(captor.getAllValues().stream().anyMatch(Dispute::isResolutionPosted)).isTrue();
        }

        @Test
        @DisplayName("resolveDispute throws DISPUTE_RESOLUTION_ALREADY_POSTED when resolutionPosted=true")
        void resolveDispute_whenAlreadyPosted_throwsConflict() {
            Dispute dispute = activeDispute(true, true); // resolutionPosted already true — still OPEN status

            when(disputeRepository.findByIdForUpdate(DISPUTE_ID)).thenReturn(Optional.of(dispute));
            when(paymentRepository.findByIdForUpdate(PAYMENT_ID))
                    .thenReturn(Optional.of(capturedPayment()));

            var resolveReq = com.firstclub.payments.disputes.dto.DisputeResolveRequestDTO.builder()
                    .outcome("WON").build();

            assertThatThrownBy(() -> disputeService.resolveDispute(MERCHANT_ID, DISPUTE_ID, resolveReq))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("DISPUTE_RESOLUTION_ALREADY_POSTED");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });

            verify(disputeAccountingService, never()).postDisputeWon(any(), any());
            verify(disputeAccountingService, never()).postDisputeLost(any(), any());
        }
    }

    // =========================================================================
    // 3. Due-Soon Checker
    // =========================================================================

    @Nested
    @DisplayName("DisputeDueDateCheckerService — evidence deadline filtering (Phase 15)")
    class DueSoonCheckerTests {

        @Mock private DisputeRepository disputeRepository;
        @Mock private PaymentRepository paymentRepository;

        @InjectMocks private DisputeDueDateCheckerServiceImpl dueDateService;

        private Dispute disputeWithDueBy(Long id, LocalDateTime dueBy, DisputeStatus status) {
            return Dispute.builder()
                    .id(id)
                    .merchantId(MERCHANT_ID)
                    .paymentId(PAYMENT_ID)
                    .customerId(55L)
                    .amount(new BigDecimal("150.00"))
                    .reasonCode("PRODUCT_NOT_RECEIVED")
                    .status(status)
                    .reservePosted(true)
                    .resolutionPosted(false)
                    .openedAt(LocalDateTime.now().minusDays(3))
                    .dueBy(dueBy)
                    .build();
        }

        @Test
        @DisplayName("returns disputes whose dueBy is within the look-ahead window")
        void returnsDisputesWithinWindow() {
            Dispute d1 = disputeWithDueBy(1L, LocalDateTime.now().plusDays(3), DisputeStatus.OPEN);
            Dispute d2 = disputeWithDueBy(2L, LocalDateTime.now().plusDays(5), DisputeStatus.UNDER_REVIEW);

            when(disputeRepository.findByStatusInAndDueByBefore(any(), any()))
                    .thenReturn(List.of(d1, d2));
            when(paymentRepository.findById(PAYMENT_ID))
                    .thenReturn(Optional.of(capturedPayment()));

            List<DisputeResponseDTO> result = dueDateService.findDueSoon(7);

            assertThat(result).hasSize(2);
            // Verify sorted ascending by dueBy
            assertThat(result.get(0).getId()).isEqualTo(1L);
            assertThat(result.get(1).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("returns empty list when no disputes are due within window")
        void returnsEmptyWhenNoneDueSoon() {
            when(disputeRepository.findByStatusInAndDueByBefore(any(), any()))
                    .thenReturn(List.of());

            List<DisputeResponseDTO> result = dueDateService.findDueSoon(7);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("response DTOs include reservePosted flag")
        void responseDtoIncludesReservePostedFlag() {
            Dispute d = disputeWithDueBy(3L, LocalDateTime.now().plusDays(2), DisputeStatus.OPEN);
            d.setReservePosted(true);

            when(disputeRepository.findByStatusInAndDueByBefore(any(), any()))
                    .thenReturn(List.of(d));
            when(paymentRepository.findById(PAYMENT_ID))
                    .thenReturn(Optional.of(capturedPayment()));

            List<DisputeResponseDTO> result = dueDateService.findDueSoon(7);

            assertThat(result.get(0).isReservePosted()).isTrue();
            assertThat(result.get(0).isResolutionPosted()).isFalse();
        }
    }

    // =========================================================================
    // 4. Integrity Checkers
    // =========================================================================

    @Nested
    @DisplayName("Phase 15 integrity checkers")
    class IntegrityCheckerTests {

        @Mock private PaymentRepository  paymentRepository;
        @Mock private RefundV2Repository refundV2Repository;
        @Mock private DisputeRepository  disputeRepository;

        @InjectMocks private RefundCumulativeIntegrityChecker refundCumulativeChecker;
        @InjectMocks private DisputeReservePostedIntegrityChecker reservePostedChecker;
        @InjectMocks private DisputeResolutionPostedIntegrityChecker resolutionPostedChecker;

        private Payment paymentWithRefundedAmount(Long id, BigDecimal refundedAmount) {
            return Payment.builder()
                    .id(id)
                    .merchantId(MERCHANT_ID)
                    .capturedAmount(new BigDecimal("1000.00"))
                    .refundedAmount(refundedAmount)
                    .disputedAmount(BigDecimal.ZERO)
                    .netAmount(new BigDecimal("1000.00").subtract(refundedAmount))
                    .currency("INR")
                    .status(PaymentStatus.PARTIALLY_REFUNDED)
                    .gatewayTxnId("txn-check")
                    .capturedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("RefundCumulativeIntegrityChecker passes when amounts match")
        void refundCumulative_passesWhenMatch() {
            Payment p = paymentWithRefundedAmount(1L, new BigDecimal("300.00"));
            when(paymentRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(p));
            when(refundV2Repository.sumAmountByPaymentIdAndStatus(1L, RefundV2Status.COMPLETED))
                    .thenReturn(new BigDecimal("300.00"));

            IntegrityCheckResult result = refundCumulativeChecker.run(MERCHANT_ID);

            assertThat(result.isPassed()).isTrue();
            assertThat(result.getViolationCount()).isZero();
        }

        @Test
        @DisplayName("RefundCumulativeIntegrityChecker detects mismatch between refundedAmount and completed sum")
        void refundCumulative_detectsMismatch() {
            Payment p = paymentWithRefundedAmount(1L, new BigDecimal("300.00"));
            when(paymentRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(p));
            when(refundV2Repository.sumAmountByPaymentIdAndStatus(1L, RefundV2Status.COMPLETED))
                    .thenReturn(new BigDecimal("500.00")); // mismatch!

            IntegrityCheckResult result = refundCumulativeChecker.run(MERCHANT_ID);

            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
            assertThat(result.getViolations().get(0).getEntityId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("DisputeReservePostedIntegrityChecker detects active dispute with reservePosted=false")
        void reservePosted_detectsMissingReserve() {
            Dispute d = openDispute(false); // reservePosted=false
            when(disputeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(d));

            IntegrityCheckResult result = reservePostedChecker.run(MERCHANT_ID);

            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
            assertThat(result.getViolations().get(0).getEntityId()).isEqualTo(DISPUTE_ID);
        }

        @Test
        @DisplayName("DisputeReservePostedIntegrityChecker passes when all active disputes have reservePosted=true")
        void reservePosted_passesWhenAllPosted() {
            Dispute d = openDispute(true); // reservePosted=true
            when(disputeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(d));

            IntegrityCheckResult result = reservePostedChecker.run(MERCHANT_ID);

            assertThat(result.isPassed()).isTrue();
        }

        @Test
        @DisplayName("DisputeResolutionPostedIntegrityChecker detects resolved dispute with resolutionPosted=false")
        void resolutionPosted_detectsMissingEntry() {
            Dispute d = openDispute(true);
            d.setStatus(DisputeStatus.WON);
            d.setResolutionPosted(false); // should be true after resolution
            when(disputeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(d));

            IntegrityCheckResult result = resolutionPostedChecker.run(MERCHANT_ID);

            assertThat(result.isPassed()).isFalse();
            assertThat(result.getViolationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("DisputeResolutionPostedIntegrityChecker passes when all resolved disputes have resolutionPosted=true")
        void resolutionPosted_passesWhenAllPosted() {
            Dispute d = openDispute(true);
            d.setStatus(DisputeStatus.LOST);
            d.setResolutionPosted(true);
            when(disputeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(d));

            IntegrityCheckResult result = resolutionPostedChecker.run(MERCHANT_ID);

            assertThat(result.isPassed()).isTrue();
        }
    }
}
