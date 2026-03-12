package com.firstclub.platform.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Inspects the PostgreSQL query planner to verify that indexes and constraints
 * are actually exercised — not merely declared — by the queries that motivated
 * their creation.
 *
 * <p>Primary use-case: EXPLAIN-plan smoke tests that run as part of CI or ops
 * verification to confirm that the partial indexes added in V67 are selected
 * by the planner for the specific hot queries they were designed to accelerate.
 *
 * <h2>Design intent</h2>
 * Declared indexes can become invisible to the planner if the table statistics
 * are stale, if estimate errors make a sequential scan look cheaper, or if a
 * slight query change removes the filter alignment. This component surfaces
 * those regressions before they affect production query latency.
 *
 * <h2>Non-PostgreSQL environments</h2>
 * All methods return empty/false on H2 (dev/test profile). The class is
 * therefore safe to inject in any environment; callers do not need to guard
 * against the database type.
 */
@Component
public class DbConstraintExplainer {

    private static final Logger log = LoggerFactory.getLogger(DbConstraintExplainer.class);

    private final JdbcTemplate jdbcTemplate;

    public DbConstraintExplainer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // EXPLAIN plan access
    // -------------------------------------------------------------------------

    /**
     * Runs {@code EXPLAIN (FORMAT TEXT)} on {@code sql} and returns the full
     * planner output as a single newline-joined string.
     *
     * @param sql a SELECT statement to explain (no bind parameters)
     * @return the planner output, or an empty string on non-PostgreSQL
     */
    public String explainQuery(String sql) {
        if (!isPostgres()) {
            return "";
        }
        List<String> lines = jdbcTemplate.queryForList("EXPLAIN " + sql, String.class);
        return String.join("\n", lines);
    }

    /**
     * Returns {@code true} if the EXPLAIN plan text for {@code sql} contains a
     * reference to {@code indexName} (case-insensitive).
     *
     * <p>Used in smoke tests to assert that the planner chose the expected
     * partial index rather than a seq scan or an inferior index.
     *
     * @param sql       the query to explain (no bind parameters)
     * @param indexName the index name to search for in the plan
     */
    public boolean isIndexUsed(String sql, String indexName) {
        String plan = explainQuery(sql);
        return plan.toLowerCase().contains(indexName.toLowerCase());
    }

    /**
     * Runs {@code EXPLAIN (FORMAT TEXT, ANALYZE false)} on {@code sql} with
     * JDBC bind parameters. {@code ANALYZE false} ensures no rows are actually
     * fetched — this is safe to call on production data.
     *
     * @param sql    the query with {@code ?} placeholders
     * @param params the bind values
     * @return the planner output, or an empty string on non-PostgreSQL
     */
    public String explainParameterized(String sql, Object... params) {
        if (!isPostgres()) {
            return "";
        }
        List<String> lines = jdbcTemplate.queryForList(
                "EXPLAIN (FORMAT TEXT, ANALYZE false) " + sql, String.class, params);
        return String.join("\n", lines);
    }

    // -------------------------------------------------------------------------
    // Schema introspection
    // -------------------------------------------------------------------------

    /**
     * Lists all partial indexes on {@code tableName} by querying
     * {@code pg_indexes}. A partial index has a {@code WHERE} clause in its
     * definition.
     *
     * @param tableName the table to inspect
     * @return partial index info records, empty on non-PostgreSQL
     */
    public List<PartialIndexInfo> listPartialIndexes(String tableName) {
        if (!isPostgres()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT indexname, indexdef "
                        + "FROM   pg_indexes "
                        + "WHERE  tablename = ? "
                        + "  AND  indexdef LIKE '%WHERE%' "
                        + "ORDER  BY indexname",
                (rs, rowNum) -> new PartialIndexInfo(
                        rs.getString("indexname"),
                        rs.getString("indexdef")),
                tableName);
    }

    /**
     * Returns {@code true} if Row-Level Security is enabled on {@code tableName}.
     *
     * @param tableName the table to inspect
     */
    public boolean isRlsEnabled(String tableName) {
        if (!isPostgres()) {
            return false;
        }
        Boolean result = jdbcTemplate.queryForObject(
                "SELECT rowsecurity FROM pg_tables WHERE tablename = ?",
                Boolean.class,
                tableName);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Lists the child partition names for {@code parentTable} from
     * {@code pg_inherits}. Returns an empty list if the table is not
     * partitioned or the database is non-PostgreSQL.
     *
     * @param parentTable the logical (parent) table name
     */
    public List<String> listPartitions(String parentTable) {
        if (!isPostgres()) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                "SELECT c.relname "
                        + "FROM   pg_inherits i "
                        + "JOIN   pg_class c ON c.oid = i.inhrelid "
                        + "JOIN   pg_class p ON p.oid = i.inhparent "
                        + "WHERE  p.relname = ? "
                        + "ORDER  BY c.relname",
                String.class,
                parentTable);
    }

    /**
     * Returns {@code true} if the PostgreSQL database is reachable and the
     * database product name identifies it as PostgreSQL.
     */
    public boolean isPostgres() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute(
                    (ConnectionCallback<Boolean>) conn ->
                            conn.getMetaData().getDatabaseProductName()
                                    .toLowerCase().contains("postgresql")));
        } catch (Exception e) {
            log.debug("DbConstraintExplainer: could not determine database product: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    /**
     * Metadata about a partial index as recorded in {@code pg_indexes}.
     *
     * @param indexName the index identifier (e.g. {@code idx_outbox_retryable_by_merchant})
     * @param indexDef  the full {@code CREATE INDEX} definition including the
     *                  {@code WHERE} clause
     */
    public record PartialIndexInfo(String indexName, String indexDef) {}
}
