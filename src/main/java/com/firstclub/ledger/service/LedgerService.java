package com.firstclub.ledger.service;

import com.firstclub.ledger.LedgerEntryFactory;
import com.firstclub.ledger.LedgerPostingPolicy;
import com.firstclub.ledger.dto.LedgerAccountBalanceDTO;
import com.firstclub.ledger.dto.LedgerEntryResponseDTO;
import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.dto.LedgerLineResponseDTO;
import com.firstclub.ledger.entity.*;
import com.firstclub.ledger.guard.ImmutableLedgerGuard;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerEntryRepository   entryRepository;
    private final LedgerLineRepository    lineRepository;
    private final LedgerPostingPolicy     postingPolicy;
    private final ImmutableLedgerGuard    immutableGuard;
    private final LedgerEntryFactory      entryFactory;

    // -------------------------------------------------------------------------
    // Write — post a double-entry set
    // -------------------------------------------------------------------------

    @Transactional
    public LedgerEntry postEntry(LedgerEntryType entryType,
                                 LedgerReferenceType referenceType,
                                 Long referenceId,
                                 String currency,
                                 List<LedgerLineRequest> lines) {

        postingPolicy.validateLines(lines);

        LedgerEntry entry = LedgerEntry.builder()
                .entryType(entryType)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .currency(currency)
                .build();

        immutableGuard.assertNewEntry(entry);

        entry = entryRepository.save(entry);

        for (LedgerLineRequest req : lines) {
            persistLine(entry.getId(), req);
        }

        log.debug("Posted ledger entry {} ({}) for ref {}:{}", entry.getId(), entryType, referenceType, referenceId);
        return entry;
    }

    /**
     * Phase 10 — Posts a REVERSAL entry that mirrors {@code original} with all
     * line directions flipped.  Called by {@link com.firstclub.ledger.reversal.LedgerReversalServiceImpl}.
     *
     * @param original      the entry being corrected
     * @param flippedLines  line requests with directions already flipped by {@link LedgerEntryFactory}
     * @param reason        mandatory reversal explanation
     * @param postedByUserId optional operator id
     */
    @Transactional
    public LedgerEntry postReversalEntry(LedgerEntry original,
                                         List<LedgerLineRequest> flippedLines,
                                         String reason,
                                         Long postedByUserId) {

        postingPolicy.validateLines(flippedLines);

        LedgerEntry reversal = entryFactory.buildReversalEntry(
                original, original.getId(), reason, postedByUserId);

        immutableGuard.assertNewEntry(reversal);

        reversal = entryRepository.save(reversal);

        for (LedgerLineRequest req : flippedLines) {
            persistLine(reversal.getId(), req);
        }

        log.info("REVERSAL entry {} posted for original entry {} reason=\"{}\"",
                reversal.getId(), original.getId(), reason);
        return reversal;
    }

    // -------------------------------------------------------------------------
    // Read — single entry with lines
    // -------------------------------------------------------------------------

    /**
     * Phase 10 — Returns a full view of the entry including its child lines.
     *
     * @throws MembershipException (404) if entry not found
     */
    @Transactional(readOnly = true)
    public LedgerEntryResponseDTO getEntry(Long entryId) {
        LedgerEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new MembershipException(
                        "Ledger entry not found: " + entryId,
                        "LEDGER_ENTRY_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        List<LedgerLine> lines = lineRepository.findByEntryId(entryId);

        // Build an account-id→name map for response projection
        Map<Long, String> accountNameById = buildAccountNameMapFromLines(lines);

        List<LedgerLineResponseDTO> lineDTO = lines.stream()
                .map(l -> LedgerLineResponseDTO.builder()
                        .id(l.getId())
                        .entryId(l.getEntryId())
                        .accountId(l.getAccountId())
                        .accountName(accountNameById.getOrDefault(l.getAccountId(), "UNKNOWN"))
                        .direction(l.getDirection())
                        .amount(l.getAmount())
                        .build())
                .collect(Collectors.toList());

        return LedgerEntryResponseDTO.builder()
                .id(entry.getId())
                .entryType(entry.getEntryType())
                .referenceType(entry.getReferenceType())
                .referenceId(entry.getReferenceId())
                .currency(entry.getCurrency())
                .createdAt(entry.getCreatedAt())
                .metadata(entry.getMetadata())
                .reversalOfEntryId(entry.getReversalOfEntryId())
                .postedByUserId(entry.getPostedByUserId())
                .reversalReason(entry.getReversalReason())
                .lines(lineDTO)
                .build();
    }

    // -------------------------------------------------------------------------
    // Read — balance sheet
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LedgerAccountBalanceDTO> getBalances() {
        List<LedgerAccount> accounts = accountRepository.findAll();
        List<LedgerLine>    allLines = lineRepository.findAll();

        Map<Long, BigDecimal> debitByAccount  = new HashMap<>();
        Map<Long, BigDecimal> creditByAccount = new HashMap<>();

        for (LedgerLine line : allLines) {
            if (line.getDirection() == LineDirection.DEBIT) {
                debitByAccount.merge(line.getAccountId(), line.getAmount(), BigDecimal::add);
            } else {
                creditByAccount.merge(line.getAccountId(), line.getAmount(), BigDecimal::add);
            }
        }

        return accounts.stream().map(account -> toBalanceDTO(account, debitByAccount, creditByAccount))
                .collect(Collectors.toList());
    }

    /**
     * Phase 10 — Returns the balance for a single account looked up by name/code.
     *
     * @param accountCode unique account name, e.g. {@code "PG_CLEARING"}
     * @throws MembershipException (404) if no account with that name exists
     */
    @Transactional(readOnly = true)
    public LedgerAccountBalanceDTO getAccountBalanceByCode(String accountCode) {
        LedgerAccount account = accountRepository.findByName(accountCode)
                .orElseThrow(() -> new MembershipException(
                        "Ledger account not found: " + accountCode,
                        "LEDGER_ACCOUNT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        List<LedgerLine> lines = lineRepository.findAll(); // scoped by accountId below

        BigDecimal debit  = lines.stream()
                .filter(l -> l.getAccountId().equals(account.getId()) && l.getDirection() == LineDirection.DEBIT)
                .map(LedgerLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal credit = lines.stream()
                .filter(l -> l.getAccountId().equals(account.getId()) && l.getDirection() == LineDirection.CREDIT)
                .map(LedgerLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return toBalanceDTO(account,
                Map.of(account.getId(), debit),
                Map.of(account.getId(), credit));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void persistLine(Long entryId, LedgerLineRequest req) {
        LedgerAccount account = accountRepository.findByName(req.getAccountName())
                .orElseThrow(() -> new MembershipException(
                        "Ledger account not found: " + req.getAccountName(),
                        "LEDGER_ACCOUNT_NOT_FOUND"));

        LedgerLine line = LedgerLine.builder()
                .entryId(entryId)
                .accountId(account.getId())
                .direction(req.getDirection())
                .amount(req.getAmount())
                .build();

        immutableGuard.assertNewLine(line);
        lineRepository.save(line);
    }

    private LedgerAccountBalanceDTO toBalanceDTO(LedgerAccount account,
                                                  Map<Long, BigDecimal> debitMap,
                                                  Map<Long, BigDecimal> creditMap) {
        BigDecimal debit  = debitMap.getOrDefault(account.getId(), BigDecimal.ZERO);
        BigDecimal credit = creditMap.getOrDefault(account.getId(), BigDecimal.ZERO);

        boolean normalDebit = account.getAccountType() == LedgerAccount.AccountType.ASSET
                           || account.getAccountType() == LedgerAccount.AccountType.EXPENSE;
        BigDecimal balance  = normalDebit ? debit.subtract(credit) : credit.subtract(debit);

        return LedgerAccountBalanceDTO.builder()
                .accountId(account.getId())
                .accountName(account.getName())
                .accountType(account.getAccountType())
                .currency(account.getCurrency())
                .debitTotal(debit)
                .creditTotal(credit)
                .balance(balance)
                .build();
    }

    private Map<Long, String> buildAccountNameMapFromLines(List<LedgerLine> lines) {
        return lines.stream()
                .map(LedgerLine::getAccountId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> accountRepository.findById(id)
                                .map(LedgerAccount::getName)
                                .orElse("UNKNOWN")));
    }
}
