package com.firstclub.recon.dto;

import com.firstclub.recon.entity.ExternalStatementImport;
import com.firstclub.recon.entity.StatementImportStatus;
import com.firstclub.recon.entity.StatementSourceType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Value
@Builder
public class StatementImportResponseDTO {
    Long                  id;
    Long                  merchantId;
    StatementSourceType   sourceType;
    LocalDate             statementDate;
    String                fileName;
    StatementImportStatus status;
    int                   rowCount;
    BigDecimal            totalAmount;
    LocalDateTime         createdAt;

    public static StatementImportResponseDTO from(ExternalStatementImport imp) {
        return StatementImportResponseDTO.builder()
                .id(imp.getId())
                .merchantId(imp.getMerchantId())
                .sourceType(imp.getSourceType())
                .statementDate(imp.getStatementDate())
                .fileName(imp.getFileName())
                .status(imp.getStatus())
                .rowCount(imp.getRowCount())
                .totalAmount(imp.getTotalAmount())
                .createdAt(imp.getCreatedAt())
                .build();
    }
}
