package com.firstclub.ledger.service;

import com.firstclub.ledger.LedgerEntryFactory;
import com.firstclub.ledger.LedgerPostingPolicy;
import com.firstclub.ledger.dto.LedgerAccountBalanceDTO;
import com.firstclub.ledger.dto.LedgerEntryResponseDTO;
import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.*;
import com.firstclub.ledger.guard.ImmutableLedgerGuard;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LedgerService}.
 *
 * <p>Phase 10 update: validation logic moved to {@link LedgerPostingPolicy} (mocked here).
 * These tests target wiring, persistence delegation, and the new read methods added in Phase 10.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService Unit Tests")
class LedgerServiceTest {

    @Mock private LedgerAccountRepository accountRepository;
    @Mock private LedgerEntryRepository   entryRepository;
    @Mock private LedgerLineRepository    lineRepository;
    @Mock private LedgerPostingPolicy     postingPolicy;    // replaces inline MeterRegistry
    @Mock private ImmutableLedgerGuard    immutableGuard;
    @Mock private LedgerEntryFactory      entryFactory;

    @InjectMocks
    private LedgerService ledgerService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LedgerAccount pgClearing() {
        return LedgerAccount.builder().id(1L).name("PG_CLEARING")
                .accountType(LedgerAccount.AccountType.ASSET).currency("INR").build();
    }

    private static LedgerAccount subLiab() {
        return LedgerAccount.builder().id(2L).name("SUBSCRIPTION_LIABILITY")
                .accountType(LedgerAccount.AccountType.LIABILITY).currency("INR").build();
    }

    private static LedgerEntry savedEntry(Long id) {
        return LedgerEntry.builder().id(id)
                .entryType(LedgerEntryType.PAYMENT_CAPTURED)
                .referenceType(LedgerReferenceType.PAYMENT)
                .referenceId(99L).currency("INR").build();
    }

    // ── postEntry ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("postEntry")
    class PostEntry {

