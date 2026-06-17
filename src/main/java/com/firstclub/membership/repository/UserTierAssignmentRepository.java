package com.firstclub.membership.repository;

import com.firstclub.membership.entity.UserTierAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserTierAssignmentRepository extends JpaRepository<UserTierAssignment, Long> {

    Optional<UserTierAssignment> findByUser_Id(Long userId);

    // JOIN FETCH the tier so the DTO mapping can read tier name/level without a lazy load.
    @Query("SELECT a FROM UserTierAssignment a JOIN FETCH a.tier WHERE a.user.id = :userId")
    Optional<UserTierAssignment> findByUserIdFetchTier(@Param("userId") Long userId);
}
