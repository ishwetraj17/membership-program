package com.firstclub.platform.scheduler;

import com.firstclub.platform.scheduler.lock.JdbcAdvisoryLockRepository;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchedulerLockService}.
 *
 * <h3>Covered scenarios</h3>
 * <ul>
 *   <li>Second concurrent scheduler cannot acquire the xact lock</li>
 *   <li>Advisory lock is released on transaction completion (xact variant)</li>
 *   <li>Session lock acquire and explicit release</li>
 *   <li>Lock ID derivation is stable and deterministic</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("SchedulerLockService — Unit Tests")
class SchedulerLockServiceTest {

    @Mock
    private JdbcAdvisoryLockRepository advisoryLockRepo;

    private SchedulerLockService service;

    private static final String SCHEDULER_NAME = "subscription-renewal";

    @BeforeEach
    void setUp() {
        service = new SchedulerLockService(advisoryLockRepo);
    }

    @Nested
    @DisplayName("BatchLock — pg_try_advisory_xact_lock")
    class BatchLock {

        @Test
        @DisplayName("tryAcquireForBatch_firstInstance_acquiresLock — xact lock succeeds")
        void tryAcquireForBatch_firstInstance_acquiresLock() {
            long lockId = service.lockIdFor(SCHEDULER_NAME);
            when(advisoryLockRepo.tryAdvisoryXactLock(lockId)).thenReturn(true);

            boolean acquired = service.tryAcquireForBatch(SCHEDULER_NAME);

            assertThat(acquired).isTrue();
            verify(advisoryLockRepo).tryAdvisoryXactLock(lockId);
        }

        @Test
        @DisplayName("tryAcquireForBatch_secondInstance_skipsExecution — xact lock denied")
        void tryAcquireForBatch_secondInstance_skipsExecution() {
            long lockId = service.lockIdFor(SCHEDULER_NAME);
            when(advisoryLockRepo.tryAdvisoryXactLock(lockId)).thenReturn(false);

            boolean acquired = service.tryAcquireForBatch(SCHEDULER_NAME);

            // Second scheduler instance MUST skip — lock already held by first
            assertThat(acquired).isFalse();
            verify(advisoryLockRepo).tryAdvisoryXactLock(lockId);
        }

        @Test
        @DisplayName("tryAcquireForBatch_advisoryLockReleasedOnRollback — xact variant auto-releases")
        void tryAcquireForBatch_advisoryLockReleasedOnRollback() {
            // Transaction-scoped lock: no explicit release method exists.
            // This test verifies the service calls tryAdvisoryXactLock (not session variant)
            // so PostgreSQL's automatic release-on-rollback applies.
            long lockId = service.lockIdFor(SCHEDULER_NAME);
            when(advisoryLockRepo.tryAdvisoryXactLock(lockId)).thenReturn(true);

            service.tryAcquireForBatch(SCHEDULER_NAME);

            // Verify xact variant was used — NOT the session-level tryAdvisoryLock
            verify(advisoryLockRepo).tryAdvisoryXactLock(lockId);
        }
    }

    @Nested
    @DisplayName("SessionLock — pg_try_advisory_lock + release")
    class SessionLock {

        @Test
        @DisplayName("tryAcquireSessionLock_success — session lock acquired")
        void tryAcquireSessionLock_success() {
            long lockId = service.lockIdFor(SCHEDULER_NAME);
            when(advisoryLockRepo.tryAdvisoryLock(lockId)).thenReturn(true);

            boolean acquired = service.tryAcquireSessionLock(SCHEDULER_NAME);

            assertThat(acquired).isTrue();
        }

        @Test
        @DisplayName("tryAcquireSessionLock_busy — returns false without blocking")
        void tryAcquireSessionLock_busy() {
            long lockId = service.lockIdFor(SCHEDULER_NAME);
            when(advisoryLockRepo.tryAdvisoryLock(lockId)).thenReturn(false);

            boolean acquired = service.tryAcquireSessionLock(SCHEDULER_NAME);

            assertThat(acquired).isFalse();
        }

        @Test
        @DisplayName("releaseSessionLock_callsUnderlyingRelease")
        void releaseSessionLock_callsUnderlyingRelease() {
            long lockId = service.lockIdFor(SCHEDULER_NAME);

            service.releaseSessionLock(SCHEDULER_NAME);

            verify(advisoryLockRepo).releaseAdvisoryLock(lockId);
        }
    }

    @Nested
    @DisplayName("LockID — deterministic derivation")
    class LockId {

        @Test
        @DisplayName("lockIdFor_sameName_sameId — stable across calls")
        void lockIdFor_sameName_sameId() {
            long id1 = service.lockIdFor("dunning-daily");
            long id2 = service.lockIdFor("dunning-daily");

            assertThat(id1).isEqualTo(id2).isNotNegative();
        }

        @Test
        @DisplayName("lockIdFor_differentNames_differentIds — no collision")
        void lockIdFor_differentNames_differentIds() {
            long idA = service.lockIdFor("subscription-renewal");
            long idB = service.lockIdFor("idempotency-cleanup");

            assertThat(idA).isNotEqualTo(idB);
        }

        @Test
        @DisplayName("lockIdFor_alwaysNonNegative — Math.abs applied")
        void lockIdFor_alwaysNonNegative() {
            assertThat(service.lockIdFor("x")).isNotNegative();
            assertThat(service.lockIdFor("any-scheduler-name-with-hyphens")).isNotNegative();
            assertThat(service.lockIdFor("a")).isNotNegative();
        }
    }
}
