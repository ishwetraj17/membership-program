package com.firstclub.platform.ops.repository;

import com.firstclub.platform.ops.entity.JobLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface JobLockRepository extends JpaRepository<JobLock, String> {

    /**
     * Attempts to claim the lock atomically. Updates the row only when
     * it is currently free (locked_until IS NULL or already expired).
     * Returns the number of rows updated (1 = acquired, 0 = lock held).
     */
    @Modifying
    @Query("""
            UPDATE JobLock j
               SET j.lockedBy    = :lockedBy,
                   j.lockedUntil = :until
             WHERE j.jobName = :jobName
               AND (j.lockedUntil IS NULL OR j.lockedUntil < :now)
            """)
    int tryUpdateLock(@Param("jobName")  String        jobName,
                      @Param("lockedBy") String        lockedBy,
                      @Param("until")    LocalDateTime until,
                      @Param("now")      LocalDateTime now);

    /**
     * Releases the lock only if the caller is the current owner.
     * Returns 1 if released, 0 if not the owner or row absent.
     */
    @Modifying
    @Query("""
            UPDATE JobLock j
               SET j.lockedBy    = NULL,
                   j.lockedUntil = NULL
             WHERE j.jobName = :jobName
               AND j.lockedBy = :lockedBy
            """)
    int tryReleaseLock(@Param("jobName")  String jobName,
                       @Param("lockedBy") String lockedBy);

    List<JobLock> findAllByOrderByJobNameAsc();
}
