package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.TierEligibilityResult;
import com.firstclub.membership.dto.UserTierAssignmentDTO;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.entity.UserTierAssignment;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.repository.UserTierAssignmentRepository;
import com.firstclub.membership.service.TierEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Re-evaluates a single user's earned tier in its own transaction.
 *
 * Separate bean so {@code @Transactional(REQUIRES_NEW)} is honoured through the proxy:
 * in the batch path one user's failure rolls back only its own transaction, never the rest.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EarnedTierProcessor {

    private final UserRepository userRepository;
    private final MembershipTierRepository tierRepository;
    private final UserTierAssignmentRepository assignmentRepository;
    private final TierEvaluationService tierEvaluationService;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserTierAssignmentDTO reassign(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> MembershipException.userNotFound(userId));

        TierEligibilityResult eval = tierEvaluationService.evaluateEligibleTier(userId);
        MembershipTier tier = tierRepository.findByName(eval.getEligibleTierName())
                .orElseThrow(() -> MembershipException.tierNotFound(eval.getEligibleTierName()));

        UserTierAssignment assignment = assignmentRepository.findByUser_Id(userId)
                .orElseGet(() -> UserTierAssignment.builder().user(user).build());

        MembershipTier previous = assignment.getTier();
        assignment.setTier(tier);
        assignment.setSource(UserTierAssignment.Source.AUTO);
        assignment.setEvaluatedAt(LocalDateTime.now(clock));
        UserTierAssignment saved = assignmentRepository.save(assignment);

        if (previous != null && !previous.getId().equals(tier.getId())) {
            log.info("User {} earned-tier changed: {} -> {}", userId, previous.getName(), tier.getName());
        }

        return UserTierAssignmentDTO.builder()
                .userId(userId)
                .earnedTierName(tier.getName())
                .earnedTierLevel(tier.getLevel())
                .source(saved.getSource())
                .evaluatedAt(saved.getEvaluatedAt())
                .evaluationNote(eval.getEvaluationNote())
                .build();
    }
}
