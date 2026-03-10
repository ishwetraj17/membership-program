package com.firstclub.dunning.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.DunningDecisionAuditService;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.classification.FailureCodeClassifier;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeResult;
import com.firstclub.dunning.strategy.BackupPaymentMethodSelector;
import com.firstclub.dunning.strategy.DunningStrategyService;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.DunningPolicyService;
import com.firstclub.dunning.service.impl.DunningServiceV2Impl;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.service.PaymentIntentService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DunningServiceV2 Unit Tests")
class DunningServiceV2Test {

    @Mock private DunningAttemptRepository                dunningAttemptRepository;
    @Mock private DunningPolicyRepository                 dunningPolicyRepository;
    @Mock private SubscriptionPaymentPreferenceRepository preferenceRepository;
    @Mock private SubscriptionV2Repository                subscriptionV2Repository;
    @Mock private InvoiceRepository                       invoiceRepository;
    @Mock private InvoiceService                          invoiceService;
    @Mock private PaymentIntentService                    paymentIntentService;
    @Mock private PaymentGatewayPort                      paymentGatewayPort;
    @Mock private DomainEventLog                          domainEventLog;
    @Mock private DunningPolicyService                     dunningPolicyService;
    @Mock private FailureCodeClassifier                    failureCodeClassifier;
    @Mock private DunningStrategyService                   dunningStrategyService;
    @Mock private BackupPaymentMethodSelector              backupSelector;
    @Mock private DunningDecisionAuditService              decisionAuditService;

    @InjectMocks
    private DunningServiceV2Impl dunningServiceV2;

    private static final Long MERCHANT_ID     = 1L;
    private static final Long SUBSCRIPTION_ID = 10L;
    private static final Long INVOICE_ID      = 50L;
    private static final Long POLICY_ID       = 3L;
    private static final Long PRIMARY_PM_ID   = 100L;
    private static final Long BACKUP_PM_ID    = 200L;

    private DunningPolicy suspendPolicy;
    private DunningPolicy cancelPolicy;
    private DunningPolicy fallbackPolicy;
    private SubscriptionV2 pastDueSub;
    private Invoice openInvoice;
    private PaymentIntentDTO freshPi;
    private SubscriptionPaymentPreference prefWithBackup;

    @BeforeEach
    void setUp() {
        suspendPolicy = DunningPolicy.builder()
                .id(POLICY_ID).merchantId(MERCHANT_ID).policyCode("DEFAULT")
                .retryOffsetsJson("[60, 360, 1440, 4320]").maxAttempts(4).graceDays(7)
                .fallbackToBackupPaymentMethod(false)
                .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();

        cancelPolicy = DunningPolicy.builder()
                .id(POLICY_ID).merchantId(MERCHANT_ID).policyCode("STRICT")
                .retryOffsetsJson("[30, 60]").maxAttempts(2).graceDays(3)
                .fallbackToBackupPaymentMethod(false)
                .statusAfterExhaustion(DunningTerminalStatus.CANCELLED).build();

        fallbackPolicy = DunningPolicy.builder()
                .id(POLICY_ID).merchantId(MERCHANT_ID).policyCode("FALLBACK")
                .retryOffsetsJson("[60, 360]").maxAttempts(2).graceDays(7)
                .fallbackToBackupPaymentMethod(true)
                .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();

        pastDueSub = SubscriptionV2.builder()
                .id(SUBSCRIPTION_ID)
                .status(SubscriptionStatusV2.PAST_DUE)
                .version(0L).build();

        openInvoice = Invoice.builder()
                .id(INVOICE_ID).status(InvoiceStatus.OPEN)
                .totalAmount(new BigDecimal("499.00")).currency("INR").build();

        freshPi = PaymentIntentDTO.builder()
                .id(300L).invoiceId(INVOICE_ID)
                .amount(new BigDecimal("499.00")).currency("INR")
                .status(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD)
                .clientSecret("cs_test").build();

        prefWithBackup = SubscriptionPaymentPreference.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .primaryPaymentMethodId(PRIMARY_PM_ID)
                .backupPaymentMethodId(BACKUP_PM_ID).build();
    }

    // ── scheduleAttemptsFromPolicy ────────────────────────────────────────────

    @Nested
    @DisplayName("scheduleAttemptsFromPolicy")
    class ScheduleAttempts {

