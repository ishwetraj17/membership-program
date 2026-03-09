package com.firstclub.dunning.service;

import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.SubscriptionHistoryRepository;
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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalService Unit Tests")
class RenewalServiceTest {

    @Mock private SubscriptionRepository       subscriptionRepository;
    @Mock private SubscriptionHistoryRepository historyRepository;
    @Mock private InvoiceService               invoiceService;
    @Mock private PaymentIntentService         paymentIntentService;
    @Mock private DunningAttemptRepository     dunningAttemptRepository;
    @Mock private PaymentGatewayPort           paymentGatewayPort;

    @InjectMocks
    private RenewalService renewalService;

    private Subscription       activeSub;
    private InvoiceDTO         openInvoice;
    private PaymentIntentDTO   pi;

    @BeforeEach
    void setUp() {
        MembershipTier tier = MembershipTier.builder().id(1L).name("SILVER").level(1)
                .discountPercentage(BigDecimal.ZERO).build();
        MembershipPlan plan = MembershipPlan.builder().id(1L).name("Silver Monthly")
                .type(MembershipPlan.PlanType.MONTHLY).price(new BigDecimal("299.00"))
                .durationInMonths(1).isActive(true).tier(tier).build();
        User user = User.builder().id(1L).email("test@test.com")
                .name("Test User").status(User.UserStatus.ACTIVE).build();

        LocalDateTime now = LocalDateTime.now();
        activeSub = Subscription.builder()
                .id(10L).user(user).plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(now.minusMonths(1))
                .endDate(now.minusDays(1))
                .nextBillingDate(now.minusDays(1))
                .nextRenewalAt(now.minusDays(1))
                .paidAmount(new BigDecimal("299.00"))
                .autoRenewal(true)
                .cancelAtPeriodEnd(false)
                .build();

        openInvoice = InvoiceDTO.builder()
                .id(50L).userId(1L).subscriptionId(10L)
                .totalAmount(new BigDecimal("299.00"))
                .currency("INR")
                .periodStart(now.minusDays(1))
                .periodEnd(now.plusMonths(1).minusDays(1))
                .build();

        pi = PaymentIntentDTO.builder()
                .id(100L).invoiceId(50L)
                .amount(new BigDecimal("299.00")).currency("INR")
                .status(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD)
                .clientSecret("cs_test").build();
    }

    // -------------------------------------------------------------------------
    // Cancel-at-period-end
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("cancel-at-period-end")
    class CancelAtPeriodEnd {

        @Test
        @DisplayName("cancels subscription when cancelAtPeriodEnd=true; does not create invoice")
        void cancelAtPeriodEnd_cancelsSubscription() {
            activeSub.setCancelAtPeriodEnd(true);
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSub));

            renewalService.processRenewal(10L);

