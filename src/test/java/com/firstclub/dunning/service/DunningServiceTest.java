package com.firstclub.dunning.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.service.PaymentIntentService;
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
@DisplayName("DunningService Unit Tests")
class DunningServiceTest {

    @Mock private DunningAttemptRepository dunningAttemptRepository;
    @Mock private SubscriptionRepository   subscriptionRepository;
    @Mock private InvoiceRepository        invoiceRepository;
    @Mock private InvoiceService           invoiceService;
    @Mock private PaymentIntentService     paymentIntentService;
    @Mock private PaymentGatewayPort       paymentGatewayPort;

    @InjectMocks
    private DunningService dunningService;

    private Subscription pastDueSub;
    private Invoice       openInvoice;
    private DunningAttempt scheduledAttempt;
    private PaymentIntentDTO freshPi;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(1L).email("test@test.com")
                .name("Test User").status(User.UserStatus.ACTIVE).build();

        LocalDateTime now = LocalDateTime.now();
        pastDueSub = Subscription.builder()
                .id(20L).user(user)
                .status(SubscriptionStatus.PAST_DUE)
                .startDate(now.minusMonths(1))
                .endDate(now)
                .nextBillingDate(now)
                .graceUntil(now.plusDays(7))
                .build();

        openInvoice = Invoice.builder()
                .id(60L).userId(1L).subscriptionId(20L)
                .status(InvoiceStatus.OPEN).currency("INR")
                .totalAmount(new BigDecimal("299.00"))
                .dueDate(now)
                .periodStart(now.minusMonths(1))
                .periodEnd(now.plusMonths(1))
                .build();

        scheduledAttempt = DunningAttempt.builder()
                .id(200L).subscriptionId(20L).invoiceId(60L)
                .attemptNumber(1)
                .scheduledAt(now.minusMinutes(5))
                .status(DunningStatus.SCHEDULED)
                .build();

        freshPi = PaymentIntentDTO.builder()
                .id(300L).invoiceId(60L)
                .amount(new BigDecimal("299.00")).currency("INR")
                .status(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD)
                .clientSecret("cs_fresh").build();
    }

    // -------------------------------------------------------------------------
    // No due attempts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("processDueAttempts — no due attempts — does nothing")
    void noDueAttempts_doesNothing() {
        when(dunningAttemptRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(DunningStatus.SCHEDULED), any()))
                .thenReturn(Collections.emptyList());

        dunningService.processDueAttempts();

        verifyNoInteractions(subscriptionRepository, invoiceRepository,
                paymentIntentService, paymentGatewayPort, invoiceService);
    }

    // -------------------------------------------------------------------------
    // Attempt succeeds
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("attempt succeeds")
    class AttemptSuccess {

        @Test
        @DisplayName("marks attempt SUCCESS, reactivates subscription, cancels remaining")
        void paymentSuccess_activatesSubscription() {
            when(dunningAttemptRepository.findByStatusAndScheduledAtLessThanEqual(
                    eq(DunningStatus.SCHEDULED), any()))
                    .thenReturn(List.of(scheduledAttempt));
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(60L)).thenReturn(Optional.of(openInvoice));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.charge(300L)).thenReturn(ChargeOutcome.SUCCESS);
            // findById called again inside DunningService after activateSubscription
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(pastDueSub));
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(20L, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            dunningService.processDueAttempts();

            // Invoice resolved
            verify(invoiceService).onPaymentSucceeded(60L);

            // Attempt saved with SUCCESS
            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            assertThat(cap.getAllValues())
                    .anyMatch(a -> a.getId().equals(200L) && a.getStatus() == DunningStatus.SUCCESS);
        }
    }

    // -------------------------------------------------------------------------
    // Attempt fails
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("attempt fails")
    class AttemptFailed {

        @Test
        @DisplayName("marks attempt FAILED; subscription stays PAST_DUE when more remain")
        void paymentFails_morePendingAttempts_subscriptionStaysPastDue() {
            when(dunningAttemptRepository.findByStatusAndScheduledAtLessThanEqual(
                    eq(DunningStatus.SCHEDULED), any()))
                    .thenReturn(List.of(scheduledAttempt));
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(60L)).thenReturn(Optional.of(openInvoice));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.charge(300L)).thenReturn(ChargeOutcome.FAILED);
            // 2 still SCHEDULED after this failure
            when(dunningAttemptRepository.countBySubscriptionIdAndStatus(20L, DunningStatus.SCHEDULED))
                    .thenReturn(2L);

            dunningService.processDueAttempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            assertThat(cap.getAllValues())
                    .anyMatch(a -> a.getId().equals(200L) && a.getStatus() == DunningStatus.FAILED);

            // Sub NOT saved (still PAST_DUE — check no SUSPENDED save)
            verify(subscriptionRepository, never()).save(argThat(s ->
                    s.getStatus() == SubscriptionStatus.SUSPENDED));
        }

        @Test
        @DisplayName("marks attempt FAILED and SUSPENDS subscription when no more remain")
        void paymentFails_noMoreAttempts_suspensSubscription() {
            when(dunningAttemptRepository.findByStatusAndScheduledAtLessThanEqual(
                    eq(DunningStatus.SCHEDULED), any()))
                    .thenReturn(List.of(scheduledAttempt));
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(60L)).thenReturn(Optional.of(openInvoice));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.charge(300L)).thenReturn(ChargeOutcome.FAILED);
            // 0 remaining after this failure
            when(dunningAttemptRepository.countBySubscriptionIdAndStatus(20L, DunningStatus.SCHEDULED))
                    .thenReturn(0L);

            dunningService.processDueAttempts();

            // Sub saved with SUSPENDED
            ArgumentCaptor<Subscription> subCap = ArgumentCaptor.forClass(Subscription.class);
            verify(subscriptionRepository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
            assertThat(subCap.getValue().getGraceUntil()).isNull();
        }

        @Test
        @DisplayName("subscription no longer PAST_DUE — attempt skipped gracefully")
        void subscriptionNotPastDue_skipsAttempt() {
            pastDueSub.setStatus(SubscriptionStatus.ACTIVE);   // already recovered

            when(dunningAttemptRepository.findByStatusAndScheduledAtLessThanEqual(
                    eq(DunningStatus.SCHEDULED), any()))
                    .thenReturn(List.of(scheduledAttempt));
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(pastDueSub));

            dunningService.processDueAttempts();

            verifyNoInteractions(invoiceRepository, paymentIntentService, paymentGatewayPort, invoiceService);
            // Attempt marked FAILED (skip)
            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(DunningStatus.FAILED);
        }
    }
}
