package com.firstclub.recon.service;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.dto.SettlementBatchItemResponseDTO;
import com.firstclub.recon.dto.SettlementBatchResponseDTO;
import com.firstclub.recon.entity.SettlementBatch;
import com.firstclub.recon.entity.SettlementBatchItem;
import com.firstclub.recon.entity.SettlementBatchStatus;
import com.firstclub.recon.repository.SettlementBatchItemRepository;
import com.firstclub.recon.repository.SettlementBatchRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementBatchService {

    private static final BigDecimal FEE_RATE     = new BigDecimal("0.02");
    private static final BigDecimal RESERVE_RATE = new BigDecimal("0.01");

    private final SettlementBatchRepository     batchRepository;
    private final SettlementBatchItemRepository itemRepository;
    private final PaymentRepository             paymentRepository;

    @Transactional
    public SettlementBatchResponseDTO runBatch(Long merchantId, LocalDate date, String gatewayName) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end   = date.plusDays(1).atStartOfDay();

        List<Payment> payments = paymentRepository
                .findByMerchantIdAndStatusAndCapturedAtBetween(merchantId, PaymentStatus.CAPTURED, start, end);

        log.info("Running settlement batch for merchant={} date={}: {} captured payments", merchantId, date, payments.size());

        SettlementBatch batch = SettlementBatch.builder()
                .merchantId(merchantId)
                .batchDate(date)
                .gatewayName(gatewayName)
                .status(SettlementBatchStatus.CREATED)
                .build();
        batch = batchRepository.save(batch);

        BigDecimal grossTotal   = BigDecimal.ZERO;
        BigDecimal feeTotal     = BigDecimal.ZERO;
        BigDecimal reserveTotal = BigDecimal.ZERO;

        for (Payment p : payments) {
            BigDecimal amount  = p.getAmount();
            BigDecimal fee     = amount.multiply(FEE_RATE).setScale(4, RoundingMode.HALF_UP);
            BigDecimal reserve = amount.multiply(RESERVE_RATE).setScale(4, RoundingMode.HALF_UP);
            BigDecimal net     = amount.subtract(fee).subtract(reserve);

            SettlementBatchItem item = SettlementBatchItem.builder()
                    .batchId(batch.getId())
                    .paymentId(p.getId())
                    .amount(amount)
                    .feeAmount(fee)
                    .reserveAmount(reserve)
                    .netAmount(net)
                    .build();
            itemRepository.save(item);

            grossTotal   = grossTotal.add(amount);
            feeTotal     = feeTotal.add(fee);
            reserveTotal = reserveTotal.add(reserve);
        }

        BigDecimal netTotal = grossTotal.subtract(feeTotal).subtract(reserveTotal);

        batch.setGrossAmount(grossTotal);
        batch.setFeeAmount(feeTotal);
        batch.setReserveAmount(reserveTotal);
        batch.setNetAmount(netTotal);
        batch.setStatus(SettlementBatchStatus.POSTED);
        batch = batchRepository.save(batch);

        List<SettlementBatchItem> items = itemRepository.findByBatchId(batch.getId());
        List<SettlementBatchItemResponseDTO> itemDTOs = items.stream()
                .map(SettlementBatchItemResponseDTO::from)
                .toList();

        return SettlementBatchResponseDTO.from(batch, itemDTOs);
    }

    @Transactional(readOnly = true)
    public SettlementBatchResponseDTO getBatch(Long batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new EntityNotFoundException("Settlement batch not found: " + batchId));
        List<SettlementBatchItemResponseDTO> items = itemRepository.findByBatchId(batchId)
                .stream().map(SettlementBatchItemResponseDTO::from).toList();
        return SettlementBatchResponseDTO.from(batch, items);
    }

    @Transactional(readOnly = true)
    public Page<SettlementBatchResponseDTO> listBatches(Long merchantId, Pageable pageable) {
        return batchRepository.findByMerchantId(merchantId, pageable)
                .map(b -> SettlementBatchResponseDTO.from(b, List.of()));
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchItemResponseDTO> listBatchItems(Long batchId) {
        return itemRepository.findByBatchId(batchId)
                .stream().map(SettlementBatchItemResponseDTO::from).toList();
    }
}
