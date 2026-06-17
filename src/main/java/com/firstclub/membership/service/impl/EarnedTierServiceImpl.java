package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.UserTierAssignmentDTO;
import com.firstclub.membership.entity.UserTierAssignment;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.repository.UserTierAssignmentRepository;
import com.firstclub.membership.service.EarnedTierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EarnedTierServiceImpl implements EarnedTierService {

    private static final int BATCH_SIZE = 200;

    private final UserRepository userRepository;
    private final UserTierAssignmentRepository assignmentRepository;
    private final EarnedTierProcessor processor;

    @Override
    public UserTierAssignmentDTO assignEarnedTier(Long userId) {
        // Delegates to the processor so the write runs in its own (REQUIRES_NEW) transaction.
        return processor.reassign(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserTierAssignmentDTO> getEarnedTier(Long userId) {
        Optional<UserTierAssignment> existing = assignmentRepository.findByUserIdFetchTier(userId);
        if (existing.isPresent()) {
            return existing.map(this::toDto);
        }
        // First read for this user — compute and persist in the processor's own transaction.
        return Optional.of(processor.reassign(userId));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int reevaluateAll() {
        // No outer transaction: each user is reassigned in its own REQUIRES_NEW transaction,
        // so one failure never aborts the batch. Paginated to bound memory.
        int processed = 0;
        Pageable page = PageRequest.of(0, BATCH_SIZE, Sort.by("id"));
        Page<Long> ids;
        do {
            ids = userRepository.findAll(page).map(u -> u.getId());
            for (Long userId : ids.getContent()) {
                try {
                    processor.reassign(userId);
                    processed++;
                } catch (Exception e) {
                    log.error("Earned-tier re-evaluation failed for user {} — skipped", userId, e);
                }
            }
            page = page.next();
        } while (ids.hasNext());

        if (processed > 0) log.info("Re-evaluated earned tier for {} user(s).", processed);
        return processed;
    }

    private UserTierAssignmentDTO toDto(UserTierAssignment a) {
        return UserTierAssignmentDTO.builder()
                .userId(a.getUser().getId())
                .earnedTierName(a.getTier().getName())
                .earnedTierLevel(a.getTier().getLevel())
                .source(a.getSource())
                .evaluatedAt(a.getEvaluatedAt())
                .evaluationNote("Last evaluated " + a.getEvaluatedAt())
                .build();
    }
}
