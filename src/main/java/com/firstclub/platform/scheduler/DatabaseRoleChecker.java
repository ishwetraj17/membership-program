package com.firstclub.platform.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks whether the connected PostgreSQL node is the primary (read-write)
 * instance or a read-only replica.
 *
 * <h3>Why this matters for schedulers</h3>
 * In primary-replica (e.g. streaming replication or RDS Multi-AZ) setups, the
 * application may be connected to a replica — especially if the connection
 * string points at a load balancer.  Running a write-heavy scheduler on a
 * replica will either fail with read-only errors or -- if the connection
 * surprises the replica into promotion -- cause split-brain writes.
 *
 * <p>{@code pg_is_in_recovery()} returns {@code true} when the PostgreSQL node
 * is a standby (replica) and is applying WAL changes from the primary.  A
 * primary node returns {@code false}.
 *
 * <h3>DB time</h3>
 * This class uses the database to answer the question, ensuring the answer
 * reflects the actual role of the connected node — not a cached JVM value that
 * might be stale after a failover.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseRoleChecker {

    private final JdbcTemplate jdbc;

    /**
     * Returns {@code true} if the connected PostgreSQL node is the primary
     * (read-write) instance.
     *
     * <p>Queries {@code SELECT NOT pg_is_in_recovery()} to determine the role.
     * On any DB failure the method returns {@code false} (assume replica /
     * unknown) to fail safe — schedulers will not run if the role cannot be
     * confirmed.
     *
     * @return {@code true} = primary (safe to write); {@code false} = replica or unknown
     */
    public boolean isPrimary() {
        try {
            Boolean primary = jdbc.queryForObject(
                    "SELECT NOT pg_is_in_recovery()", Boolean.class);
            boolean result = Boolean.TRUE.equals(primary);
            log.debug("[DB-ROLE] pg_is_in_recovery check: isPrimary={}", result);
            return result;
        } catch (Exception ex) {
            log.warn("[DB-ROLE] Could not check pg_is_in_recovery — assuming replica: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Returns {@code true} if the connected PostgreSQL node is a standby replica
     * (i.e. applying WAL from a primary).
     *
     * @return {@code true} = replica; {@code false} = primary or unknown
     */
    public boolean isInRecovery() {
        return !isPrimary();
    }
}
