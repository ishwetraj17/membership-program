package com.firstclub.payments.repository;

import com.firstclub.payments.entity.DeadLetterMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DeadLetterMessageRepository extends JpaRepository<DeadLetterMessage, Long> {

    List<DeadLetterMessage> findBySource(String source);

    List<DeadLetterMessage> findByFailureCategory(String failureCategory);

    List<DeadLetterMessage> findBySourceAndFailureCategory(String source, String failureCategory);

    /** Groups DLQ entries by failure category for the ops summary dashboard. */
    @Query("SELECT d.failureCategory, COUNT(d) FROM DeadLetterMessage d GROUP BY d.failureCategory ORDER BY COUNT(d) DESC")
    List<Object[]> countGroupedByFailureCategory();

    /** Groups DLQ entries by source (OUTBOX / WEBHOOK) for the ops summary dashboard. */
    @Query("SELECT d.source, COUNT(d) FROM DeadLetterMessage d GROUP BY d.source ORDER BY COUNT(d) DESC")
    List<Object[]> countGroupedBySource();
}