            ArgumentCaptor<Subscription> cap = ArgumentCaptor.forClass(Subscription.class);
            verify(subscriptionRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
            assertThat(cap.getValue().getCancelledAt()).isNotNull();
            assertThat(cap.getValue().getCancelAtPeriodEnd()).isFalse();

            verifyNoInteractions(invoiceService, paymentIntentService, paymentGatewayPort, dunningAttemptRepository);
        }
    }

    // -------------------------------------------------------------------------
    // Renewal payment succeeds
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("renewal payment succeeds")
    class RenewalSuccess {

        @Test
        @DisplayName("advances endDate and nextRenewalAt after successful charge")
        void paymentSucceeds_advancesSubscriptionPeriod() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSub));
            when(invoiceService.createInvoiceForSubscription(anyLong(), anyLong(), anyLong(),
                    any(), any())).thenReturn(openInvoice);
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString())).thenReturn(pi);
            when(paymentGatewayPort.charge(100L)).thenReturn(ChargeOutcome.SUCCESS);

            renewalService.processRenewal(10L);

            verify(invoiceService).onPaymentSucceeded(50L);
            // advanceSubscriptionPeriod reloads and saves with new dates
            verify(subscriptionRepository, atLeast(2)).findById(10L);
            verify(subscriptionRepository, atLeast(1)).save(argThat(s ->
                    s.getNextRenewalAt() != null && s.getEndDate() != null
                            && s.getGraceUntil() == null));
            verifyNoInteractions(dunningAttemptRepository);
        }

        @Test
        @DisplayName("credits cover full amount — no payment intent created")
        void creditsCoverFullAmount_noPaymentIntent() {
            InvoiceDTO freeInvoice = InvoiceDTO.builder()
                    .id(51L).userId(1L).subscriptionId(10L)
                    .totalAmount(BigDecimal.ZERO).currency("INR")
                    .periodStart(openInvoice.getPeriodStart())
                    .periodEnd(openInvoice.getPeriodEnd())
                    .build();
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSub));
            when(invoiceService.createInvoiceForSubscription(anyLong(), anyLong(), anyLong(),
                    any(), any())).thenReturn(freeInvoice);

            renewalService.processRenewal(10L);

            verify(invoiceService).onPaymentSucceeded(51L);
            verifyNoInteractions(paymentIntentService, paymentGatewayPort, dunningAttemptRepository);
        }
    }

    // -------------------------------------------------------------------------
    // Renewal payment fails → PAST_DUE + dunning
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("renewal payment fails")
    class RenewalFailed {

        @Test
        @DisplayName("marks PAST_DUE and schedules 4 dunning attempts on charge failure")
        void paymentFails_setsPastDueAndSchedulesDunning() {
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSub));
            when(invoiceService.createInvoiceForSubscription(anyLong(), anyLong(), anyLong(),
                    any(), any())).thenReturn(openInvoice);
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString())).thenReturn(pi);
            when(paymentGatewayPort.charge(100L)).thenReturn(ChargeOutcome.FAILED);

            renewalService.processRenewal(10L);

            // Sub should be saved as PAST_DUE with a grace period
            ArgumentCaptor<Subscription> subCap = ArgumentCaptor.forClass(Subscription.class);
            verify(subscriptionRepository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
            assertThat(subCap.getValue().getGraceUntil()).isNotNull();

            // 4 dunning attempts (matching DUNNING_OFFSETS: +1h, +6h, +24h, +3d)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<DunningAttempt>> attemptsCap =
                    ArgumentCaptor.forClass(Collection.class);
            verify(dunningAttemptRepository).saveAll(attemptsCap.capture());
            assertThat(attemptsCap.getValue()).hasSize(4);

            // All should be SCHEDULED; attempt numbers should be 1–4
            List<DunningAttempt> schedules = List.copyOf((Collection<DunningAttempt>) attemptsCap.getValue());
            assertThat(schedules).allMatch(a -> a.getStatus() == DunningAttempt.DunningStatus.SCHEDULED);
            assertThat(schedules).allMatch(a -> a.getSubscriptionId().equals(10L));
            assertThat(schedules).allMatch(a -> a.getInvoiceId().equals(50L));
            assertThat(schedules).extracting(DunningAttempt::getAttemptNumber)
                    .containsExactly(1, 2, 3, 4);

            // Schedule offsets in ascending order
            List<LocalDateTime> times = schedules.stream()
                    .map(DunningAttempt::getScheduledAt)
                    .toList();
            for (int i = 1; i < times.size(); i++) {
                assertThat(times.get(i)).isAfter(times.get(i - 1));
            }
        }

        @Test
        @DisplayName("subscription not found → throws MembershipException")
        void subscriptionNotFound_throwsMembershipException() {
            when(subscriptionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> renewalService.processRenewal(999L))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> assertThat(((MembershipException) ex).getHttpStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("subscription not ACTIVE → skips processing without error")
        void subscriptionNotActive_skipsGracefully() {
            activeSub.setStatus(SubscriptionStatus.PAST_DUE);
            when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(activeSub));

            renewalService.processRenewal(10L);

            verifyNoInteractions(invoiceService, paymentIntentService,
                    paymentGatewayPort, dunningAttemptRepository);
        }
    }

    // -------------------------------------------------------------------------
    // Dunning offset schedule validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dunning offsets are +1h, +6h, +24h, +3d in order")
    void dunningOffsets_matchSpec() {
        assertThat(RenewalService.DUNNING_OFFSETS).hasSize(4);
        assertThat(RenewalService.DUNNING_OFFSETS.get(0).toHours()).isEqualTo(1);
        assertThat(RenewalService.DUNNING_OFFSETS.get(1).toHours()).isEqualTo(6);
        assertThat(RenewalService.DUNNING_OFFSETS.get(2).toHours()).isEqualTo(24);
        assertThat(RenewalService.DUNNING_OFFSETS.get(3).toDays()).isEqualTo(3);
    }
}
