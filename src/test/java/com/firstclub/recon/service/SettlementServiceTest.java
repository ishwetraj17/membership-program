package com.firstclub.recon.service;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.dto.SettlementDTO;
import com.firstclub.recon.entity.Settlement;
import com.firstclub.recon.repository.SettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementService Unit Tests")
class SettlementServiceTest {

    @Mock private PaymentRepository    paymentRepository;
    @Mock private SettlementRepository settlementRepository;
    @Mock private LedgerService        ledgerService;

    @InjectMocks
    private SettlementService service;

    private static final LocalDate DATE       = LocalDate.of(2025, 6, 1);
    private static final LocalDateTime START  = DATE.atStartOfDay();
    private static final LocalDateTime END    = DATE.atTime(LocalTime.MAX);

    // -----------------------------------------------------------------------
    // Happy path: captured payments produce a balanced settlement entry
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("settle with captured payments")
    class WithPayments {

        @Test
        @DisplayName("creates settlement record and posts BANK debit / PG_CLEARING credit")
        void settle_createsLedgerEntry() {
            Payment p1 = Payment.builder().id(1L).paymentIntentId(10L)
                    .amount(new BigDecimal("499")).currency("INR")
                    .status(PaymentStatus.CAPTURED).gatewayTxnId("T1")
                    .capturedAt(START.plusHours(1)).build();
            Payment p2 = Payment.builder().id(2L).paymentIntentId(11L)
                    .amount(new BigDecimal("299")).currency("INR")
                    .status(PaymentStatus.CAPTURED).gatewayTxnId("T2")
                    .capturedAt(START.plusHours(2)).build();

            Settlement saved = Settlement.builder().id(77L).settlementDate(DATE)
                    .totalAmount(new BigDecimal("798")).currency("INR").build();

            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.empty());
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, START, END))
                    .thenReturn(List.of(p1, p2));
            when(settlementRepository.save(any())).thenReturn(saved);
            when(ledgerService.postEntry(any(), any(), any(), any(), any()))
                    .thenReturn(LedgerEntry.builder().id(55L).build());

            SettlementDTO dto = service.settleForDate(DATE);

            // Verify DTO fields
            assertThat(dto.getId()).isEqualTo(77L);
            assertThat(dto.getTotalAmount()).isEqualByComparingTo("798");
            assertThat(dto.getCurrency()).isEqualTo("INR");

            // Verify ledger entry arguments
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
            verify(ledgerService).postEntry(
                    eq(LedgerEntryType.SETTLEMENT),
                    eq(LedgerReferenceType.SETTLEMENT_BATCH),
                    eq(77L),
                    eq("INR"),
                    linesCaptor.capture());

            List<LedgerLineRequest> lines = linesCaptor.getValue();
            assertThat(lines).hasSize(2);

            LedgerLineRequest debitLine = lines.stream()
                    .filter(l -> l.getDirection() == LineDirection.DEBIT).findFirst().orElseThrow();
            LedgerLineRequest creditLine = lines.stream()
                    .filter(l -> l.getDirection() == LineDirection.CREDIT).findFirst().orElseThrow();

            assertThat(debitLine.getAccountName()).isEqualTo("BANK");
            assertThat(debitLine.getAmount()).isEqualByComparingTo("798");
            assertThat(creditLine.getAccountName()).isEqualTo("PG_CLEARING");
            assertThat(creditLine.getAmount()).isEqualByComparingTo("798");
        }

        @Test
        @DisplayName("debit and credit amounts are equal (balanced)")
        void settle_entryIsBalanced() {
            Payment p = Payment.builder().id(3L).paymentIntentId(12L)
                    .amount(new BigDecimal("799")).currency("INR")
                    .status(PaymentStatus.CAPTURED).gatewayTxnId("T3")
                    .capturedAt(START.plusHours(3)).build();

            Settlement saved = Settlement.builder().id(88L).settlementDate(DATE)
                    .totalAmount(new BigDecimal("799")).currency("INR").build();

            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.empty());
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, START, END))
                    .thenReturn(List.of(p));
            when(settlementRepository.save(any())).thenReturn(saved);
            when(ledgerService.postEntry(any(), any(), any(), any(), any()))
                    .thenReturn(LedgerEntry.builder().id(56L).build());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerLineRequest>> linesCaptor = ArgumentCaptor.forClass(List.class);
            service.settleForDate(DATE);
            verify(ledgerService).postEntry(any(), any(), any(), any(), linesCaptor.capture());

            List<LedgerLineRequest> lines = linesCaptor.getValue();
            BigDecimal drTotal = lines.stream().filter(l -> l.getDirection() == LineDirection.DEBIT)
                    .map(LedgerLineRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal crTotal = lines.stream().filter(l -> l.getDirection() == LineDirection.CREDIT)
                    .map(LedgerLineRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(drTotal).isEqualByComparingTo(crTotal);
        }
    }

    // -----------------------------------------------------------------------
    // No payments: produces an empty settlement (total=0) without ledger entry
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("no captured payments → zero settlement, no ledger entry posted")
    void noPayments_zeroSettlement() {
        Settlement zeroSettlement = Settlement.builder().id(99L).settlementDate(DATE)
                .totalAmount(BigDecimal.ZERO).currency("INR").build();

        when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.empty());
        when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, START, END))
                .thenReturn(List.of());
        when(settlementRepository.save(any())).thenReturn(zeroSettlement);

        SettlementDTO dto = service.settleForDate(DATE);

        assertThat(dto.getTotalAmount()).isEqualByComparingTo("0");
        verifyNoInteractions(ledgerService);
    }

    // -----------------------------------------------------------------------
    // Idempotency: returns existing settlement without re-settling
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("existing settlement returned without re-processing")
    void idempotent_returnsExisting() {
        Settlement existing = Settlement.builder().id(11L).settlementDate(DATE)
                .totalAmount(new BigDecimal("500")).currency("INR").build();

        when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.of(existing));

        SettlementDTO dto = service.settleForDate(DATE);

        assertThat(dto.getId()).isEqualTo(11L);
        assertThat(dto.getTotalAmount()).isEqualByComparingTo("500");
        verifyNoInteractions(paymentRepository, ledgerService);
    }
}
