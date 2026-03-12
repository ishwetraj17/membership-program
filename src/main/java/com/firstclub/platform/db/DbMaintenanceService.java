package com.firstclub.platform.db;

import com.firstclub.platform.db.partitioning.PartitionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled DB maintenance tasks that keep the partition infrastructure
 * healthy without manual operator intervention.
 *
 * <p>On the 1st of each month at 01:00 this service asks
 * {@link PartitionManager} to ensure the current month and the next two
 * future months all have child partition tables. Running 2 months ahead
 * provides a comfortable buffer so a short scheduler outage does not cause
 * missed partitions.
 *
 * <p>All operations are no-ops on non-PostgreSQL databases (H2 in dev).
 */
@Service
public class DbMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(DbMaintenanceService.class);
    private static final int MONTHS_AHEAD = 2;

    private final PartitionManager partitionManager;

    public DbMaintenanceService(PartitionManager partitionManager) {
        this.partitionManager = partitionManager;
    }

    /**
     * Runs at 01:00 on the 1st of every month.
     * Pre-creates next-month (and over-next-month) partitions for all managed tables.
     */
    @Scheduled(cron = "0 0 1 1 * *")
    public void ensureUpcomingPartitions() {
        log.info("DbMaintenanceService: ensuring partitions for current + {} future months", MONTHS_AHEAD);
        partitionManager.ensureAllManagedPartitions(MONTHS_AHEAD);
        log.info("DbMaintenanceService: partition maintenance complete");
    }

    /**
     * Programmatic trigger for tests or manual operator invocation.
     *
     * @param monthsAhead how many future months to pre-create beyond the current month
     */
    public void runPartitionMaintenance(int monthsAhead) {
        log.info("DbMaintenanceService: manual partition maintenance run (monthsAhead={})", monthsAhead);
        partitionManager.ensureAllManagedPartitions(monthsAhead);
    }
}
