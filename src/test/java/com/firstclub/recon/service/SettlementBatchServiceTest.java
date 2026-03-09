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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementBatchService Unit Tests")
class SettlementBatchServiceTest {

    @Mock private SettlementBatchRepository     batchRepository;
    @Mock private SettlementBatchItemRepository itemRepository;
    @Mock private PaymentRepository             paymentRepository;

    @InjectMocks private SettlementBatchService service;

    private static final LocalDate DATE         = LocalDate.of(2025, 6, 1);
    private static final Long      MERCHANT_ID  = 1L;
    private static final String    GATEWAY_NAME = "STRIPE";

    private Payment payment(Long id, String amount) {
        return Payment.builder()
                .id(id)
                .merchantId(MERCHANT_ID)
                .amount(new BigDecimal(amount))
                .status(PaymentStatus.CAPTURED)
                .capturedAt(DATE.atStartOfDay().plusHours(1))
                .currency("INR")
                .build();
    }

    @Nested
    @DisplayName("runBatch")
    class RunBatch {

        @Test
        @DisplayName("two payments → correct gross/fee/net totals and POSTED status")
        void runBatch_twoPayments_correctTotals() {
            Payment p1 = payment(1L, "1000.00");
            Payment p2 = payment(2L, "2000.00");

            LocalDateTime start = DATE.atStartOfDay();
            LocalDateTime end   = DATE.plusDays(1).atStartOfDay();

            when(paymentRepository.findByMerchantIdAndStatusAndCapturedAtBetween(
                    MERCHANT_ID, PaymentStatus.CAPTURED, start, end))
                    .thenReturn(List.of(p1, p2));

            SettlementBatch savedBatch = SettlementBatch.builder()
                    .id(10L).merchantId(MERCHANT_ID).batchDate(DATE)
                    .gatewayName(GATEWAY_NAME).status(SettlementBatchStatus.CREATED).build();

            when(batchRepository.save(any())).thenReturn(savedBatch);
            when(itemRepository.save(any())).thenAnswer(inv -> {
                SettlementBatchItem item = inv.getArgument(0);
                item.setId(99L);
                return item;
            });

            SettlementBatch postedBatch = SettlementBatch.builder()
                    .id(10L).merchantId(MERCHANT_ID).batchDate(DATE)
                    .gatewayName(GATEWAY_NAME)
                    .grossAmount(new BigDecimal("3000.00"))
                    .feeAmount(new BigDecimal("60.0000"))
                    .reserveAmount(new BigDecimal("30.0000"))
                    .netAmount(new BigDecimal("2910.0000"))
                    .status(SettlementBatchStatus.POSTED).build();

            when(batchRepository.save(argThat(b -> b.getStatus() == SettlementBatchStatus.POSTED)))
                    .thenReturn(postedBatch);
            when(itemRepository.findByBatchId(10L)).thenReturn(List.of());

            SettlementBatchResponseDTO result = service.runBatch(MERCHANT_ID, DATE, GATEWAY_NAME);

            assertThat(result.getStatus()).isEqualTo(SettlementBatchStatus.POSTED);
            assertThat(result.getGrossAmount()).isEqualByComparingTo("3000.00");
            assertThat(result.getFeeAmount()).isEqualByComparingTo("60.0000");
            assertThat(result.getReserveAmount()).isEqualByComparingTo("30.0000");
            assertThat(result.getNetAmount()).isEqualByComparingTo("2910.0000");
            verify(itemRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("no captured payments → batch POSTED with zero totals")
        void runBatch_noPayments_zeroTotals() {
            when(paymentRepository.findByMerchantIdAndStatusAndCapturedAtBetween(
                    anyLong(), any(), any(), any()))
                    .thenReturn(List.of());

            SettlementBatch emptyBatch = SettlementBatch.builder()
                    .id(20L).merchantId(MERCHANT_ID).batchDate(DATE)
                    .gatewayName(GATEWAY_NAME)
                    .grossAmount(BigDecimal.ZERO)
                    .feeAmount(BigDecimal.ZERO)
                    .reserveAmount(BigDecimal.ZERO)
                    .netAmount(BigDecimal.ZERO)
                    .status(SettlementBatchStatus.POSTED).build();

            when(batchRepository.save(any())).thenReturn(emptyBatch);
            when(itemRepository.findByBatchId(20L)).thenReturn(List.of());

            SettlementBatchResponseDTO result = service.runBatch(MERCHANT_ID, DATE, GATEWAY_NAME);

            assertThat(result.getGrossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getItems()).isEmpty();
            verify(itemRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getBatch")
    class GetBatch {

        @Test
        @DisplayName("found → returns DTO with items")
        void getBatch_found() {
            SettlementBatch batch = SettlementBatch.builder()
                    .id(5L).merchantId(MERCHANT_ID).batchDate(DATE)
                    .gatewayName(GATEWAY_NAME).grossAmount(new BigDecimal("500"))
                    .feeAmount(new BigDecimal("10")).reserveAmount(new BigDecimal("5"))
                    .netAmount(new BigDecimal("485")).status(SettlementBatchStatus.POSTED).build();

            SettlementBatchItem item = SettlementBatchItem.builder()
                    .id(1L).batchId(5L).paymentId(100L)
                    .amount(new BigDecimal("500")).feeAmount(new BigDecimal("10"))
                    .reserveAmount(new BigDecimal("5")).netAmount(new BigDecimal("485")).build();

            when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
            when(itemRepository.findByBatchId(5L)).thenReturn(List.of(item));

            SettlementBatchResponseDTO result = service.getBatch(5L);

            assertThat(result.getId()).isEqualTo(5L);
            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getPaymentId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("not found → throws EntityNotFoundException")
        void getBatch_notFound() {
            when(batchRepository.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getBatch(99L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("listBatches")
    class ListBatches {

        @Test
        @DisplayName("returns page of batch DTOs")
        void listBatches_returnsDTOs() {
            SettlementBatch batch = SettlementBatch.builder()
                    .id(7L).merchantId(MERCHANT_ID).batchDate(DATE)
                    .gatewayName(GATEWAY_NAME).status(SettlementBatchStatus.POSTED).build();

            PageRequest pageable = PageRequest.of(0, 10);
            when(batchRepository.findByMerchantId(MERCHANT_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(batch)));

            var page = service.listBatches(MERCHANT_ID, pageable);

            assertThat(page.getTotalElements()).isEqualTo(1);
        }
    }
}
