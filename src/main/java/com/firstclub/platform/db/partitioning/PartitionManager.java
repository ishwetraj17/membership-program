package com.firstclub.platform.db.partitioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.YearMonth;
import java.util.List;

/**
 * Manages monthly PostgreSQL child partitions for the four high-volume
 * append-only tables registered in V67: {@code outbox_events},
 * {@code domain_events}, {@code audit_entries}, {@code ops_timeline_events}.
 *
 * <h2>Usage</h2>
 * Call {@link #ensureAllManagedPartitions(int)} on application startup (e.g.
 * from an {@code ApplicationRunner}) and from a monthly scheduled job to
 * guarantee that child partition tables exist for the current and upcoming
 * months before data arrives.
 *
 * <h2>Underlying mechanism</h2>
 * Delegates actual SQL work to the {@code create_monthly_partition} PL/pgSQL
 * function created by V67. Records confirmed partitions in
 * {@code partition_management_log} for observability.
 *
 * <h2>Non-PostgreSQL environments</h2>
 * Partition operations are silently skipped on H2 (dev/test profile) so
 * no code changes are required in the service layer.
 */
@Component
public class PartitionManager {

    private static final Logger log = LoggerFactory.getLogger(PartitionManager.class);

    /**
     * The four append-only tables targeted by the V67 partitioning strategy.
     * All are high-volume event logs where old data can be detached as whole
     * partitions instead of requiring slow DELETE operations.
     */
    public static final List<String> MANAGED_TABLES = List.of(
            "outbox_events",
            "domain_events",
            "audit_entries",
            "ops_timeline_events"
    );

    private final JdbcTemplate jdbcTemplate;

    public PartitionManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Ensures monthly partitions exist for all {@link #MANAGED_TABLES} from
     * the current month through {@code monthsAhead} months in the future.
     *
     * @param monthsAhead number of future months to pre-create (0 = current only)
     * @throws IllegalArgumentException if monthsAhead is negative
     */
    public void ensureAllManagedPartitions(int monthsAhead) {
        if (!isPostgres()) {
            log.debug("PartitionManager: non-PostgreSQL database — partition creation skipped");
            return;
        }
        for (String table : MANAGED_TABLES) {
            ensurePartitionsExist(table, monthsAhead);
        }
    }

    /**
     * Ensures monthly partitions exist for {@code parentTable} from the
     * current month through the requested number of future months.
     *
     * @param parentTable logical parent table name
     * @param monthsAhead number of future months to pre-create (&gt;= 0)
     * @throws IllegalArgumentException if monthsAhead is negative
     */
    public void ensurePartitionsExist(String parentTable, int monthsAhead) {
        if (monthsAhead < 0) {
            throw new IllegalArgumentException(
                    "monthsAhead must be >= 0, got: " + monthsAhead);
        }
        if (!isPostgres()) {
            log.debug("PartitionManager: skipping partition creation for {} (not PostgreSQL)", parentTable);
            return;
        }
        YearMonth current = YearMonth.now();
        for (int i = 0; i <= monthsAhead; i++) {
            createPartition(parentTable, current.plusMonths(i));
        }
    }

    /**
     * Returns the canonical child partition name for a given table and month.
     * <p>Example: {@code resolvePartitionName("outbox_events", YearMonth.of(2026, 3))}
     * → {@code "outbox_events_2026_03"}.
     *
     * @param parentTable the parent table name
     * @param month       the target year-month
     * @return partition name string
     */
    public String resolvePartitionName(String parentTable, YearMonth month) {
        return parentTable + "_"
                + String.format("%04d_%02d", month.getYear(), month.getMonthValue());
    }

    /**
     * Returns partition names recorded in {@code partition_management_log}
     * for the given parent table, ordered by partition start date ascending.
     *
     * @param parentTable the parent table name
     * @return ordered list of partition names
     */
    public List<String> getRecordedPartitions(String parentTable) {
        return jdbcTemplate.queryForList(
                "SELECT partition_name "
                        + "FROM   partition_management_log "
                        + "WHERE  parent_table = ? "
                        + "ORDER  BY partition_start ASC",
                String.class, parentTable);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    void createPartition(String parentTable, YearMonth month) {
        String partitionName = resolvePartitionName(parentTable, month);
        log.debug("PartitionManager: ensuring partition {} exists", partitionName);
        try {
            String created = jdbcTemplate.queryForObject(
                    "SELECT create_monthly_partition(?, ?, ?)",
                    String.class,
                    parentTable, month.getYear(), month.getMonthValue());
            if (created != null) {
                recordPartition(parentTable, partitionName, month);
                log.info("PartitionManager: verified/created partition {}", partitionName);
            }
        } catch (Exception e) {
            log.warn("PartitionManager: could not create partition {} for table {} month {}/{}: {}",
                    partitionName, parentTable, month.getYear(), month.getMonthValue(),
                    e.getMessage());
        }
    }

    private void recordPartition(String parentTable, String partitionName, YearMonth month) {
        jdbcTemplate.update(
                "INSERT INTO partition_management_log "
                        + "  (parent_table, partition_name, partition_start, partition_end) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (parent_table, partition_name) DO NOTHING",
                parentTable,
                partitionName,
                Date.valueOf(month.atDay(1)),
                Date.valueOf(month.plusMonths(1).atDay(1)));
    }

    /**
     * Returns {@code true} when the underlying database is PostgreSQL.
     * All partition operations are no-ops on other databases (e.g. H2 in dev).
     */
    public boolean isPostgres() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute(
                    (ConnectionCallback<Boolean>) conn ->
                            conn.getMetaData().getDatabaseProductName()
                                    .toLowerCase().contains("postgresql")));
        } catch (Exception e) {
            log.debug("PartitionManager: could not determine database product: {}", e.getMessage());
            return false;
        }
    }
}
