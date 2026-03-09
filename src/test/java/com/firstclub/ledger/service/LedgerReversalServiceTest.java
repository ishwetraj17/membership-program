package com.firstclub.ledger.service;

import com.firstclub.ledger.LedgerEntryFactory;
import com.firstclub.ledger.LedgerPostingPolicy;
import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.*;
import com.firstclub.ledger.guard.ImmutableLedgerGuard;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.ledger.reversal.LedgerReversalServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 10 — Unit tests for the reversal-only correction model.
 *
 * <p>Covers four components without any database/Docker dependency:
 * <ol>
 *   <li>{@link LedgerReversalServiceImpl} — orchestration logic</li>
 *   <li>{@link ImmutableLedgerGuard} — service-layer immutability guard</li>
 *   <li>{@link LedgerPostingPolicy} — validation rules for posting + reversal</li>
 *   <li>{@link LedgerEntryFactory} — builder utilities for reversal lines</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 10 — Reversal / Guard / Policy / Factory unit tests")
class LedgerReversalServiceTest {

    // ── Shared fixtures ───────────────────────────────────────────────────────

    static LedgerAccount pgClearingAccount() {
        return LedgerAccount.builder().id(1L).name("PG_CLEARING")
                .accountType(LedgerAccount.AccountType.ASSET).currency("INR").build();
    }

    static LedgerAccount subLiabAccount() {
        return LedgerAccount.builder().id(2L).name("SUBSCRIPTION_LIABILITY")
                .accountType(LedgerAccount.AccountType.LIABILITY).currency("INR").build();
    }

    static LedgerEntry paymentCapturedEntry(Long id) {
        return LedgerEntry.builder()
                .id(id)
                .entryType(LedgerEntryType.PAYMENT_CAPTURED)
                .referenceType(LedgerReferenceType.PAYMENT)
                .referenceId(99L)
                .currency("INR")
                .build();
    }

    static List<LedgerLine> twoLines(Long entryId) {
        return List.of(
                LedgerLine.builder().id(10L).entryId(entryId).accountId(1L)
                        .direction(LineDirection.DEBIT).amount(new BigDecimal("500.00")).build(),
                LedgerLine.builder().id(11L).entryId(entryId).accountId(2L)
                        .direction(LineDirection.CREDIT).amount(new BigDecimal("500.00")).build()
        );
    }

    // =========================================================================
    // 1. LedgerReversalServiceImpl
    // =========================================================================

    @Nested
    @DisplayName("LedgerReversalServiceImpl")
    class ReversalServiceTests {

        @Mock private LedgerEntryRepository   entryRepository;
        @Mock private LedgerLineRepository    lineRepository;
        @Mock private LedgerAccountRepository accountRepository;
        @Mock private LedgerPostingPolicy     postingPolicy;
        @Mock private LedgerEntryFactory      entryFactory;
        @Mock private LedgerService           ledgerService;

        @InjectMocks
        private LedgerReversalServiceImpl reversalService;

        @Test
        @DisplayName("reverse_success: posts REVERSAL entry with flipped lines, returns saved reversal")
        void reverse_success_createsReversalEntry() {
            LedgerEntry original = paymentCapturedEntry(42L);
            List<LedgerLine> lines = twoLines(42L);

            LedgerLineRequest flippedDebit  = LedgerLineRequest.builder()
                    .accountName("SUBSCRIPTION_LIABILITY")
                    .direction(LineDirection.DEBIT).amount(new BigDecimal("500.00")).build();
            LedgerLineRequest flippedCredit = LedgerLineRequest.builder()
                    .accountName("PG_CLEARING")
                    .direction(LineDirection.CREDIT).amount(new BigDecimal("500.00")).build();
            List<LedgerLineRequest> flipped = List.of(flippedDebit, flippedCredit);

            LedgerEntry savedReversal = LedgerEntry.builder().id(99L)
                    .entryType(LedgerEntryType.REVERSAL).reversalOfEntryId(42L).build();

            when(entryRepository.findById(42L)).thenReturn(Optional.of(original));
            when(entryRepository.existsByReversalOfEntryId(42L)).thenReturn(false);
            when(lineRepository.findByEntryId(42L)).thenReturn(lines);
            when(accountRepository.findById(1L)).thenReturn(Optional.of(pgClearingAccount()));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(subLiabAccount()));
            when(entryFactory.buildReversalLines(eq(lines), any())).thenReturn(flipped);
            when(ledgerService.postReversalEntry(eq(original), eq(flipped), eq("Test reason"), isNull()))
                    .thenReturn(savedReversal);

