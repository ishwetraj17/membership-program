package com.firstclub.recon.dto;

import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconReport;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Value
@Builder
public class ReconReportDTO {
    Long                    id;
    LocalDate               reportDate;
    BigDecimal              expectedTotal;
    BigDecimal              actualTotal;
    BigDecimal              variance;
    int                     mismatchCount;
    List<ReconMismatchDTO>  mismatches;

    public static ReconReportDTO from(ReconReport report, List<ReconMismatch> mismatches) {
        BigDecimal variance = report.getActualTotal().subtract(report.getExpectedTotal());
        return ReconReportDTO.builder()
                .id(report.getId())
                .reportDate(report.getReportDate())
                .expectedTotal(report.getExpectedTotal())
                .actualTotal(report.getActualTotal())
                .variance(variance)
                .mismatchCount(report.getMismatchCount())
                .mismatches(mismatches.stream().map(ReconMismatchDTO::from).toList())
                .build();
    }
}
