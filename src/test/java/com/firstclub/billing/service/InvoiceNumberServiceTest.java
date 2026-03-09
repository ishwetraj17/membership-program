package com.firstclub.billing.service;

import com.firstclub.billing.entity.InvoiceSequence;
import com.firstclub.billing.repository.InvoiceSequenceRepository;
import com.firstclub.billing.service.impl.InvoiceNumberServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceNumberService — sequential generation")
class InvoiceNumberServiceTest {

    @Mock
    private InvoiceSequenceRepository sequenceRepository;

    private InvoiceNumberService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceNumberServiceImpl(sequenceRepository);
    }

    // ── Test 1: first invoice for a merchant creates the sequence ─────────────

    @Test
    @DisplayName("first call for new merchant: creates sequence, returns INV-000001")
    void generateFirst_createsSequenceAndReturnsFormattedNumber() {
        when(sequenceRepository.findByMerchantIdWithLock(1L)).thenReturn(Optional.empty());

        InvoiceSequence created = InvoiceSequence.builder()
                .merchantId(1L).currentNumber(0L).prefix("INV").updatedAt(LocalDateTime.now())
                .build();
        when(sequenceRepository.save(any())).thenReturn(created);

        String result = service.generateNextInvoiceNumber(1L);

        assertThat(result).isEqualTo("INV-000001");

        // Verify sequence was saved with currentNumber = 1
        ArgumentCaptor<InvoiceSequence> captor = ArgumentCaptor.forClass(InvoiceSequence.class);
        verify(sequenceRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(s -> s.getCurrentNumber() == 1L);
    }

    // ── Test 2: subsequent calls increment the counter ────────────────────────

    @Test
    @DisplayName("second call for existing merchant: increments to INV-000002")
    void generateSecond_incrementsExistingSequence() {
        InvoiceSequence existing = InvoiceSequence.builder()
                .merchantId(1L).currentNumber(1L).prefix("INV").updatedAt(LocalDateTime.now())
                .build();
        when(sequenceRepository.findByMerchantIdWithLock(1L)).thenReturn(Optional.of(existing));
        when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = service.generateNextInvoiceNumber(1L);

        assertThat(result).isEqualTo("INV-000002");
    }

    // ── Test 3: custom prefix ─────────────────────────────────────────────────

    @Test
    @DisplayName("sequence with custom prefix: format uses that prefix")
    void generateWithCustomPrefix_usesPrefix() {
        InvoiceSequence existing = InvoiceSequence.builder()
                .merchantId(2L).currentNumber(41L).prefix("FCM").updatedAt(LocalDateTime.now())
                .build();
        when(sequenceRepository.findByMerchantIdWithLock(2L)).thenReturn(Optional.of(existing));
        when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = service.generateNextInvoiceNumber(2L);

        assertThat(result).isEqualTo("FCM-000042");
    }

    // ── Test 4: zero-pads to 6 digits ────────────────────────────────────────

    @Test
    @DisplayName("large counter value: still zero-pads to 6 digits minimum")
    void generate_largCounter_zeropadToSixDigits() {
        InvoiceSequence existing = InvoiceSequence.builder()
                .merchantId(3L).currentNumber(999998L).prefix("INV").updatedAt(LocalDateTime.now())
                .build();
        when(sequenceRepository.findByMerchantIdWithLock(3L)).thenReturn(Optional.of(existing));
        when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = service.generateNextInvoiceNumber(3L);

        assertThat(result).isEqualTo("INV-999999");
    }

    // ── Test 5: initSequence creates row when absent ──────────────────────────

    @Test
    @DisplayName("initSequence on new merchant: saves sequence with given prefix")
    void initSequence_newMerchant_savesWithPrefix() {
        when(sequenceRepository.findByMerchantId(5L)).thenReturn(Optional.empty());
        when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initSequence(5L, "ACME");

        ArgumentCaptor<InvoiceSequence> captor = ArgumentCaptor.forClass(InvoiceSequence.class);
        verify(sequenceRepository).save(captor.capture());
        assertThat(captor.getValue().getPrefix()).isEqualTo("ACME");
        assertThat(captor.getValue().getMerchantId()).isEqualTo(5L);
    }

    // ── Test 6: initSequence updates prefix when sequence exists ─────────────

    @Test
    @DisplayName("initSequence on existing merchant: updates prefix")
    void initSequence_existingMerchant_updatesPrefix() {
        InvoiceSequence existing = InvoiceSequence.builder()
                .merchantId(5L).currentNumber(10L).prefix("OLD").updatedAt(LocalDateTime.now())
                .build();
        when(sequenceRepository.findByMerchantId(5L)).thenReturn(Optional.of(existing));
        when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initSequence(5L, "NEW");

        ArgumentCaptor<InvoiceSequence> captor = ArgumentCaptor.forClass(InvoiceSequence.class);
        verify(sequenceRepository).save(captor.capture());
        assertThat(captor.getValue().getPrefix()).isEqualTo("NEW");
        // current number must not be reset
        assertThat(captor.getValue().getCurrentNumber()).isEqualTo(10L);
    }
}