            LedgerEntry result = reversalService.reverse(42L, "Test reason", null);

            assertThat(result.getId()).isEqualTo(99L);
            assertThat(result.getEntryType()).isEqualTo(LedgerEntryType.REVERSAL);
            verify(postingPolicy).validateReversal(original, "Test reason", false);
            verify(ledgerService).postReversalEntry(original, flipped, "Test reason", null);
        }

        @Test
        @DisplayName("reverse_success: postedByUserId is forwarded to ledgerService")
        void reverse_postedByUserIdForwarded() {
            LedgerEntry original = paymentCapturedEntry(10L);
            List<LedgerLine> lines = twoLines(10L);
            List<LedgerLineRequest> flipped = List.of(
                    LedgerLineRequest.builder().accountName("X")
                            .direction(LineDirection.CREDIT).amount(new BigDecimal("100.00")).build(),
                    LedgerLineRequest.builder().accountName("Y")
                            .direction(LineDirection.DEBIT).amount(new BigDecimal("100.00")).build());
            LedgerEntry savedReversal = LedgerEntry.builder().id(20L)
                    .entryType(LedgerEntryType.REVERSAL).build();

            when(entryRepository.findById(10L)).thenReturn(Optional.of(original));
            when(entryRepository.existsByReversalOfEntryId(10L)).thenReturn(false);
            when(lineRepository.findByEntryId(10L)).thenReturn(lines);
            when(accountRepository.findById(1L)).thenReturn(Optional.of(pgClearingAccount()));
            when(accountRepository.findById(2L)).thenReturn(Optional.of(subLiabAccount()));
            when(entryFactory.buildReversalLines(any(), any())).thenReturn(flipped);
            when(ledgerService.postReversalEntry(any(), any(), any(), eq(777L))).thenReturn(savedReversal);

            reversalService.reverse(10L, "operator correction", 777L);

            verify(ledgerService).postReversalEntry(original, flipped, "operator correction", 777L);
        }

