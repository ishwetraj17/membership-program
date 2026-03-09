package com.firstclub.ledger.dto;

import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 10 — Read-only view of a {@code LedgerEntry} with its child lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryResponseDTO {

    private Long              id;
    private LedgerEntryType   entryType;
    private LedgerReferenceType referenceType;
    private Long              referenceId;
    private String            currency;
    private LocalDateTime     createdAt;
    private String            metadata;

    // Phase 10 reversal fields
    /** Non-null only for entries with {@code entryType = REVERSAL}. */
    private Long   reversalOfEntryId;
    private Long   postedByUserId;
    private String reversalReason;

    /** Debit and credit legs belonging to this entry. */
    private List<LedgerLineResponseDTO> lines;
}