        @Test
        @DisplayName("creates min(maxAttempts, offsets.size) attempts within grace window")
        void scheduleAttemptsFromPolicy_createsCorrectNumber() {
            when(dunningPolicyService.resolvePolicy(MERCHANT_ID)).thenReturn(suspendPolicy);
            when(dunningPolicyService.parseOffsets("[60, 360, 1440, 4320]"))
                    .thenReturn(List.of(60, 360, 1440, 4320));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            dunningServiceV2.scheduleAttemptsFromPolicy(SUBSCRIPTION_ID, INVOICE_ID, MERCHANT_ID);

            // 4 attempts expected (all offsets < 7 days = 10080 minutes)
            verify(dunningAttemptRepository, times(4)).save(any(DunningAttempt.class));
            verify(domainEventLog).record(eq("DUNNING_V2_SCHEDULED"), anyMap());
        }

        @Test
        @DisplayName("attempts beyond grace window are not created")
        void scheduleAttemptsFromPolicy_respectsGraceDays() {
            // 1-day grace window; offsets include 60 min (ok) + 4320 min (3 d → outside)
            DunningPolicy tightPolicy = DunningPolicy.builder()
                    .id(9L).merchantId(MERCHANT_ID).policyCode("TIGHT")
                    .retryOffsetsJson("[60, 4320]").maxAttempts(2).graceDays(1)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(dunningPolicyService.resolvePolicy(MERCHANT_ID)).thenReturn(tightPolicy);
            when(dunningPolicyService.parseOffsets("[60, 4320]"))
                    .thenReturn(List.of(60, 4320));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            dunningServiceV2.scheduleAttemptsFromPolicy(SUBSCRIPTION_ID, INVOICE_ID, MERCHANT_ID);

            // Only 60-min attempt fits inside 1-day window
            verify(dunningAttemptRepository, times(1)).save(any(DunningAttempt.class));
        }
    }

    // ── processDueV2Attempts ──────────────────────────────────────────────────

    @Nested
    @DisplayName("processDueV2Attempts")
    class ProcessDueAttempts {

        private DunningAttempt dueV2Attempt;

        @BeforeEach
        void setUp() {
            dueV2Attempt = DunningAttempt.builder()
                    .id(500L).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(1)
                    .scheduledAt(LocalDateTime.now().minusMinutes(1))
                    .status(DunningStatus.SCHEDULED)
                    .dunningPolicyId(POLICY_ID)
                    .usedBackupMethod(false)
                    .build();
        }

        @Test
        @DisplayName("no due attempts → no-op")
        void processDueV2Attempts_noDueAttempts_noOp() {
            when(dunningAttemptRepository
                    .findDueForProcessingWithSkipLocked(any(LocalDateTime.class), eq(50)))
                    .thenReturn(Collections.emptyList());

            dunningServiceV2.processDueV2Attempts();

            verifyNoInteractions(subscriptionV2Repository, invoiceRepository,
                    paymentIntentService, paymentGatewayPort);
        }

        @Test
        @DisplayName("payment succeeds → sub ACTIVE, remaining attempts cancelled")
        void processDueV2Attempts_success_activatesSubscription() {
            stubDueAttempts(dueV2Attempt);
            stubPolicy(suspendPolicy);
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            dunningServiceV2.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            assertThat(cap.getAllValues())
                    .anyMatch(a -> a.getId().equals(500L) && a.getStatus() == DunningStatus.SUCCESS);

            ArgumentCaptor<SubscriptionV2> subCap = ArgumentCaptor.forClass(SubscriptionV2.class);
            verify(subscriptionV2Repository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatusV2.ACTIVE);

            verify(invoiceService).onPaymentSucceeded(INVOICE_ID);
            verify(domainEventLog).record(eq("DUNNING_V2_SUCCEEDED"), anyMap());
        }