        @Test
        @DisplayName("reverse_entryNotFound: throws LEDGER_ENTRY_NOT_FOUND 404")
        void reverse_entryNotFound_throwsNotFound() {
            when(entryRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reversalService.reverse(999L, "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("LEDGER_ENTRY_NOT_FOUND");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });

            verify(lineRepository, never()).findByEntryId(any());
            verify(ledgerService, never()).postReversalEntry(any(), any(), any(), any());
        }

        @Test
        @DisplayName("reverse_alreadyReversed: existsByReversalOfEntryId=true → postingPolicy throws REVERSAL_ALREADY_EXISTS")
        void reverse_alreadyReversed_throwsConflict() {
            LedgerEntry original = paymentCapturedEntry(42L);
            when(entryRepository.findById(42L)).thenReturn(Optional.of(original));
            when(entryRepository.existsByReversalOfEntryId(42L)).thenReturn(true);
            doThrow(new MembershipException("already reversed", "REVERSAL_ALREADY_EXISTS", HttpStatus.CONFLICT))
                    .when(postingPolicy).validateReversal(any(), any(), eq(true));

            assertThatThrownBy(() -> reversalService.reverse(42L, "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("REVERSAL_ALREADY_EXISTS");

            verify(lineRepository, never()).findByEntryId(any());
        }

        @Test
        @DisplayName("reverse_reversalEntry: postingPolicy throws CANNOT_REVERSE_REVERSAL")
        void reverse_reversalEntry_throwsCannotReverseReversal() {
            LedgerEntry reversalEntry = LedgerEntry.builder()
                    .id(42L).entryType(LedgerEntryType.REVERSAL)
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(1L)
                    .currency("INR").build();

            when(entryRepository.findById(42L)).thenReturn(Optional.of(reversalEntry));
            when(entryRepository.existsByReversalOfEntryId(42L)).thenReturn(false);
            doThrow(new MembershipException("cannot reverse reversal",
                            "CANNOT_REVERSE_REVERSAL", HttpStatus.UNPROCESSABLE_ENTITY))
                    .when(postingPolicy).validateReversal(eq(reversalEntry), any(), eq(false));

            assertThatThrownBy(() -> reversalService.reverse(42L, "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("CANNOT_REVERSE_REVERSAL");
        }

        @Test
        @DisplayName("reverse_emptyOriginalLines: throws LEDGER_ENTRY_HAS_NO_LINES 500")
        void reverse_emptyOriginalLines_throwsInternalError() {
            LedgerEntry original = paymentCapturedEntry(42L);
            when(entryRepository.findById(42L)).thenReturn(Optional.of(original));
            when(entryRepository.existsByReversalOfEntryId(42L)).thenReturn(false);
            when(lineRepository.findByEntryId(42L)).thenReturn(List.of());

            assertThatThrownBy(() -> reversalService.reverse(42L, "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("LEDGER_ENTRY_HAS_NO_LINES");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    });

            verify(ledgerService, never()).postReversalEntry(any(), any(), any(), any());
        }

        @Test
        @DisplayName("reverse_missingAccount: propagates LEDGER_ACCOUNT_NOT_FOUND from accountRepository")
        void reverse_missingAccount_throwsAccountNotFound() {
            LedgerEntry original = paymentCapturedEntry(42L);
            when(entryRepository.findById(42L)).thenReturn(Optional.of(original));
            when(entryRepository.existsByReversalOfEntryId(42L)).thenReturn(false);
            when(lineRepository.findByEntryId(42L)).thenReturn(twoLines(42L));
            when(accountRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reversalService.reverse(42L, "reason", null))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_ACCOUNT_NOT_FOUND");
        }
    }

    // =========================================================================
    // 2. ImmutableLedgerGuard
    // =========================================================================

    @Nested
    @DisplayName("ImmutableLedgerGuard")
    class GuardTests {

        private final ImmutableLedgerGuard guard = new ImmutableLedgerGuard();

        @Test
        @DisplayName("assertNewEntry passes for entity with null id")
        void assertNewEntry_nullId_passes() {
            LedgerEntry entry = LedgerEntry.builder()
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED).build();

            assertThatNoException().isThrownBy(() -> guard.assertNewEntry(entry));
        }

        @Test
        @DisplayName("assertNewEntry throws LEDGER_IMMUTABLE 409 for entity with non-null id")
        void assertNewEntry_existingId_throwsImmutable() {
            LedgerEntry entry = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED).build();

            assertThatThrownBy(() -> guard.assertNewEntry(entry))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("LEDGER_IMMUTABLE");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("assertNewLine passes for line with null id")
        void assertNewLine_nullId_passes() {
            LedgerLine line = LedgerLine.builder().entryId(1L).accountId(1L)
                    .direction(LineDirection.DEBIT).amount(BigDecimal.TEN).build();

            assertThatNoException().isThrownBy(() -> guard.assertNewLine(line));
        }

        @Test
        @DisplayName("assertNewLine throws LEDGER_IMMUTABLE 409 for line with non-null id")
        void assertNewLine_existingId_throwsImmutable() {
            LedgerLine line = LedgerLine.builder().id(99L).entryId(1L).accountId(1L)
                    .direction(LineDirection.DEBIT).amount(BigDecimal.TEN).build();

            assertThatThrownBy(() -> guard.assertNewLine(line))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("LEDGER_IMMUTABLE");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
    }

    // =========================================================================
    // 3. LedgerPostingPolicy
    // =========================================================================

    @Nested
    @DisplayName("LedgerPostingPolicy")
    class PostingPolicyTests {

        @Mock private MeterRegistry meterRegistry;
        @Mock private Counter       counter;

        @InjectMocks
        private LedgerPostingPolicy policy;

        @BeforeEach
        void setUp() {
            when(meterRegistry.counter("ledger_unbalanced_total")).thenReturn(counter);
            policy.init();
        }

        // ── validateLines ─────────────────────────────────────────────────────

        @Test
        @DisplayName("validateLines: balanced DR=CR passes without exception")
        void validateLines_balanced_passes() {
            assertThatNoException().isThrownBy(() -> policy.validateLines(List.of(
                    req("A", LineDirection.DEBIT,  "500.00"),
                    req("B", LineDirection.CREDIT, "500.00")
            )));
            verifyNoInteractions(counter);
        }

        @Test
        @DisplayName("validateLines: empty list → LEDGER_UNBALANCED + counter incremented")
        void validateLines_empty_throwsUnbalancedAndIncrements() {
            assertThatThrownBy(() -> policy.validateLines(List.of()))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_UNBALANCED");

            verify(counter).increment();
        }

        @Test
        @DisplayName("validateLines: null list → LEDGER_UNBALANCED + counter incremented")
        void validateLines_null_throwsUnbalancedAndIncrements() {
            assertThatThrownBy(() -> policy.validateLines(null))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_UNBALANCED");

            verify(counter).increment();
        }

        @Test
        @DisplayName("validateLines: DR != CR → LEDGER_UNBALANCED + counter incremented")
        void validateLines_unbalanced_throwsAndIncrements() {
            assertThatThrownBy(() -> policy.validateLines(List.of(
                    req("A", LineDirection.DEBIT,  "100.00"),
                    req("B", LineDirection.CREDIT, "80.00")
            )))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_UNBALANCED");

            verify(counter).increment();
        }

        @Test
        @DisplayName("validateLines: single DR only → LEDGER_UNBALANCED + counter incremented")
        void validateLines_singleDebitLine_throwsAndIncrements() {
            assertThatThrownBy(() -> policy.validateLines(List.of(
                    req("A", LineDirection.DEBIT, "200.00")
            )))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_UNBALANCED");

            verify(counter).increment();
        }

        @Test
        @DisplayName("validateLines: zero amount → LEDGER_INVALID_AMOUNT (no counter increment)")
        void validateLines_zeroAmount_throwsInvalidAmount() {
            assertThatThrownBy(() -> policy.validateLines(List.of(
                    req("A", LineDirection.DEBIT,  "0.00"),
                    req("B", LineDirection.CREDIT, "0.00")
            )))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_INVALID_AMOUNT");

            verifyNoInteractions(counter);
        }

        @Test
        @DisplayName("validateLines: negative amount → LEDGER_INVALID_AMOUNT")
        void validateLines_negativeAmount_throwsInvalidAmount() {
            assertThatThrownBy(() -> policy.validateLines(List.of(
                    req("A", LineDirection.DEBIT,  "-50.00"),
                    req("B", LineDirection.CREDIT, "-50.00")
            )))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("LEDGER_INVALID_AMOUNT");
        }

        // ── validateReversal ──────────────────────────────────────────────────

        @Test
        @DisplayName("validateReversal: valid reason, not already reversed, not a REVERSAL → passes")
        void validateReversal_valid_passes() {
            LedgerEntry entry = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED).build();

            assertThatNoException().isThrownBy(() -> policy.validateReversal(entry, "data entry error", false));
        }

        @Test
        @DisplayName("validateReversal: blank reason → REVERSAL_REASON_REQUIRED 422")
        void validateReversal_blankReason_throwsReasonRequired() {
            LedgerEntry entry = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED).build();

            assertThatThrownBy(() -> policy.validateReversal(entry, "   ", false))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("REVERSAL_REASON_REQUIRED");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        @DisplayName("validateReversal: null reason → REVERSAL_REASON_REQUIRED 422")
        void validateReversal_nullReason_throwsReasonRequired() {
            LedgerEntry entry = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED).build();

            assertThatThrownBy(() -> policy.validateReversal(entry, null, false))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("REVERSAL_REASON_REQUIRED");
        }

        @Test
        @DisplayName("validateReversal: alreadyReversed=true → REVERSAL_ALREADY_EXISTS 409")
        void validateReversal_alreadyReversed_throwsConflict() {
            LedgerEntry entry = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.PAYMENT_CAPTURED).build();

            assertThatThrownBy(() -> policy.validateReversal(entry, "valid reason", true))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("REVERSAL_ALREADY_EXISTS");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("validateReversal: REVERSAL entry type → CANNOT_REVERSE_REVERSAL 422")
        void validateReversal_reversalEntry_throwsCannotReverseReversal() {
            LedgerEntry reversal = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.REVERSAL).build();

            assertThatThrownBy(() -> policy.validateReversal(reversal, "valid reason", false))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("CANNOT_REVERSE_REVERSAL");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
        }

        @Test
        @DisplayName("validateReversal: REVERSAL type checked BEFORE alreadyReversed")
        void validateReversal_reversalEntryCheckedFirst() {
            // When entry is REVERSAL AND alreadyReversed=true, CANNOT_REVERSE_REVERSAL wins
            LedgerEntry reversal = LedgerEntry.builder().id(1L)
                    .entryType(LedgerEntryType.REVERSAL).build();

            assertThatThrownBy(() -> policy.validateReversal(reversal, "reason", true))
                    .isInstanceOf(MembershipException.class)
                    .extracting(ex -> ((MembershipException) ex).getErrorCode())
                    .isEqualTo("CANNOT_REVERSE_REVERSAL");
        }

        private LedgerLineRequest req(String account, LineDirection dir, String amount) {
            return LedgerLineRequest.builder()
                    .accountName(account).direction(dir)
                    .amount(new BigDecimal(amount)).build();
        }
    }

    // =========================================================================
    // 4. LedgerEntryFactory
    // =========================================================================

    @Nested
    @DisplayName("LedgerEntryFactory")
    class FactoryTests {

        private final LedgerEntryFactory factory = new LedgerEntryFactory();

        @Test
        @DisplayName("line() builds a LedgerLineRequest with correct fields")
        void line_buildsCorrectRequest() {
            LedgerLineRequest req = factory.line("PG_CLEARING", LineDirection.DEBIT, new BigDecimal("300.00"));

            assertThat(req.getAccountName()).isEqualTo("PG_CLEARING");
            assertThat(req.getDirection()).isEqualTo(LineDirection.DEBIT);
            assertThat(req.getAmount()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("debit() convenience method sets DEBIT direction")
        void debit_setsDebitDirection() {
            assertThat(factory.debit("X", BigDecimal.ONE).getDirection()).isEqualTo(LineDirection.DEBIT);
        }

        @Test
        @DisplayName("credit() convenience method sets CREDIT direction")
        void credit_setsCreditDirection() {
            assertThat(factory.credit("X", BigDecimal.ONE).getDirection()).isEqualTo(LineDirection.CREDIT);
        }

        @Test
        @DisplayName("buildReversalLines: flips DEBIT→CREDIT and CREDIT→DEBIT, preserves amounts and account names")
        void buildReversalLines_flipsDirectionsAndPreservesAmounts() {
            List<LedgerLine> origLines = List.of(
                    LedgerLine.builder().id(1L).entryId(10L).accountId(1L)
                            .direction(LineDirection.DEBIT).amount(new BigDecimal("300.00")).build(),
                    LedgerLine.builder().id(2L).entryId(10L).accountId(2L)
                            .direction(LineDirection.CREDIT).amount(new BigDecimal("300.00")).build()
            );
            Map<Long, String> nameMap = Map.of(1L, "PG_CLEARING", 2L, "SUBSCRIPTION_LIABILITY");

            List<LedgerLineRequest> flipped = factory.buildReversalLines(origLines, nameMap);

            assertThat(flipped).hasSize(2);
            // Original DEBIT → flipped CREDIT
            assertThat(flipped.get(0).getDirection()).isEqualTo(LineDirection.CREDIT);
            assertThat(flipped.get(0).getAccountName()).isEqualTo("PG_CLEARING");
            assertThat(flipped.get(0).getAmount()).isEqualByComparingTo("300.00");
            // Original CREDIT → flipped DEBIT
            assertThat(flipped.get(1).getDirection()).isEqualTo(LineDirection.DEBIT);
            assertThat(flipped.get(1).getAccountName()).isEqualTo("SUBSCRIPTION_LIABILITY");
            assertThat(flipped.get(1).getAmount()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("buildReversalLines: resulting lines are balanced (DR total == CR total)")
        void buildReversalLines_resultIsBalanced() {
            List<LedgerLine> origLines = List.of(
                    LedgerLine.builder().entryId(10L).accountId(1L)
                            .direction(LineDirection.DEBIT).amount(new BigDecimal("500.00")).build(),
                    LedgerLine.builder().entryId(10L).accountId(2L)
                            .direction(LineDirection.CREDIT).amount(new BigDecimal("500.00")).build()
            );
            Map<Long, String> nameMap = Map.of(1L, "A", 2L, "B");
            List<LedgerLineRequest> flipped = factory.buildReversalLines(origLines, nameMap);

            BigDecimal drTotal = flipped.stream()
                    .filter(r -> r.getDirection() == LineDirection.DEBIT)
                    .map(LedgerLineRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal crTotal = flipped.stream()
                    .filter(r -> r.getDirection() == LineDirection.CREDIT)
                    .map(LedgerLineRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(drTotal).isEqualByComparingTo(crTotal);
        }

        @Test
        @DisplayName("original + reversal lines net to zero per account")
        void originalAndReversalLinesNetToZeroPerAccount() {
            BigDecimal amount = new BigDecimal("500.00");
            List<LedgerLine> origLines = List.of(
                    LedgerLine.builder().entryId(10L).accountId(1L)
                            .direction(LineDirection.DEBIT).amount(amount).build(),
                    LedgerLine.builder().entryId(10L).accountId(2L)
                            .direction(LineDirection.CREDIT).amount(amount).build()
            );
            Map<Long, String> nameMap = Map.of(1L, "PG_CLEARING", 2L, "SUBSCRIPTION_LIABILITY");
            List<LedgerLineRequest> flipped = factory.buildReversalLines(origLines, nameMap);

            // For PG_CLEARING: original DR +500, reversal CR -500 = net 0
            BigDecimal pgNet = origLines.stream()
                    .filter(l -> l.getAccountId().equals(1L))
                    .map(l -> l.getDirection() == LineDirection.DEBIT ? l.getAmount() : l.getAmount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(flipped.stream()
                            .filter(r -> r.getAccountName().equals("PG_CLEARING"))
                            .map(r -> r.getDirection() == LineDirection.DEBIT ? r.getAmount() : r.getAmount().negate())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));

            assertThat(pgNet).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("buildReversalEntry: sets REVERSAL type, reversalOfEntryId, reason, postedByUserId, no id")
        void buildReversalEntry_setsAllFieldsCorrectly() {
            LedgerEntry original = LedgerEntry.builder()
                    .id(5L).entryType(LedgerEntryType.PAYMENT_CAPTURED)
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(99L)
                    .currency("INR").build();

            LedgerEntry reversal = factory.buildReversalEntry(original, 5L, "error correction", 7L);

            assertThat(reversal.getId()).isNull();  // not yet persisted
            assertThat(reversal.getEntryType()).isEqualTo(LedgerEntryType.REVERSAL);
            assertThat(reversal.getReversalOfEntryId()).isEqualTo(5L);
            assertThat(reversal.getReversalReason()).isEqualTo("error correction");
            assertThat(reversal.getPostedByUserId()).isEqualTo(7L);
            assertThat(reversal.getReferenceType()).isEqualTo(LedgerReferenceType.PAYMENT);
            assertThat(reversal.getReferenceId()).isEqualTo(99L);
            assertThat(reversal.getCurrency()).isEqualTo("INR");
        }

        @Test
        @DisplayName("buildReversalEntry: null postedByUserId is preserved (optional field)")
        void buildReversalEntry_nullPostedByUserId_preserved() {
            LedgerEntry original = LedgerEntry.builder()
                    .id(1L).entryType(LedgerEntryType.PAYMENT_CAPTURED)
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(1L)
                    .currency("INR").build();

            LedgerEntry reversal = factory.buildReversalEntry(original, 1L, "correction", null);

            assertThat(reversal.getPostedByUserId()).isNull();
        }
    }
}