        @Test
        @DisplayName("balanced DR=CR: saves entry + 2 lines, delegates validation to policy")
        void balancedEntry_savesEntryAndLines() {
            when(entryRepository.save(any())).thenReturn(savedEntry(10L));
            when(accountRepository.findByName("PG_CLEARING")).thenReturn(Optional.of(pgClearing()));
            when(accountRepository.findByName("SUBSCRIPTION_LIABILITY")).thenReturn(Optional.of(subLiab()));

            LedgerEntry result = ledgerService.postEntry(
                    LedgerEntryType.PAYMENT_CAPTURED, LedgerReferenceType.PAYMENT, 99L, "INR",
                    List.of(
                            req("PG_CLEARING",            LineDirection.DEBIT,  "500.00"),
                            req("SUBSCRIPTION_LIABILITY", LineDirection.CREDIT, "500.00")
                    ));

            assertThat(result.getId()).isEqualTo(10L);
            verify(postingPolicy).validateLines(any());
            verify(immutableGuard).assertNewEntry(any());
            verify(entryRepository).save(any());
            verify(lineRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("policy throws LEDGER_UNBALANCED → propagated, no entry saved")
        void policyRejectsUnbalanced_noPersistence() {
            doThrow(new MembershipException("unbalanced", "LEDGER_UNBALANCED", HttpStatus.INTERNAL_SERVER_ERROR))
                    .when(postingPolicy).validateLines(any());

            assertThatThrownBy(() -> ledgerService.postEntry(
                    LedgerEntryType.PAYMENT_CAPTURED, LedgerReferenceType.PAYMENT, 1L, "INR",
                    List.of(req("A", LineDirection.DEBIT, "100.00"),
                            req("B", LineDirection.CREDIT, "80.00"))))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_UNBALANCED");

            verifyNoInteractions(entryRepository);
        }

        @Test
        @DisplayName("unknown account name → LEDGER_ACCOUNT_NOT_FOUND")
        void unknownAccount_throwsAccountNotFound() {
            when(entryRepository.save(any())).thenReturn(savedEntry(5L));
            when(accountRepository.findByName(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledgerService.postEntry(
                    LedgerEntryType.PAYMENT_CAPTURED, LedgerReferenceType.PAYMENT, 1L, "INR",
                    List.of(req("UNKNOWN",      LineDirection.DEBIT,  "100.00"),
                            req("ALSO_UNKNOWN", LineDirection.CREDIT, "100.00"))))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_ACCOUNT_NOT_FOUND");
        }

        @Test
        @DisplayName("guard throws LEDGER_IMMUTABLE for entry with existing ID → propagated")
        void guardRejectsExistingEntry_throwsImmutable() {
            doThrow(new MembershipException("immutable", "LEDGER_IMMUTABLE", HttpStatus.CONFLICT))
                    .when(immutableGuard).assertNewEntry(any());

            assertThatThrownBy(() -> ledgerService.postEntry(
                    LedgerEntryType.PAYMENT_CAPTURED, LedgerReferenceType.PAYMENT, 1L, "INR",
                    List.of(req("X", LineDirection.DEBIT, "100.00"),
                            req("Y", LineDirection.CREDIT, "100.00"))))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_IMMUTABLE");

            verify(entryRepository, never()).save(any());
        }
    }

    // ── getEntry ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getEntry")
    class GetEntry {

        @Test
        @DisplayName("returns DTO with lines when entry exists")
        void getEntry_found_returnsDtoWithLines() {
            LedgerEntry entry = savedEntry(42L);
            LedgerLine  line1 = LedgerLine.builder().id(1L).entryId(42L).accountId(1L)
                    .direction(LineDirection.DEBIT).amount(new BigDecimal("200.00")).build();
            LedgerLine  line2 = LedgerLine.builder().id(2L).entryId(42L).accountId(2L)
                    .direction(LineDirection.CREDIT).amount(new BigDecimal("200.00")).build();

            when(entryRepository.findById(42L)).thenReturn(Optional.of(entry));
            when(lineRepository.findByEntryId(42L)).thenReturn(List.of(line1, line2));
            when(accountRepository.findById(1L)).thenReturn(Optional.of(pgClearing()));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(subLiab()));

            LedgerEntryResponseDTO dto = ledgerService.getEntry(42L);

            assertThat(dto.getId()).isEqualTo(42L);
            assertThat(dto.getLines()).hasSize(2);
            assertThat(dto.getLines().get(0).getAccountName()).isEqualTo("PG_CLEARING");
        }

        @Test
        @DisplayName("LEDGER_ENTRY_NOT_FOUND when entry is missing")
        void getEntry_notFound_throwsNotFound() {
            when(entryRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledgerService.getEntry(999L))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("LEDGER_ENTRY_NOT_FOUND");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }

    // ── getAccountBalanceByCode ───────────────────────────────────────────────

    @Nested
    @DisplayName("getAccountBalanceByCode")
    class GetAccountBalance {

        @Test
        @DisplayName("sums lines for the requested account and returns correct balance")
        void getAccountBalance_returnsCorrectBalance() {
            LedgerAccount pg = pgClearing(); // id=1, ASSET → normal balance = DR-CR

            LedgerLine dr1 = LedgerLine.builder().id(1L).entryId(10L).accountId(1L)
                    .direction(LineDirection.DEBIT).amount(new BigDecimal("700.00")).build();
            LedgerLine cr1 = LedgerLine.builder().id(2L).entryId(11L).accountId(1L)
                    .direction(LineDirection.CREDIT).amount(new BigDecimal("200.00")).build();
            LedgerLine otherAcct = LedgerLine.builder().id(3L).entryId(10L).accountId(2L)
                    .direction(LineDirection.CREDIT).amount(new BigDecimal("700.00")).build();

            when(accountRepository.findByName("PG_CLEARING")).thenReturn(Optional.of(pg));
            when(lineRepository.findAll()).thenReturn(List.of(dr1, cr1, otherAcct));

            LedgerAccountBalanceDTO dto = ledgerService.getAccountBalanceByCode("PG_CLEARING");

            assertThat(dto.getAccountName()).isEqualTo("PG_CLEARING");
            assertThat(dto.getDebitTotal()).isEqualByComparingTo("700.00");
            assertThat(dto.getCreditTotal()).isEqualByComparingTo("200.00");
            assertThat(dto.getBalance()).isEqualByComparingTo("500.00"); // ASSET → DR-CR
        }

        @Test
        @DisplayName("LEDGER_ACCOUNT_NOT_FOUND when account code unknown")
        void getAccountBalance_notFound_throws() {
            when(accountRepository.findByName("UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledgerService.getAccountBalanceByCode("UNKNOWN"))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("LEDGER_ACCOUNT_NOT_FOUND");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }

    // ── postReversalEntry ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("postReversalEntry")
    class PostReversalEntry {

        private LedgerEntry originalEntry() {
            return LedgerEntry.builder().id(5L)
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED)
                    .referenceType(LedgerReferenceType.PAYMENT)
                    .referenceId(99L).currency("INR").build();
        }

        private List<LedgerLineRequest> flippedLines() {
            return List.of(
                    req("SUBSCRIPTION_LIABILITY", LineDirection.DEBIT,  "500.00"),
                    req("PG_CLEARING",            LineDirection.CREDIT, "500.00"));
        }

        @Test
        @DisplayName("posts REVERSAL entry with flipped lines and sets reversalOfEntryId")
        void postReversalEntry_savesReversalAndLines() {
            LedgerEntry original = originalEntry();
            LedgerEntry builtReversal = LedgerEntry.builder()
                    .entryType(LedgerEntryType.REVERSAL)
                    .reversalOfEntryId(5L).reversalReason("data error")
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(99L)
                    .currency("INR").build();
            LedgerEntry savedReversal = LedgerEntry.builder().id(20L)
                    .entryType(LedgerEntryType.REVERSAL).reversalOfEntryId(5L)
                    .reversalReason("data error").referenceType(LedgerReferenceType.PAYMENT)
                    .referenceId(99L).currency("INR").build();

            when(entryFactory.buildReversalEntry(original, 5L, "data error", null))
                    .thenReturn(builtReversal);
            when(entryRepository.save(builtReversal)).thenReturn(savedReversal);
            when(accountRepository.findByName("SUBSCRIPTION_LIABILITY")).thenReturn(Optional.of(subLiab()));
            when(accountRepository.findByName("PG_CLEARING")).thenReturn(Optional.of(pgClearing()));
            when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            LedgerEntry result = ledgerService.postReversalEntry(
                    original, flippedLines(), "data error", null);

            assertThat(result.getId()).isEqualTo(20L);
            assertThat(result.getEntryType()).isEqualTo(LedgerEntryType.REVERSAL);
            assertThat(result.getReversalOfEntryId()).isEqualTo(5L);
            verify(postingPolicy).validateLines(flippedLines());
            verify(immutableGuard).assertNewEntry(builtReversal);
            verify(entryRepository).save(builtReversal);
            verify(lineRepository, times(2)).save(any(LedgerLine.class));
        }

        @Test
        @DisplayName("delegates line validation to postingPolicy before persisting")
        void postReversalEntry_validatesLinesFirst() {
            LedgerEntry original = originalEntry();
            doThrow(new MembershipException("unbalanced", "LEDGER_UNBALANCED", HttpStatus.INTERNAL_SERVER_ERROR))
                    .when(postingPolicy).validateLines(any());

            assertThatThrownBy(() -> ledgerService.postReversalEntry(
                    original, flippedLines(), "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_UNBALANCED");

            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("postedByUserId is passed through to LedgerEntryFactory")
        void postReversalEntry_postedByUserIdPassedToFactory() {
            LedgerEntry original = originalEntry();
            LedgerEntry builtReversal = LedgerEntry.builder()
                    .entryType(LedgerEntryType.REVERSAL).build();
            LedgerEntry savedReversal = LedgerEntry.builder().id(30L)
                    .entryType(LedgerEntryType.REVERSAL).build();

            when(entryFactory.buildReversalEntry(original, 5L, "admin fix", 42L))
                    .thenReturn(builtReversal);
            when(entryRepository.save(builtReversal)).thenReturn(savedReversal);
            when(accountRepository.findByName(anyString())).thenReturn(Optional.of(pgClearing()));
            when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ledgerService.postReversalEntry(original, flippedLines(), "admin fix", 42L);

            verify(entryFactory).buildReversalEntry(original, 5L, "admin fix", 42L);
        }

        @Test
        @DisplayName("immutableGuard.assertNewEntry blocks already-persisted reversal entry")
        void postReversalEntry_guardRejectsPersistedEntry() {
            LedgerEntry original = originalEntry();
            LedgerEntry alreadyPersisted = LedgerEntry.builder().id(99L)
                    .entryType(LedgerEntryType.REVERSAL).build();

            when(entryFactory.buildReversalEntry(any(), any(), any(), any()))
                    .thenReturn(alreadyPersisted);
            doThrow(new MembershipException("immutable", "LEDGER_IMMUTABLE", HttpStatus.CONFLICT))
                    .when(immutableGuard).assertNewEntry(alreadyPersisted);

            assertThatThrownBy(() -> ledgerService.postReversalEntry(
                    original, flippedLines(), "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_IMMUTABLE");

            verify(entryRepository, never()).save(any());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LedgerLineRequest req(String account, LineDirection direction, String amount) {
        return LedgerLineRequest.builder()
                .accountName(account).direction(direction)
                .amount(new BigDecimal(amount)).build();
    }
}
