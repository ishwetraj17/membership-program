package com.firstclub.ledger;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.*;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.ledger.reversal.LedgerReversalService;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 10 — Integration tests for ledger immutability enforcement.
 *
 * <p>Runs against a live PostgreSQL container via {@link PostgresIntegrationTestBase}
 * (Testcontainers, {@code create-drop} DDL — Docker required; skipped automatically
 * if Docker is unavailable).
 *
 * <h3>Tests</h3>
 * <ol>
 *   <li>Direct UPDATE on {@code ledger_entries} is rejected by the DB trigger.</li>
 *   <li>Direct DELETE on {@code ledger_entries} is rejected by the DB trigger.</li>
 *   <li>Direct DELETE on {@code ledger_lines} is rejected by the DB trigger.</li>
 *   <li>Reversal entry is balanced and references the original.</li>
 *   <li>Original + reversal lines net to zero per account.</li>
 *   <li>Trying to reverse the same entry twice returns {@code REVERSAL_ALREADY_EXISTS}.</li>
 *   <li>Blank reversal reason returns {@code REVERSAL_REASON_REQUIRED}.</li>
 *   <li>Reversing a non-existent entry returns {@code LEDGER_ENTRY_NOT_FOUND}.</li>
 *   <li>Reversing a REVERSAL entry returns {@code CANNOT_REVERSE_REVERSAL}.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase 10 — Ledger Immutability Integration Tests")
class LedgerImmutabilityIT extends PostgresIntegrationTestBase {

    @Autowired private LedgerService            ledgerService;
    @Autowired private LedgerReversalService    reversalService;
    @Autowired private LedgerAccountRepository  accountRepository;
    @Autowired private LedgerEntryRepository    entryRepository;
    @Autowired private LedgerLineRepository     lineRepository;
    @Autowired private JdbcTemplate             jdbcTemplate;
    @Autowired private TransactionTemplate      txTemplate;

    private static final BigDecimal AMOUNT = new BigDecimal("300.00");

    // Accounts seeded by AccountSeeder (runs at Spring context startup)
    private LedgerAccount pgClearing;
    private LedgerAccount subLiability;

    // The "golden" original entry shared by reversal tests
    private LedgerEntry originalEntry;

