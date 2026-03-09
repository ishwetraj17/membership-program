package com.firstclub.payments.disputes.service;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.service.impl.DisputeAccountingServiceImpl;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DisputeAccountingService — Unit Tests")
class DisputeAccountingServiceTest {

    @Mock  private LedgerService ledgerService;
    @Captor private ArgumentCaptor<LedgerEntryType>         entryTypeCaptor;
    @Captor private ArgumentCaptor<LedgerReferenceType>     refTypeCaptor;
    @Captor private ArgumentCaptor<Long>                    refIdCaptor;
    @Captor private ArgumentCaptor<String>                  currencyCaptor;
    @Captor private ArgumentCaptor<List<LedgerLineRequest>> linesCaptor;

    @InjectMocks
    private DisputeAccountingServiceImpl accountingService;

    private Dispute dispute;
    private Payment payment;

    @BeforeEach
    void setUp() {
        dispute = Dispute.builder()
                .id(10L)
                .paymentId(42L)
                .amount(new BigDecimal("500.00"))
                .status(DisputeStatus.OPEN)
                .build();
        payment = Payment.builder()
                .id(42L)
                .currency("INR")
                .status(PaymentStatus.DISPUTED)
                .build();
        when(ledgerService.postEntry(any(), any(), any(), any(), any())).thenReturn(null);
    }

    private void captureArgs() {
        verify(ledgerService).postEntry(
                entryTypeCaptor.capture(),
                refTypeCaptor.capture(),
                refIdCaptor.capture(),
                currencyCaptor.capture(),
                linesCaptor.capture());
    }

    // ── postDisputeOpen ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("postDisputeOpen")
    class OpenTests {

        @Test
        @DisplayName("entry type is DISPUTE_OPENED")
        void open_entryType() {
            accountingService.postDisputeOpen(dispute, payment);
            captureArgs();
            assertThat(entryTypeCaptor.getValue()).isEqualTo(LedgerEntryType.DISPUTE_OPENED);
        }

        @Test
        @DisplayName("reference type is DISPUTE with correct referenceId")
        void open_referenceType() {
            accountingService.postDisputeOpen(dispute, payment);
            captureArgs();
            assertThat(refTypeCaptor.getValue()).isEqualTo(LedgerReferenceType.DISPUTE);
            assertThat(refIdCaptor.getValue()).isEqualTo(10L);
        }

        @Test
        @DisplayName("exactly 2 lines (1 DR + 1 CR)")
        void open_twoLines() {
            accountingService.postDisputeOpen(dispute, payment);
            captureArgs();
            assertThat(linesCaptor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("DEBIT is DISPUTE_RESERVE")
        void open_debitDisputeReserve() {
            accountingService.postDisputeOpen(dispute, payment);
            captureArgs();
            LedgerLineRequest debit = linesCaptor.getValue().stream()
                    .filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
            assertThat(debit.getAccountName()).isEqualTo("DISPUTE_RESERVE");
            assertThat(debit.getAmount()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("CREDIT is PG_CLEARING")
        void open_creditPgClearing() {
            accountingService.postDisputeOpen(dispute, payment);
            captureArgs();
            LedgerLineRequest credit = linesCaptor.getValue().stream()
                    .filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();
            assertThat(credit.getAccountName()).isEqualTo("PG_CLEARING");
            assertThat(credit.getAmount()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("amounts are balanced")
        void open_balanced() {
            accountingService.postDisputeOpen(dispute, payment);
            captureArgs();
            BigDecimal dr = sumByDirection(linesCaptor.getValue(), LineDirection.DEBIT);
            BigDecimal cr = sumByDirection(linesCaptor.getValue(), LineDirection.CREDIT);
            assertThat(dr).isEqualByComparingTo(cr);
        }
    }

    // ── postDisputeWon ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("postDisputeWon")
    class WonTests {

        @Test
        @DisplayName("entry type is DISPUTE_WON")
        void won_entryType() {
            dispute.setStatus(DisputeStatus.WON);
            accountingService.postDisputeWon(dispute, payment);
            captureArgs();
            assertThat(entryTypeCaptor.getValue()).isEqualTo(LedgerEntryType.DISPUTE_WON);
        }

        @Test
        @DisplayName("DEBIT is PG_CLEARING, CREDIT is DISPUTE_RESERVE")
        void won_drPgClearing_crDisputeReserve() {
            dispute.setStatus(DisputeStatus.WON);
            accountingService.postDisputeWon(dispute, payment);
            captureArgs();
            List<LedgerLineRequest> lines = linesCaptor.getValue();
            LedgerLineRequest debit  = lines.stream().filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
            LedgerLineRequest credit = lines.stream().filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();
            assertThat(debit.getAccountName()).isEqualTo("PG_CLEARING");
            assertThat(credit.getAccountName()).isEqualTo("DISPUTE_RESERVE");
            assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        }

        @Test
        @DisplayName("amounts are balanced")
        void won_balanced() {
            accountingService.postDisputeWon(dispute, payment);
            captureArgs();
            assertThat(sumByDirection(linesCaptor.getValue(), LineDirection.DEBIT))
                    .isEqualByComparingTo(sumByDirection(linesCaptor.getValue(), LineDirection.CREDIT));
        }
    }

    // ── postDisputeLost ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("postDisputeLost (chargeback)")
    class LostTests {

        @Test
        @DisplayName("entry type is CHARGEBACK_POSTED")
        void lost_entryType() {
            dispute.setStatus(DisputeStatus.LOST);
            accountingService.postDisputeLost(dispute, payment);
            captureArgs();
            assertThat(entryTypeCaptor.getValue()).isEqualTo(LedgerEntryType.CHARGEBACK_POSTED);
        }

        @Test
        @DisplayName("DEBIT is CHARGEBACK_EXPENSE, CREDIT is DISPUTE_RESERVE")
        void lost_drChargebackExpense_crDisputeReserve() {
            dispute.setStatus(DisputeStatus.LOST);
            accountingService.postDisputeLost(dispute, payment);
            captureArgs();
            List<LedgerLineRequest> lines = linesCaptor.getValue();
            LedgerLineRequest debit  = lines.stream().filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
            LedgerLineRequest credit = lines.stream().filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();
            assertThat(debit.getAccountName()).isEqualTo("CHARGEBACK_EXPENSE");
            assertThat(credit.getAccountName()).isEqualTo("DISPUTE_RESERVE");
        }

        @Test
        @DisplayName("amounts are balanced")
        void lost_balanced() {
            accountingService.postDisputeLost(dispute, payment);
            captureArgs();
            assertThat(sumByDirection(linesCaptor.getValue(), LineDirection.DEBIT))
                    .isEqualByComparingTo(sumByDirection(linesCaptor.getValue(), LineDirection.CREDIT));
        }

        @Test
        @DisplayName("currency is taken from payment")
        void lost_currencyFromPayment() {
            accountingService.postDisputeLost(dispute, payment);
            captureArgs();
            assertThat(currencyCaptor.getValue()).isEqualTo("INR");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BigDecimal sumByDirection(List<LedgerLineRequest> lines, LineDirection direction) {
        return lines.stream()
                .filter(l -> l.getDirection() == direction)
                .map(LedgerLineRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