        @Test
        @DisplayName("primary fails → backup PM attempt queued when policy allows fallback")
        void processDueV2Attempts_primaryFails_backupAttemptQueued() {
            stubDueAttempts(dueV2Attempt);
            stubPolicy(fallbackPolicy);
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.failed("expired_card"));
            when(failureCodeClassifier.classify("expired_card")).thenReturn(FailureCategory.CARD_EXPIRED);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_EXPIRED), any()))
                    .thenReturn(DunningDecision.RETRY_WITH_BACKUP);
            when(backupSelector.findBackup(SUBSCRIPTION_ID)).thenReturn(java.util.Optional.of(BACKUP_PM_ID));

            dunningServiceV2.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(2)).save(cap.capture());

            // One attempt marked FAILED (primary)
            assertThat(cap.getAllValues())
                    .anyMatch(a -> a.getId().equals(500L) && a.getStatus() == DunningStatus.FAILED);
            // One new backup attempt created
            assertThat(cap.getAllValues())
                    .anyMatch(a -> a.isUsedBackupMethod()
                            && a.getStatus() == DunningStatus.SCHEDULED
                            && a.getPaymentMethodId().equals(BACKUP_PM_ID));

            verify(domainEventLog).record(eq("DUNNING_V2_BACKUP_QUEUED"), anyMap());
        }

        @Test
        @DisplayName("all attempts fail with SUSPEND policy → sub set SUSPENDED")
        void processDueV2Attempts_exhausted_suspendApplied() {
            stubDueAttempts(dueV2Attempt);
            stubPolicy(suspendPolicy);
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.failed("card_declined"));
            when(failureCodeClassifier.classify("card_declined")).thenReturn(FailureCategory.CARD_DECLINED_GENERIC);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_DECLINED_GENERIC), any()))
                    .thenReturn(DunningDecision.EXHAUSTED);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));

            dunningServiceV2.processDueV2Attempts();

            ArgumentCaptor<SubscriptionV2> subCap = ArgumentCaptor.forClass(SubscriptionV2.class);
            verify(subscriptionV2Repository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatusV2.SUSPENDED);
            verify(domainEventLog).record(eq("DUNNING_V2_EXHAUSTED"), anyMap());
        }

        @Test
        @DisplayName("all attempts fail with CANCEL policy → sub set CANCELLED and cancelledAt set")
        void processDueV2Attempts_exhausted_cancelApplied() {
            stubDueAttempts(dueV2Attempt);
            stubPolicy(cancelPolicy);
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.failed("card_declined"));
            when(failureCodeClassifier.classify("card_declined")).thenReturn(FailureCategory.CARD_DECLINED_GENERIC);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_DECLINED_GENERIC), any()))
                    .thenReturn(DunningDecision.EXHAUSTED);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(cancelPolicy));

            dunningServiceV2.processDueV2Attempts();

            ArgumentCaptor<SubscriptionV2> subCap = ArgumentCaptor.forClass(SubscriptionV2.class);
            verify(subscriptionV2Repository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatusV2.CANCELLED);
            assertThat(subCap.getValue().getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("subscription is not PAST_DUE → attempt skipped (marked FAILED)")
        void processDueV2Attempts_subscriptionNotPastDue_skipped() {
            SubscriptionV2 activeSub = SubscriptionV2.builder()
                    .id(SUBSCRIPTION_ID).status(SubscriptionStatusV2.ACTIVE).version(0L).build();
            stubDueAttempts(dueV2Attempt);
            stubPolicy(suspendPolicy);
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(activeSub));

            dunningServiceV2.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(DunningStatus.FAILED);
            verifyNoInteractions(invoiceRepository, paymentIntentService, paymentGatewayPort);
        }

        @Test
        @DisplayName("invoice is not OPEN → attempt skipped (marked FAILED)")
        void processDueV2Attempts_invoiceNotOpen_skipped() {
            Invoice paidInvoice = Invoice.builder()
                    .id(INVOICE_ID).status(InvoiceStatus.PAID)
                    .totalAmount(new BigDecimal("499.00")).currency("INR").build();
            stubDueAttempts(dueV2Attempt);
            stubPolicy(suspendPolicy);
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(paidInvoice));
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));

            dunningServiceV2.processDueV2Attempts();

            verifyNoInteractions(paymentIntentService, paymentGatewayPort);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubDueAttempts(DunningAttempt... attempts) {
        when(dunningAttemptRepository
                .findDueForProcessingWithSkipLocked(any(LocalDateTime.class), eq(50)))
                .thenReturn(List.of(attempts));
    }

    private void stubPolicy(DunningPolicy policy) {
        when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(policy));
    }
}
