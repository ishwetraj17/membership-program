package com.firstclub.platform.ops.service.impl;

import com.firstclub.platform.ops.entity.JobLock;
import com.firstclub.platform.ops.repository.JobLockRepository;
import com.firstclub.platform.ops.service.JobLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobLockServiceImpl implements JobLockService {

    private final JobLockRepository jobLockRepository;

    /**
     * Acquires the lock using a two-step approach:
     * <ol>
     *   <li>If the row doesn't exist, insert a new locked row.</li>
     *   <li>If the row exists but the lock is free/expired, update atomically.</li>
     * </ol>
     * Not perfectly atomic across pods for the INSERT path, but acceptable
     * for low-contention scheduler coordination.
     * A high-volume deployment should migrate to ShedLock instead.
     */
    @Override
    @Transactional
    public boolean acquireLock(String jobName, String lockedBy, LocalDateTime until) {
        LocalDateTime now = LocalDateTime.now();

        if (!jobLockRepository.existsById(jobName)) {
            try {
                JobLock lock = JobLock.builder()
                        .jobName(jobName)
                        .lockedBy(lockedBy)
                        .lockedUntil(until)
                        .build();
                jobLockRepository.saveAndFlush(lock);
                log.debug("JobLock '{}' acquired by '{}' (new row)", jobName, lockedBy);
                return true;
            } catch (DataIntegrityViolationException e) {
                // Another pod inserted concurrently — fall through to the UPDATE path
                log.debug("JobLock '{}' concurrent insert detected; falling through to UPDATE", jobName);
            }
        }

        int updated = jobLockRepository.tryUpdateLock(jobName, lockedBy, until, now);
        if (updated > 0) {
            log.debug("JobLock '{}' acquired by '{}' via UPDATE", jobName, lockedBy);
            return true;
        }

        log.debug("JobLock '{}' is currently held; acquisition by '{}' failed", jobName, lockedBy);
        return false;
    }

    @Override
    @Transactional
    public void releaseLock(String jobName, String lockedBy) {
        int updated = jobLockRepository.tryReleaseLock(jobName, lockedBy);
        if (updated == 0) {
            log.warn("Cannot release job lock '{}' — not held by '{}'", jobName, lockedBy);
        } else {
            log.debug("JobLock '{}' released by '{}'", jobName, lockedBy);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobLock> listLocks() {
        return jobLockRepository.findAllByOrderByJobNameAsc();
    }
}
