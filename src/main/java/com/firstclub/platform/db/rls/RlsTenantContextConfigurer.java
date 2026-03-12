package com.firstclub.platform.db.rls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Applies the PostgreSQL session variable {@code app.current_merchant_id}
 * that Row-Level Security policies on tenant-scoped tables depend on.
 *
 * <h2>How RLS session context works</h2>
 * At the start of every merchant-scoped {@code @Transactional} method (or
 * just before the first query), call:
 * <pre>{@code
 * rlsTenantContextConfigurer.applyMerchantContext(merchantId);
 * }</pre>
 * This executes {@code SET LOCAL app.current_merchant_id = '<id>'} on the
 * current JDBC connection. The {@code LOCAL} scope means the variable is
 * cleared automatically when the transaction commits or rolls back — there is
 * no risk of a stale merchant ID leaking to the next request that reuses the
 * same pooled connection.
 *
 * <h2>RLS policies (created in V67)</h2>
 * The four guarded tables each carry a PERMISSIVE policy:
 * <pre>{@code
 * USING (merchant_id = NULLIF(current_setting('app.current_merchant_id', true), '')::BIGINT)
 * }</pre>
 * If the session variable is not set (background job, unauthenticated
 * context), {@code NULLIF(..., '')} returns {@code NULL}, making the policy
 * evaluate to {@code NULL} (falsy in SQL) → zero rows visible. This is the
 * <em>safe default</em>.
 *
 * <h2>Background jobs that read all merchants</h2>
 * Options: (a) run as a database role with {@code BYPASSRLS}, or (b) iterate
 * per merchant and call {@link #applyMerchantContext} once per merchant within
 * each transaction.
 *
 * <h2>Spring/JPA compatibility</h2>
 * This class uses {@link JdbcTemplate} to run the {@code SET LOCAL} command
 * on the same underlying connection that Hibernate uses for the current
 * transaction. Because both share a connection via
 * {@code DataSourceTransactionManager} / {@code JpaTransactionManager}, the
 * variable is visible to all JPA queries within the same transaction.
 *
 * <h2>Non-PostgreSQL environments</h2>
 * All operations are silently skipped on H2 (dev/test profile) — no code
 * changes are required in the service layer to accommodate both environments.
 */
@Component
public class RlsTenantContextConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RlsTenantContextConfigurer.class);

    private final JdbcTemplate jdbcTemplate;

    public RlsTenantContextConfigurer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Applies {@code SET LOCAL app.current_merchant_id = '<merchantId>'}
     * to the current database transaction.
     *
     * <p>Must be called <em>inside</em> an active Spring-managed transaction.
     * Using {@code @Transactional} on the calling method is the recommended
     * pattern.
     *
     * @param merchantId the merchant whose rows should be visible; must be
     *                   non-null and positive
     * @throws IllegalArgumentException if {@code merchantId} is null or negative
     */
    public void applyMerchantContext(Long merchantId) {
        if (merchantId == null || merchantId < 0) {
            throw new IllegalArgumentException(
                    "merchantId must be a non-null positive Long, got: " + merchantId);
        }
        if (!isPostgres()) {
            log.trace("RlsTenantContextConfigurer: SET LOCAL skipped (non-PostgreSQL)");
            return;
        }
        // merchantId is typed as Long — a numeric Java type. String-formatting
        // a Long cannot introduce SQL injection (only digits and optional minus).
        jdbcTemplate.execute("SET LOCAL app.current_merchant_id = '" + merchantId + "'");
        log.trace("RlsTenantContextConfigurer: SET LOCAL app.current_merchant_id = {}", merchantId);
    }

    /**
     * Clears the merchant context by setting {@code app.current_merchant_id}
     * to an empty string for the current transaction.
     *
     * <p>Normally not required because {@code SET LOCAL} auto-resets at
     * transaction end; useful when explicitly switching merchant scope within
     * a single long transaction.
     */
    public void clearMerchantContext() {
        if (!isPostgres()) {
            return;
        }
        jdbcTemplate.execute("SET LOCAL app.current_merchant_id = ''");
        log.trace("RlsTenantContextConfigurer: merchant context cleared");
    }

    /**
     * Returns the merchant ID currently stored in the Postgres session
     * variable, if any.
     *
     * @return an {@link Optional} containing the current merchant ID, or
     *         {@link Optional#empty()} if not set, not a valid number, or
     *         running on a non-PostgreSQL database
     */
    public Optional<Long> currentMerchantContext() {
        if (!isPostgres()) {
            return Optional.empty();
        }
        try {
            String raw = jdbcTemplate.queryForObject(
                    "SELECT current_setting('app.current_merchant_id', true)",
                    String.class);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            log.debug("RlsTenantContextConfigurer: current_merchant_id is not a valid Long");
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if the underlying database is PostgreSQL.
     * RLS operations are silently skipped on non-PostgreSQL engines.
     */
    public boolean isPostgres() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute(
                    (ConnectionCallback<Boolean>) conn ->
                            conn.getMetaData().getDatabaseProductName()
                                    .toLowerCase().contains("postgresql")));
        } catch (Exception e) {
            log.debug("RlsTenantContextConfigurer: could not determine database product: {}",
                    e.getMessage());
            return false;
        }
    }
}
