package com.firstclub.platform.ops;

import com.firstclub.platform.ops.entity.JobLock;
import com.firstclub.platform.ops.repository.JobLockRepository;
import com.firstclub.platform.ops.service.impl.JobLockServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobLockService — Unit Tests")
class JobLockServiceTest {

    @Mock JobLockRepository   jobLockRepository;
    @InjectMocks JobLockServiceImpl service;

    private static final LocalDateTime FUTURE = LocalDateTime.now().plusMinutes(5);

    @Nested
    @DisplayName("acquireLock")
    class AcquireLock {

        @Test
        @DisplayName("creates and returns true when no row exists")
        void returnsTrue_whenRowDoesNotExist() {
            when(jobLockRepository.existsById("RENEWAL_JOB")).thenReturn(false);
            when(jobLockRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean acquired = service.acquireLock("RENEWAL_JOB", "pod-1", FUTURE);

            assertThat(acquired).isTrue();
            verify(jobLockRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("updates and returns true when lock exists but is expired")
        void returnsTrue_whenLockExpired() {
            when(jobLockRepository.existsById("DUNNING_JOB")).thenReturn(true);
            when(jobLockRepository.tryUpdateLock(eq("DUNNING_JOB"), eq("pod-2"), any(), any()))
                    .thenReturn(1);

            boolean acquired = service.acquireLock("DUNNING_JOB", "pod-2", FUTURE);

            assertThat(acquired).isTrue();
        }

        @Test
        @DisplayName("returns false when lock is currently held by another pod")
        void returnsFalse_whenLockHeld() {
            when(jobLockRepository.existsById("RECONCILIATION_JOB")).thenReturn(true);
            when(jobLockRepository.tryUpdateLock(eq("RECONCILIATION_JOB"), any(), any(), any()))
                    .thenReturn(0);

            boolean acquired = service.acquireLock("RECONCILIATION_JOB", "pod-3", FUTURE);

            assertThat(acquired).isFalse();
        }
    }

    @Nested
    @DisplayName("releaseLock")
    class ReleaseLock {

        @Test
        @DisplayName("nullifies lock when caller is the owner")
        void releasesLock_whenOwnerMatches() {
            when(jobLockRepository.tryReleaseLock("SNAPSHOT_JOB", "pod-1")).thenReturn(1);

            service.releaseLock("SNAPSHOT_JOB", "pod-1");

            verify(jobLockRepository).tryReleaseLock("SNAPSHOT_JOB", "pod-1");
        }

        @Test
        @DisplayName("logs warning but does not throw when caller is not the owner")
        void logsWarning_whenNotOwner() {
            when(jobLockRepository.tryReleaseLock("SNAPSHOT_JOB", "pod-99")).thenReturn(0);

            // Should not throw
            service.releaseLock("SNAPSHOT_JOB", "pod-99");

            verify(jobLockRepository).tryReleaseLock("SNAPSHOT_JOB", "pod-99");
        }
    }

    @Nested
    @DisplayName("listLocks")
    class ListLocks {

        @Test
        @DisplayName("returns all locks in job-name order")
        void returnsAllLocks() {
            JobLock a = JobLock.builder().jobName("A_JOB").build();
            JobLock b = JobLock.builder().jobName("B_JOB").build();
            when(jobLockRepository.findAllByOrderByJobNameAsc()).thenReturn(List.of(a, b));

            List<JobLock> result = service.listLocks();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getJobName()).isEqualTo("A_JOB");
        }
    }
}
