package com.firstclub.recon.service;

import com.firstclub.ledger.dto.LedgerLineRequest;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Simulates nightly settlement: sweeps captured payment funds from
 * PG_CLEARING → BANK via a double-entry ledger entry, then records
 * the settlement batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final PaymentRepository    paymentRepository;
    private final SettlementRepository settlementRepository;
    private final LedgerService        ledgerService;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Settle all captured payments for {@code settlementDate}.
     * Idempotent: returns the existing settlement if one already exists for that date.
     *
     * @return the created (or existing) settlement DTO
     */
    @Transactional
    public SettlementDTO settleForDate(LocalDate settlementDate) {
        // Idempotency guard
        return settlementRepository.findBySettlementDate(settlementDate)
                .map(this::toDTO)
                .orElseGet(() -> doSettle(settlementDate));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private SettlementDTO doSettle(LocalDate settlementDate) {
        LocalDateTime dayStart = settlementDate.atStartOfDay();
        LocalDateTime dayEnd   = settlementDate.atTime(LocalTime.MAX);

        List<Payment> captured = paymentRepository
                .findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, dayStart, dayEnd);

        BigDecimal total = captured.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Settlement for {}: nothing to settle (no captured payments)", settlementDate);
            Settlement empty = settlementRepository.save(Settlement.builder()
                    .settlementDate(settlementDate)
                    .totalAmount(BigDecimal.ZERO)
                    .currency("INR")
                    .build());
            return toDTO(empty);
        }

        // Create Settlement record first so we have an ID for the ledger entry
        Settlement settlement = settlementRepository.save(Settlement.builder()
                .settlementDate(settlementDate)
                .totalAmount(total)
                .currency("INR")
                .build());

        // Post journal entry: move funds from PG clearing account to bank
        //   DEBIT  BANK          (asset increases — funds arrive in bank)
        //   CREDIT PG_CLEARING   (clearing account decreases)
        ledgerService.postEntry(
                LedgerEntryType.SETTLEMENT,
                LedgerReferenceType.SETTLEMENT_BATCH,
                settlement.getId(),
                "INR",
                List.of(
                        LedgerLineRequest.builder()
                                .accountName("BANK")
                                .direction(LineDirection.DEBIT)
                                .amount(total)
                                .build(),
                        LedgerLineRequest.builder()
                                .accountName("PG_CLEARING")
                                .direction(LineDirection.CREDIT)
                                .amount(total)
                                .build()
                )
        );

        log.info("Settlement {} for {}: total={} INR, {} payments swept",
                settlement.getId(), settlementDate, total, captured.size());

        return toDTO(settlement);
    }

    private SettlementDTO toDTO(Settlement s) {
        return SettlementDTO.builder()
                .id(s.getId())
                .settlementDate(s.getSettlementDate())
                .totalAmount(s.getTotalAmount())
                .currency(s.getCurrency())
                .build();
    }
}
