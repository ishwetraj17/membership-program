package com.firstclub.reporting.projections;

import com.firstclub.ledger.dto.LedgerAccountBalanceDTO;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.reporting.projections.dto.LedgerBalanceSnapshotDTO;
import com.firstclub.reporting.projections.entity.LedgerBalanceSnapshot;
import com.firstclub.reporting.projections.repository.LedgerBalanceSnapshotRepository;
import com.firstclub.reporting.projections.service.LedgerSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LedgerSnapshotService}.
 * No Spring context — pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerSnapshotService — Unit Tests")
class LedgerSnapshotServiceTest {

    @Mock private LedgerService                   ledgerService;
    @Mock private LedgerBalanceSnapshotRepository snapshotRepository;

    @InjectMocks
    private LedgerSnapshotService service;

    private static final LocalDate TEST_DATE = LocalDate.of(2025, 1, 15);

    // ── generateSnapshotForDate ──────────────────────────────────────────────

    @Test
    @DisplayName("generateSnapshotForDate — creates one snapshot per account when none exist")
    void generateSnapshot_createsSnapshotPerAccount() {
        List<LedgerAccountBalanceDTO> balances = List.of(
                LedgerAccountBalanceDTO.builder()
                        .accountId(1L).accountName("Assets").balance(new BigDecimal("5000.00")).build(),
                LedgerAccountBalanceDTO.builder()
                        .accountId(2L).accountName("Revenue").balance(new BigDecimal("3000.00")).build()
        );
        when(ledgerService.getBalances()).thenReturn(balances);
        when(snapshotRepository.existsByAccountIdAndSnapshotDateAndMerchantIdIsNull(anyLong(), eq(TEST_DATE)))
                .thenReturn(false);
        when(snapshotRepository.save(any())).thenAnswer(inv -> {
            LedgerBalanceSnapshot s = inv.getArgument(0);
            return LedgerBalanceSnapshot.builder()
                    .id(System.nanoTime())
                    .accountId(s.getAccountId())
                    .snapshotDate(s.getSnapshotDate())
                    .balance(s.getBalance())
                    .build();
        });

        List<LedgerBalanceSnapshotDTO> results = service.generateSnapshotForDate(TEST_DATE);

        assertThat(results).hasSize(2);
        verify(snapshotRepository, times(2)).save(any());
        assertThat(results).extracting(LedgerBalanceSnapshotDTO::getAccountId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("generateSnapshotForDate — idempotent: existing snapshots not duplicated")
    void generateSnapshot_idempotent_doesNotDuplicate() {
        LedgerAccountBalanceDTO balance = LedgerAccountBalanceDTO.builder()
                .accountId(1L).accountName("Assets").balance(new BigDecimal("5000.00")).build();
        when(ledgerService.getBalances()).thenReturn(List.of(balance));

        // Account 1 snapshot already exists
        when(snapshotRepository.existsByAccountIdAndSnapshotDateAndMerchantIdIsNull(1L, TEST_DATE))
                .thenReturn(true);
        LedgerBalanceSnapshot existing = LedgerBalanceSnapshot.builder()
                .id(99L).accountId(1L).snapshotDate(TEST_DATE)
                .balance(new BigDecimal("5000.00")).build();
        when(snapshotRepository.findByAccountIdAndSnapshotDateAndMerchantIdIsNull(1L, TEST_DATE))
                .thenReturn(Optional.of(existing));

        List<LedgerBalanceSnapshotDTO> results = service.generateSnapshotForDate(TEST_DATE);

        assertThat(results).hasSize(1);
        verify(snapshotRepository, never()).save(any()); // No new row written
        assertThat(results.get(0).getId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("generateSnapshotForDate — accounts without accountId are skipped")
    void generateSnapshot_skipsAccountsWithNullId() {
        LedgerAccountBalanceDTO noId = LedgerAccountBalanceDTO.builder()
                .accountId(null).accountName("Ghost").balance(BigDecimal.ZERO).build();
        when(ledgerService.getBalances()).thenReturn(List.of(noId));

        List<LedgerBalanceSnapshotDTO> results = service.generateSnapshotForDate(TEST_DATE);

        assertThat(results).isEmpty();
        verify(snapshotRepository, never()).save(any());
    }

    // ── getSnapshots ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSnapshots — delegates to repository with correct params")
    void getSnapshots_callsRepoWithCorrectParams() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to   = LocalDate.of(2025, 1, 31);
        LedgerBalanceSnapshot snap = LedgerBalanceSnapshot.builder()
                .id(1L).accountId(1L).snapshotDate(from).balance(BigDecimal.TEN).build();
        when(snapshotRepository.findWithFilters(null, from, to)).thenReturn(List.of(snap));

        List<LedgerBalanceSnapshotDTO> results = service.getSnapshots(from, to, null);

        verify(snapshotRepository).findWithFilters(null, from, to);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getBalance()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("getSnapshots — all-null params returns all snapshots")
    void getSnapshots_allNullParams_returnsAll() {
        when(snapshotRepository.findWithFilters(null, null, null)).thenReturn(List.of());

        List<LedgerBalanceSnapshotDTO> results = service.getSnapshots(null, null, null);

        verify(snapshotRepository).findWithFilters(null, null, null);
        assertThat(results).isEmpty();
    }
}
