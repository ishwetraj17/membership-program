package com.firstclub.ledger.revenue.service.impl;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionReportDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RevenueRecognitionReportServiceImpl implements RevenueRecognitionReportService {

    private final RevenueRecognitionScheduleRepository scheduleRepository;

    @Override
    @Transactional(readOnly = true)
    public RevenueRecognitionReportDTO reportByDateRange(LocalDate from, LocalDate to) {
        BigDecimal postedAmount  = nullSafe(scheduleRepository.sumAmountByDateRangeAndStatus(from, to, RevenueRecognitionStatus.POSTED));
        BigDecimal pendingAmount = nullSafe(scheduleRepository.sumAmountByDateRangeAndStatus(from, to, RevenueRecognitionStatus.PENDING));
        BigDecimal failedAmount  = nullSafe(scheduleRepository.sumAmountByDateRangeAndStatus(from, to, RevenueRecognitionStatus.FAILED));

        List<RevenueRecognitionSchedule> all = scheduleRepository.findByRecognitionDateBetween(from, to);

        int postedCount  = (int) all.stream().filter(s -> s.getStatus() == RevenueRecognitionStatus.POSTED).count();
        int pendingCount = (int) all.stream().filter(s -> s.getStatus() == RevenueRecognitionStatus.PENDING).count();
        int failedCount  = (int) all.stream().filter(s -> s.getStatus() == RevenueRecognitionStatus.FAILED).count();

        return RevenueRecognitionReportDTO.builder()
                .from(from)
                .to(to)
                .postedAmount(postedAmount)
                .pendingAmount(pendingAmount)
                .failedAmount(failedAmount)
                .postedCount(postedCount)
                .pendingCount(pendingCount)
                .failedCount(failedCount)
                .build();
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
