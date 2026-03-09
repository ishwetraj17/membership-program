package com.firstclub.recon.service;

import com.firstclub.recon.dto.StatementImportRequestDTO;
import com.firstclub.recon.dto.StatementImportResponseDTO;
import com.firstclub.recon.entity.ExternalStatementImport;
import com.firstclub.recon.entity.StatementImportStatus;
import com.firstclub.recon.entity.StatementSourceType;
import com.firstclub.recon.repository.ExternalStatementImportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatementImportService Unit Tests")
class StatementImportServiceTest {

    @Mock private ExternalStatementImportRepository importRepository;

    @InjectMocks private StatementImportService service;

    private static final Long      MERCHANT_ID = 1L;
    private static final LocalDate DATE        = LocalDate.of(2025, 6, 1);

    private StatementImportRequestDTO req(String csv) {
        return StatementImportRequestDTO.builder()
                .merchantId(MERCHANT_ID)
                .sourceType(StatementSourceType.GATEWAY)
                .statementDate(DATE)
                .fileName("stripe-2025-06-01.csv")
                .csvContent(csv)
                .build();
    }

    @Nested
    @DisplayName("importStatement")
    class ImportStatement {

        @Test
        @DisplayName("valid CSV → IMPORTED status, correct row count and total amount")
        void import_validCsv_importedStatus() {
            String csv = "txn_id,amount,currency,payment_date,reference\n" +
                         "TXN-001,1000.00,INR,2025-06-01,PAY-001\n" +
                         "TXN-002,2500.00,INR,2025-06-01,PAY-002\n";

            ExternalStatementImport pending = ExternalStatementImport.builder()
                    .id(1L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("stripe-2025-06-01.csv")
                    .status(StatementImportStatus.PENDING).build();

            ExternalStatementImport imported = ExternalStatementImport.builder()
                    .id(1L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("stripe-2025-06-01.csv")
                    .status(StatementImportStatus.IMPORTED)
                    .rowCount(2).totalAmount(new BigDecimal("3500.00")).build();

            when(importRepository.save(argThat(i -> i != null && i.getStatus() == StatementImportStatus.PENDING)))
                    .thenReturn(pending);
            when(importRepository.save(argThat(i -> i != null && i.getStatus() == StatementImportStatus.IMPORTED)))
                    .thenReturn(imported);

            StatementImportResponseDTO result = service.importStatement(req(csv));

            assertThat(result.getStatus()).isEqualTo(StatementImportStatus.IMPORTED);
            assertThat(result.getRowCount()).isEqualTo(2);
            assertThat(result.getTotalAmount()).isEqualByComparingTo("3500.00");
        }

        @Test
        @DisplayName("empty CSV → IMPORTED with zero rows and zero total")
        void import_emptyCsv_zeroRows() {
            ExternalStatementImport pending = ExternalStatementImport.builder()
                    .id(2L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("stripe-2025-06-01.csv")
                    .status(StatementImportStatus.PENDING).rowCount(0)
                    .totalAmount(BigDecimal.ZERO).build();
            ExternalStatementImport imported = ExternalStatementImport.builder()
                    .id(2L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("stripe-2025-06-01.csv")
                    .status(StatementImportStatus.IMPORTED).rowCount(0)
                    .totalAmount(BigDecimal.ZERO).build();

            when(importRepository.save(any())).thenReturn(pending).thenReturn(imported);

            StatementImportResponseDTO result = service.importStatement(req(""));

            assertThat(result.getRowCount()).isEqualTo(0);
            assertThat(result.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("malformed CSV (missing columns) → FAILED status")
        void import_malformedCsv_failedStatus() {
            String badCsv = "txn_id,amount\nTXN-001,100\n";

            ExternalStatementImport pending = ExternalStatementImport.builder()
                    .id(3L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("bad.csv")
                    .status(StatementImportStatus.PENDING).build();
            ExternalStatementImport failed = ExternalStatementImport.builder()
                    .id(3L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("bad.csv")
                    .status(StatementImportStatus.FAILED).build();

            when(importRepository.save(argThat(i -> i != null && i.getStatus() == StatementImportStatus.PENDING)))
                    .thenReturn(pending);
            when(importRepository.save(argThat(i -> i != null && i.getStatus() == StatementImportStatus.FAILED)))
                    .thenReturn(failed);

            StatementImportResponseDTO result = service.importStatement(
                    StatementImportRequestDTO.builder()
                            .merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                            .statementDate(DATE).fileName("bad.csv")
                            .csvContent(badCsv).build());

            assertThat(result.getStatus()).isEqualTo(StatementImportStatus.FAILED);
        }

        @Test
        @DisplayName("CSV with header only → IMPORTED with zero rows")
        void import_headerOnly_zeroRows() {
            String csv = "txn_id,amount,currency,payment_date,reference\n";

            ExternalStatementImport saved = ExternalStatementImport.builder()
                    .id(4L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("stripe-2025-06-01.csv")
                    .status(StatementImportStatus.IMPORTED).rowCount(0)
                    .totalAmount(BigDecimal.ZERO).build();

            when(importRepository.save(any())).thenReturn(saved);

            StatementImportResponseDTO result = service.importStatement(req(csv));
            assertThat(result.getRowCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("listImports")
    class ListImports {

        @Test
        @DisplayName("returns page mapped to DTOs")
        void listImports_returnsPage() {
            ExternalStatementImport imp = ExternalStatementImport.builder()
                    .id(1L).merchantId(MERCHANT_ID).sourceType(StatementSourceType.GATEWAY)
                    .statementDate(DATE).fileName("f.csv")
                    .status(StatementImportStatus.IMPORTED).rowCount(5)
                    .totalAmount(new BigDecimal("500")).build();

            PageRequest pageable = PageRequest.of(0, 10);
            when(importRepository.findByMerchantId(MERCHANT_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(imp)));

            var page = service.listImports(MERCHANT_ID, pageable);

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().get(0).getRowCount()).isEqualTo(5);
        }
    }
}
