package com.firstclub.payments.refund.service;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.service.impl.RefundAccountingServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RefundAccountingServiceImpl}.
 *
 * <p>Verifies that the correct double-entry reversal is posted for every refund:
 * <pre>
 *   DR SUBSCRIPTION_LIABILITY  refund.amount
 *   CR PG_CLEARING             refund.amount
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class RefundAccountingServiceTest {

    @Mock  private LedgerService ledgerService;
    @InjectMocks private RefundAccountingServiceImpl service;

    private RefundV2 refund(Long id, BigDecimal amount) {
        return RefundV2.builder()
                .id(id)
                .merchantId(10L)
                .paymentId(100L)
                .amount(amount)
                .reasonCode("UNIT_TEST")
                .status(RefundV2Status.COMPLETED)
                .completedAt(LocalDateTime.now())
                .build();
    }

    private Payment payment(BigDecimal amount) {
        return Payment.builder()
                .id(100L)
                .merchantId(10L)
                .paymentIntentId(999L)
                .amount(amount)
                .capturedAmount(amount)
                .refundedAmount(BigDecimal.ZERO)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(amount)
                .currency("INR")
                .status(PaymentStatus.CAPTURED)
                .gatewayTxnId("txn-accounting-test")
                .capturedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("posts REFUND_ISSUED entry with REFUND reference type and correct refund id")
    void postsCorrectEntryTypeAndReferenceType() {
        BigDecimal amount = new BigDecimal("250.00");
        RefundV2 refund   = refund(42L, amount);
        Payment  payment  = payment(new BigDecimal("500.00"));

        service.postRefundReversal(refund, payment);

        verify(ledgerService).postEntry(
                eq(LedgerEntryType.REFUND_ISSUED),
                eq(LedgerReferenceType.REFUND),
                eq(42L),
                eq("INR"),
                any());
    }

    @Test
    @DisplayName("ledger entry has exactly 2 lines: 1 DEBIT + 1 CREDIT")
    void entryHasTwoLines() {
        BigDecimal amount = new BigDecimal("100.00");
        service.postRefundReversal(refund(1L, amount), payment(new BigDecimal("300.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntry(any(), any(), any(), any(), linesCaptor.capture());

        List<LedgerLineRequest> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(2);

        long debits  = lines.stream().filter(l -> l.getDirection() == LineDirection.DEBIT).count();
        long credits = lines.stream().filter(l -> l.getDirection() == LineDirection.CREDIT).count();
        assertThat(debits).isEqualTo(1);
        assertThat(credits).isEqualTo(1);
    }

    @Test
    @DisplayName("DEBIT line is against SUBSCRIPTION_LIABILITY with correct amount")
    void debitLine_isSubscriptionLiability() {
        BigDecimal amount = new BigDecimal("123.45");
        service.postRefundReversal(refund(2L, amount), payment(new BigDecimal("500.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntry(any(), any(), any(), any(), linesCaptor.capture());

        LedgerLineRequest debit = linesCaptor.getValue().stream()
                .filter(l -> l.getDirection() == LineDirection.DEBIT)
                .findFirst().orElseThrow();

        assertThat(debit.getAccountName()).isEqualTo("SUBSCRIPTION_LIABILITY");
        assertThat(debit.getAmount()).isEqualByComparingTo(amount);
    }

    @Test
    @DisplayName("CREDIT line is against PG_CLEARING with correct amount")
    void creditLine_isPgClearing() {
        BigDecimal amount = new BigDecimal("75.00");
        service.postRefundReversal(refund(3L, amount), payment(new BigDecimal("500.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntry(any(), any(), any(), any(), linesCaptor.capture());

        LedgerLineRequest credit = linesCaptor.getValue().stream()
                .filter(l -> l.getDirection() == LineDirection.CREDIT)
                .findFirst().orElseThrow();

        assertThat(credit.getAccountName()).isEqualTo("PG_CLEARING");
        assertThat(credit.getAmount()).isEqualByComparingTo(amount);
    }

    @Test
    @DisplayName("entry is balanced — DEBIT amount equals CREDIT amount")
    void entryIsBalanced() {
        BigDecimal amount = new BigDecimal("399.99");
        service.postRefundReversal(refund(4L, amount), payment(new BigDecimal("500.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postEntry(any(), any(), any(), any(), linesCaptor.capture());

        List<LedgerLineRequest> lines = linesCaptor.getValue();
        BigDecimal totalDebits  = lines.stream().filter(l -> l.getDirection() == LineDirection.DEBIT)
                .map(LedgerLineRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = lines.stream().filter(l -> l.getDirection() == LineDirection.CREDIT)
                .map(LedgerLineRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    @Test
    @DisplayName("uses the payment currency for the ledger entry")
    void usesCurrencyFromPayment() {
        service.postRefundReversal(refund(5L, new BigDecimal("50")),
                payment(new BigDecimal("200.00")));  // payment.currency = "INR"

        verify(ledgerService).postEntry(any(), any(), any(), eq("INR"), any());
    }
}
