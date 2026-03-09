package com.firstclub.ledger.revenue.service;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionReportDTO;

import java.time.LocalDate;

public interface RevenueRecognitionReportService {

    /**
     * Generates a summary of revenue recognition activity for the given date range.
     *
     * @param from start date (inclusive)
     * @param to   end date (inclusive)
     * @return aggregated counts and amounts split by POSTED / PENDING / FAILED
     */
    RevenueRecognitionReportDTO reportByDateRange(LocalDate from, LocalDate to);
}
