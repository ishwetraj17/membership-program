package com.firstclub.recon.service;

import com.firstclub.recon.dto.StatementImportRequestDTO;
import com.firstclub.recon.dto.StatementImportResponseDTO;
import com.firstclub.recon.entity.ExternalStatementImport;
import com.firstclub.recon.entity.StatementImportStatus;
import com.firstclub.recon.repository.ExternalStatementImportRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatementImportService {

    /** Expected CSV header columns (case-insensitive). */
    private static final int CSV_TXN_ID    = 0;
    private static final int CSV_AMOUNT    = 1;
    private static final int CSV_CURRENCY  = 2;
    private static final int CSV_DATE      = 3;
    private static final int CSV_REF       = 4;
    private static final int EXPECTED_COLS = 5;

    private final ExternalStatementImportRepository importRepository;

    @Transactional
    public StatementImportResponseDTO importStatement(StatementImportRequestDTO req) {
        ExternalStatementImport imp = ExternalStatementImport.builder()
                .merchantId(req.getMerchantId())
                .sourceType(req.getSourceType())
                .statementDate(req.getStatementDate())
                .fileName(req.getFileName())
                .status(StatementImportStatus.PENDING)
                .build();
        imp = importRepository.save(imp);

        try {
            List<StatementLineRecord> lines = parseCsv(req.getCsvContent());

            BigDecimal total = lines.stream()
                    .map(StatementLineRecord::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            imp.setRowCount(lines.size());
            imp.setTotalAmount(total);
            imp.setStatus(StatementImportStatus.IMPORTED);
            imp = importRepository.save(imp);

            log.info("Imported statement for merchant={} date={}: {} rows, total={}",
                    req.getMerchantId(), req.getStatementDate(), lines.size(), total);
        } catch (Exception e) {
            log.error("Statement import failed for merchant={}: {}", req.getMerchantId(), e.getMessage());
            imp.setStatus(StatementImportStatus.FAILED);
            imp = importRepository.save(imp);
        }

        return StatementImportResponseDTO.from(imp);
    }

    @Transactional(readOnly = true)
    public Page<StatementImportResponseDTO> listImports(Long merchantId, Pageable pageable) {
        return importRepository.findByMerchantId(merchantId, pageable)
                .map(StatementImportResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public StatementImportResponseDTO getImport(Long importId) {
        ExternalStatementImport imp = importRepository.findById(importId)
                .orElseThrow(() -> new EntityNotFoundException("Statement import not found: " + importId));
        return StatementImportResponseDTO.from(imp);
    }

    /**
     * Parses CSV content.
     * Expected format: first line is header (txn_id,amount,currency,payment_date,reference).
     * Subsequent lines are data rows.
     */
    private List<StatementLineRecord> parseCsv(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }
        List<StatementLineRecord> records = new ArrayList<>();
        String[] lines = csvContent.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isBlank()) continue;
            // Skip header row
            if (i == 0 && line.toLowerCase().startsWith("txn_id")) continue;

            String[] cols = line.split(",", -1);
            if (cols.length < EXPECTED_COLS) {
                throw new IllegalArgumentException("CSV row " + (i + 1) + " has fewer than " + EXPECTED_COLS + " columns: " + line);
            }
            String txnId    = cols[CSV_TXN_ID].trim();
            BigDecimal amt  = new BigDecimal(cols[CSV_AMOUNT].trim());
            String currency = cols[CSV_CURRENCY].trim();
            LocalDate date  = LocalDate.parse(cols[CSV_DATE].trim());
            String ref      = cols[CSV_REF].trim();
            records.add(new StatementLineRecord(txnId, amt, currency, date, ref));
        }
        return records;
    }

    /** Internal record representing one parsed CSV line. */
    record StatementLineRecord(String txnId, BigDecimal amount, String currency, LocalDate paymentDate, String reference) {}
}