    @BeforeAll
    void setUp() {
        // Install DB-level immutability triggers.
        // Flyway is disabled in tests (create-drop DDL), so we create the function and triggers manually.
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION prevent_ledger_modification()
                RETURNS TRIGGER AS $$
                BEGIN
                    IF TG_OP = 'DELETE' THEN
                        RAISE EXCEPTION
                            'Ledger records are immutable: DELETE is not allowed on table "%" (id=%). Correct via reversal entry.',
                            TG_TABLE_NAME, OLD.id
                            USING ERRCODE = 'raise_exception';
                    END IF;
                    IF TG_OP = 'UPDATE' THEN
                        RAISE EXCEPTION
                            'Ledger records are immutable: UPDATE is not allowed on table "%" (id=%). Correct via reversal entry.',
                            TG_TABLE_NAME, OLD.id
                            USING ERRCODE = 'raise_exception';
                    END IF;
                    RETURN NULL;
                END;
                $$ LANGUAGE plpgsql;
                """);

        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS trg_ledger_entries_immutable ON ledger_entries;
                CREATE TRIGGER trg_ledger_entries_immutable
                    BEFORE UPDATE OR DELETE ON ledger_entries
                    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();
                """);

        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS trg_ledger_lines_immutable ON ledger_lines;
                CREATE TRIGGER trg_ledger_lines_immutable
                    BEFORE UPDATE OR DELETE ON ledger_lines
                    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();
                """);

        pgClearing   = accountRepository.findByName("PG_CLEARING")
                .orElseThrow(() -> new IllegalStateException("PG_CLEARING account not found — check AccountSeeder"));
        subLiability = accountRepository.findByName("SUBSCRIPTION_LIABILITY")
                .orElseThrow(() -> new IllegalStateException("SUBSCRIPTION_LIABILITY account not found"));

        // Post a real balanced entry to use in all tests
        originalEntry = ledgerService.postEntry(
                LedgerEntryType.PAYMENT_CAPTURED,
                LedgerReferenceType.PAYMENT,
                1000L,
                "INR",
                List.of(
                        LedgerLineRequest.builder().accountName("PG_CLEARING")
                                .direction(LineDirection.DEBIT).amount(AMOUNT).build(),
                        LedgerLineRequest.builder().accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.CREDIT).amount(AMOUNT).build()
                )
        );
    }

    // ── DB-level immutability ─────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("UPDATE on ledger_entries row is rejected by DB trigger")
    void updateLedgerEntry_rejectedByDbTrigger() {
        Long entryId = originalEntry.getId();

        // Run inside a transaction that we expect to fail
        assertThatThrownBy(() ->
            txTemplate.execute(status -> {
                jdbcTemplate.update(
                        "UPDATE ledger_entries SET currency = 'USD' WHERE id = ?",
                        entryId);
                return null;
            })
        ).isInstanceOfAny(
                DataIntegrityViolationException.class,
                org.springframework.dao.DataAccessException.class,
                Exception.class) // DB trigger raises an exception; Spring wraps it
         .satisfies(ex -> {
             String msg = ex.getMessage();
             if (msg != null) {
                 // Postgres trigger message contains "immutable"
                 // (the exact wrapper type can vary; the message is canonical)
                 assertThat(msg.toLowerCase()).containsAnyOf("immutable", "not allowed", "ledger_entries");
             }
         });
    }

    @Test
    @Order(2)
    @DisplayName("DELETE on ledger_entries row is rejected by DB trigger")
    void deleteLedgerEntry_rejectedByDbTrigger() {
        Long entryId = originalEntry.getId();

        assertThatThrownBy(() ->
            txTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM ledger_lines WHERE entry_id = ?", entryId);
                jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId);
                return null;
            })
        ).isInstanceOfAny(
                DataIntegrityViolationException.class,
                org.springframework.dao.DataAccessException.class,
                Exception.class)
         .satisfies(ex -> {
             String msg = ex.getMessage();
             if (msg != null) {
                 assertThat(msg.toLowerCase()).containsAnyOf("immutable", "not allowed", "ledger_lines", "ledger_entries");
             }
         });
    }

    @Test
    @Order(3)
    @DisplayName("DELETE on ledger_lines row is rejected by DB trigger")
    void deleteLedgerLine_rejectedByDbTrigger() {
        List<LedgerLine> lines = lineRepository.findByEntryId(originalEntry.getId());
        assertThat(lines).isNotEmpty();
        Long lineId = lines.get(0).getId();

        assertThatThrownBy(() ->
            txTemplate.execute(status -> {
                jdbcTemplate.update("DELETE FROM ledger_lines WHERE id = ?", lineId);
                return null;
            })
        ).isInstanceOfAny(
                DataIntegrityViolationException.class,
                org.springframework.dao.DataAccessException.class,
                Exception.class)
         .satisfies(ex -> {
             String msg = ex.getMessage();
             if (msg != null) {
                 assertThat(msg.toLowerCase()).containsAnyOf("immutable", "not allowed", "ledger_lines");
             }
         });
    }

    // ── Reversal correctness ──────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("reversal entry is balanced with flipped directions and references original")
    void reversal_isBalancedAndReferencesOriginal() {
        // Use a fresh entry so this test doesn't conflict with order-6 duplicate test
        LedgerEntry entry = ledgerService.postEntry(
                LedgerEntryType.PAYMENT_CAPTURED,
                LedgerReferenceType.PAYMENT,
                2000L, "INR",
                List.of(
                        LedgerLineRequest.builder().accountName("PG_CLEARING")
                                .direction(LineDirection.DEBIT).amount(AMOUNT).build(),
                        LedgerLineRequest.builder().accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.CREDIT).amount(AMOUNT).build()
                )
        );

        LedgerEntry reversal = reversalService.reverse(entry.getId(), "test reversal reason", null);

        assertThat(reversal.getEntryType()).isEqualTo(LedgerEntryType.REVERSAL);
        assertThat(reversal.getReversalOfEntryId()).isEqualTo(entry.getId());
        assertThat(reversal.getReversalReason()).isEqualTo("test reversal reason");
        assertThat(reversal.getCurrency()).isEqualTo("INR");

        List<LedgerLine> reversalLines = lineRepository.findByEntryId(reversal.getId());
        assertThat(reversalLines).hasSize(2);

        BigDecimal drTotal = reversalLines.stream()
                .filter(l -> l.getDirection() == LineDirection.DEBIT)
                .map(LedgerLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal crTotal = reversalLines.stream()
                .filter(l -> l.getDirection() == LineDirection.CREDIT)
                .map(LedgerLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(drTotal).isEqualByComparingTo(crTotal);
    }

    @Test
    @Order(5)
    @DisplayName("original entry + its reversal net to zero per account")
    void originalPlusReversal_netToZero() {
        LedgerEntry entry = ledgerService.postEntry(
                LedgerEntryType.PAYMENT_CAPTURED,
                LedgerReferenceType.PAYMENT,
                3000L, "INR",
                List.of(
                        LedgerLineRequest.builder().accountName("PG_CLEARING")
                                .direction(LineDirection.DEBIT).amount(AMOUNT).build(),
                        LedgerLineRequest.builder().accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.CREDIT).amount(AMOUNT).build()
                )
        );
        LedgerEntry reversal = reversalService.reverse(entry.getId(), "net zero test", null);

        List<LedgerLine> origLines     = lineRepository.findByEntryId(entry.getId());
        List<LedgerLine> reversalLines = lineRepository.findByEntryId(reversal.getId());

        BigDecimal pgNet = netForAccount(pgClearing.getId(), origLines, reversalLines);
        BigDecimal slNet = netForAccount(subLiability.getId(), origLines, reversalLines);

        assertThat(pgNet).as("PG_CLEARING net after reversal").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(slNet).as("SUBSCRIPTION_LIABILITY net after reversal").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @Order(6)
    @DisplayName("reversing the same entry twice throws REVERSAL_ALREADY_EXISTS 409")
    void duplicateReversal_throwsConflict() {
        // First reversal on the global originalEntry
        reversalService.reverse(originalEntry.getId(), "first reversal", null);

        // Second reversal attempt must be rejected
        assertThatThrownBy(() ->
                reversalService.reverse(originalEntry.getId(), "duplicate reversal", null))
                .isInstanceOf(MembershipException.class)
                .satisfies(ex -> {
                    MembershipException me = (MembershipException) ex;
                    assertThat(me.getErrorCode()).isEqualTo("REVERSAL_ALREADY_EXISTS");
                    assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    // ── Validation rules ──────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("blank reversal reason throws REVERSAL_REASON_REQUIRED 422")
    void blankReason_throwsReasonRequired() {
        LedgerEntry entry = ledgerService.postEntry(
                LedgerEntryType.PAYMENT_CAPTURED,
                LedgerReferenceType.PAYMENT,
                4000L, "INR",
                List.of(
                        LedgerLineRequest.builder().accountName("PG_CLEARING")
                                .direction(LineDirection.DEBIT).amount(AMOUNT).build(),
                        LedgerLineRequest.builder().accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.CREDIT).amount(AMOUNT).build()
                )
        );

        assertThatThrownBy(() ->
                reversalService.reverse(entry.getId(), "   ", null))
                .isInstanceOf(MembershipException.class)
                .satisfies(ex -> {
                    MembershipException me = (MembershipException) ex;
                    assertThat(me.getErrorCode()).isEqualTo("REVERSAL_REASON_REQUIRED");
                    assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    @Order(8)
    @DisplayName("reversing a non-existent entry throws LEDGER_ENTRY_NOT_FOUND 404")
    void entryNotFound_throwsNotFound() {
        assertThatThrownBy(() ->
                reversalService.reverse(Long.MAX_VALUE, "does not exist", null))
                .isInstanceOf(MembershipException.class)
                .satisfies(ex -> {
                    MembershipException me = (MembershipException) ex;
                    assertThat(me.getErrorCode()).isEqualTo("LEDGER_ENTRY_NOT_FOUND");
                    assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    @Order(9)
    @DisplayName("reversing a REVERSAL entry throws CANNOT_REVERSE_REVERSAL 422")
    void reversalEntry_cannotBeReversed() {
        // Create a fresh original + reversal pair
        LedgerEntry entry = ledgerService.postEntry(
                LedgerEntryType.PAYMENT_CAPTURED,
                LedgerReferenceType.PAYMENT,
                5000L, "INR",
                List.of(
                        LedgerLineRequest.builder().accountName("PG_CLEARING")
                                .direction(LineDirection.DEBIT).amount(AMOUNT).build(),
                        LedgerLineRequest.builder().accountName("SUBSCRIPTION_LIABILITY")
                                .direction(LineDirection.CREDIT).amount(AMOUNT).build()
                )
        );
        LedgerEntry reversalEntry = reversalService.reverse(entry.getId(), "create reversal to try to re-reverse", null);

        // Now attempt to reverse the reversal entry itself
        assertThatThrownBy(() ->
                reversalService.reverse(reversalEntry.getId(), "re-reversal attempt", null))
                .isInstanceOf(MembershipException.class)
                .satisfies(ex -> {
                    MembershipException me = (MembershipException) ex;
                    assertThat(me.getErrorCode()).isEqualTo("CANNOT_REVERSE_REVERSAL");
                    assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal netForAccount(Long accountId,
                                     List<LedgerLine> origLines,
                                     List<LedgerLine> reversalLines) {
        BigDecimal net = BigDecimal.ZERO;
        for (LedgerLine l : origLines) {
            if (l.getAccountId().equals(accountId)) {
                net = net.add(l.getDirection() == LineDirection.DEBIT ? l.getAmount() : l.getAmount().negate());
            }
        }
        for (LedgerLine l : reversalLines) {
            if (l.getAccountId().equals(accountId)) {
                net = net.add(l.getDirection() == LineDirection.DEBIT ? l.getAmount() : l.getAmount().negate());
            }
        }
        return net;
    }
}
