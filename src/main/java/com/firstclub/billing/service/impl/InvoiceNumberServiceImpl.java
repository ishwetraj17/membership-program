package com.firstclub.billing.service.impl;

import com.firstclub.billing.entity.InvoiceSequence;
import com.firstclub.billing.repository.InvoiceSequenceRepository;
import com.firstclub.billing.service.InvoiceNumberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InvoiceNumberServiceImpl implements InvoiceNumberService {

    private static final String DEFAULT_PREFIX = "INV";

    private final InvoiceSequenceRepository sequenceRepository;

    @Override
    @Transactional
    public String generateNextInvoiceNumber(Long merchantId) {
        InvoiceSequence seq = sequenceRepository.findByMerchantIdWithLock(merchantId)
                .orElseGet(() -> {
                    InvoiceSequence newSeq = InvoiceSequence.builder()
                            .merchantId(merchantId)
                            .currentNumber(0L)
                            .prefix(DEFAULT_PREFIX)
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return sequenceRepository.save(newSeq);
                });

        long next = seq.getCurrentNumber() + 1;
        seq.setCurrentNumber(next);
        seq.setUpdatedAt(LocalDateTime.now());
        sequenceRepository.save(seq);

        return seq.getPrefix() + "-" + String.format("%06d", next);
    }

    @Override
    @Transactional
    public void initSequence(Long merchantId, String prefix) {
        InvoiceSequence seq = sequenceRepository.findByMerchantId(merchantId)
                .orElseGet(() -> InvoiceSequence.builder()
                        .merchantId(merchantId)
                        .currentNumber(0L)
                        .updatedAt(LocalDateTime.now())
                        .build());
        seq.setPrefix(prefix);
        seq.setUpdatedAt(LocalDateTime.now());
        sequenceRepository.save(seq);
    }
}
