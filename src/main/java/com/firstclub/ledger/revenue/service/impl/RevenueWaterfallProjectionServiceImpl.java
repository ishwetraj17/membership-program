package com.firstclub.ledger.revenue.service.impl;

import com.firstclub.ledger.revenue.dto.RevenueWaterfallProjectionDTO;
import com.firstclub.ledger.revenue.entity.RevenueWaterfallProjection;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.repository.RevenueWaterfallProjectionRepository;
import com.firstclub.ledger.revenue.service.RevenueWaterfallProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueWaterfallProjectionServiceImpl implements RevenueWaterfallProjectionService {

    private final RevenueWaterfallProjectionRepository waterfallRepository;
    private final RevenueRecognitionScheduleRepository scheduleRepository;

    @Override
    @Transactional
    public RevenueWaterfallProjectionDTO updateProjectionForDate(Long merchantId, LocalDate date) {
        // Compute recognised amount from POSTED schedule rows on this date
        BigDecimal recognized = scheduleRepository
                .sumPostedAmountByMerchantAndDate(merchantId, date);
        if (recognized == null) recognized = BigDecimal.ZERO;

        final BigDecimal recognizedFinal = recognized;

        RevenueWaterfallProjection projection = waterfallRepository
                .findByMerchantIdAndBusinessDate(merchantId, date)
                .orElseGet(() -> RevenueWaterfallProjection.builder()
                        .merchantId(merchantId)
                        .businessDate(date)
                        .build());

        projection.setRecognizedAmount(recognizedFinal);
        // Deferred closing = deferred_opening + billed - recognized - refunded - disputed
        BigDecimal deferredClosing = projection.getDeferredOpening()
                .add(projection.getBilledAmount())
                .subtract(recognizedFinal)
                .subtract(projection.getRefundedAmount())
                .subtract(projection.getDisputedAmount());
        projection.setDeferredClosing(deferredClosing);

        RevenueWaterfallProjection saved = waterfallRepository.save(projection);
        log.debug("Updated waterfall projection merchant={} date={} recognized={}",
                merchantId, date, recognizedFinal);

        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevenueWaterfallProjectionDTO> getWaterfall(Long merchantId, LocalDate from, LocalDate to) {
        return waterfallRepository
                .findByMerchantIdAndBusinessDateBetweenOrderByBusinessDateAsc(merchantId, from, to)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevenueWaterfallProjectionDTO> getWaterfallAllMerchants(LocalDate from, LocalDate to) {
        return waterfallRepository
                .findByBusinessDateBetweenOrderByMerchantIdAscBusinessDateAsc(from, to)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RevenueWaterfallProjectionDTO toDto(RevenueWaterfallProjection p) {
        return RevenueWaterfallProjectionDTO.builder()
                .id(p.getId())
                .merchantId(p.getMerchantId())
                .businessDate(p.getBusinessDate())
                .billedAmount(p.getBilledAmount())
                .deferredOpening(p.getDeferredOpening())
                .deferredClosing(p.getDeferredClosing())
                .recognizedAmount(p.getRecognizedAmount())
                .refundedAmount(p.getRefundedAmount())
                .disputedAmount(p.getDisputedAmount())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
