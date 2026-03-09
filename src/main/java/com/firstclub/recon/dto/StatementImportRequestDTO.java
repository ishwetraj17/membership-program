package com.firstclub.recon.dto;

import com.firstclub.recon.entity.StatementSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatementImportRequestDTO {
    private Long               merchantId;
    private StatementSourceType sourceType;
    private LocalDate          statementDate;
    private String             fileName;
    /** CSV content: header row (txn_id,amount,currency,payment_date,reference) followed by data rows */
    private String             csvContent;
}
