package com.firstclub.platform.repair.actions;

import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.firstclub.reporting.projections.service.LedgerSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Rebuilds the ledger balance snapshot for a given calendar date.
 *
 * <p><b>What changes:</b> {@code ledger_balance_snapshots} rows for the
 * requested date — existing rows for that date are overwritten with freshly
 * computed balances.
 *
 * <p><b>What is never changed:</b> {@code ledger_entries} and
 * {@code ledger_lines} are strictly read-only.
 *
 * <p><b>Dry-run:</b> not supported (snapshot generation is cheap and
 * idempotent, so the live run is safe).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerSnapshotRebuildAction implements RepairAction {

    static final String PARAM_DATE = "date";

    private final LedgerSnapshotService ledgerSnapshotService;
    private final ObjectMapper          objectMapper;

    @Override
    public String getRepairKey() { return "repair.ledger.rebuild_snapshot"; }

    @Override
    public String getTargetType() { return "LEDGER_SNAPSHOT"; }

    @Override
    public boolean supportsDryRun() { return false; }

    @Override
    @Transactional
    public RepairActionResult execute(RepairContext context) {
        String dateStr = context.param(PARAM_DATE);
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Parameter 'date' (YYYY-MM-DD) is required");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format, expected YYYY-MM-DD: " + dateStr);
        }

        log.info("LedgerSnapshotRebuildAction: rebuilding snapshot for date={}", date);
        List<?> snapshots = ledgerSnapshotService.generateSnapshotForDate(date);

        String afterJson = toJson(snapshots);
        log.info("LedgerSnapshotRebuildAction: rebuilt {} account snapshots for date={}",
                snapshots.size(), date);

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .afterSnapshotJson(afterJson)
                .details("Ledger snapshot rebuilt for date=" + date + ": "
                        + snapshots.size() + " account balances recomputed")
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
