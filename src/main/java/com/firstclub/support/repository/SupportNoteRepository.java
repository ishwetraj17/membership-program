package com.firstclub.support.repository;

import com.firstclub.support.entity.SupportNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupportNoteRepository extends JpaRepository<SupportNote, Long> {

    List<SupportNote> findByCaseIdOrderByCreatedAtAsc(Long caseId);
}
