package com.firstclub.platform.ops.service;

import com.firstclub.platform.ops.dto.DlqEntryResponseDTO;
import com.firstclub.platform.ops.dto.DlqSummaryDTO;

import java.util.List;

public interface DlqOpsService {

    /**
     * Returns all current DLQ entries, optionally filtered.
     *
     * @param source          filter by source (e.g. {@code "OUTBOX"}), or {@code null} for any
     * @param failureCategory filter by failure category, or {@code null} for any
     */
    List<DlqEntryResponseDTO> listDlqEntries(String source, String failureCategory);

    /**
     * Returns aggregate DLQ counts grouped by source and failure category.
     */
    DlqSummaryDTO getDlqSummary();

    /**
     * Re-queues the DLQ entry as a new outbox event and removes it from the DLQ.
     * Throws {@code ResponseStatusException(NOT_FOUND)} if the entry does not exist.
     * The caller is responsible for ensuring the underlying cause is resolved
     * before retrying, otherwise the message may land back in the DLQ.
     */
    DlqEntryResponseDTO retryDlqEntry(Long id);
}
