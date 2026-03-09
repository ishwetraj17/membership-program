package com.firstclub.platform.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Nightly job that removes expired idempotency keys from the database.
 *
 * <p>Runs at 03:00 every day.  Keys are considered expired when their
 * {@code expires_at} timestamp is in the past (default TTL = 24 h, as set on
 * the {@link com.firstclub.platform.idempotency.annotation.Idempotent}
 * annotation).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupJob {

    private final IdempotencyKeyRepository repository;

    /**
     * Deletes all idempotency records whose TTL has elapsed.
     * Runs daily at 03:00 AM.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteExpiredKeys() {
        LocalDateTime now = LocalDateTime.now();
        log.info("Running idempotency key expiry cleanup at {}", now);
        int deleted = repository.deleteExpiredBefore(now);
        log.info("Idempotency cleanup complete: {} expired record(s) removed", deleted);
    }
}
