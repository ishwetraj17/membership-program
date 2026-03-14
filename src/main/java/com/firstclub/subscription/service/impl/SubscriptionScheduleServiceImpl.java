package com.firstclub.subscription.service.impl;

import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;
import com.firstclub.subscription.entity.SubscriptionSchedule;
import com.firstclub.subscription.entity.SubscriptionScheduleStatus;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.exception.SubscriptionException;
import com.firstclub.subscription.mapper.SubscriptionScheduleMapper;
import com.firstclub.subscription.repository.SubscriptionScheduleRepository;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.firstclub.subscription.service.SubscriptionScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link SubscriptionScheduleService}.
 *
 * <p>Business rules enforced:
 * <ul>
 *   <li>{@code effectiveAt} must be in the future (allowing no past scheduling).</li>
 *   <li>Schedules cannot be added to terminal subscriptions.</li>
 *   <li>No two {@code SCHEDULED} entries at the exact same timestamp for the same subscription.</li>
 *   <li>Only {@code SCHEDULED} entries can be cancelled; others are immutable.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionScheduleServiceImpl implements SubscriptionScheduleService {

    private final SubscriptionScheduleRepository scheduleRepository;
    private final SubscriptionV2Repository subscriptionRepository;
    private final SubscriptionScheduleMapper mapper;

    @Override
    @Transactional
    public SubscriptionScheduleResponseDTO createSchedule(Long merchantId, Long subscriptionId,
                                                           SubscriptionScheduleCreateRequestDTO request) {
        log.info("Creating schedule for merchantId={}, subscriptionId={}, action={}, effectiveAt={}",
                merchantId, subscriptionId, request.getScheduledAction(), request.getEffectiveAt());

        // Acquire a pessimistic lock on the subscription to serialise concurrent
        // schedule creation and prevent duplicate SCHEDULED entries at the same
        // effectiveAt from passing the check-then-insert window.
        SubscriptionV2 subscription = subscriptionRepository
                .findByMerchantIdAndIdForUpdate(merchantId, subscriptionId)
                .orElseThrow(() -> SubscriptionException.notFound(merchantId, subscriptionId));

        // Guard: terminal subscriptions cannot receive new schedules
        if (subscription.getStatus().isTerminal()) {
            throw SubscriptionException.terminalSubscription(subscriptionId);
        }

        // Guard: effectiveAt must be in the future
        if (!request.getEffectiveAt().isAfter(LocalDateTime.now())) {
            throw SubscriptionException.scheduleEffectiveAtInPast();
        }

        // Truncate to microseconds for both storage and comparison since
        // PostgreSQL stores timestamps with microsecond precision.
        // Without this, Java's nanosecond-precision values (e.g. 424122881 ns)
        // get rounded by PostgreSQL to the nearest microsecond (e.g. 424123 µs),
        // causing the duplicate check to miss a match when comparing the
        // truncated request value (424122 µs) against the stored rounded
        // value (424123 µs).
        LocalDateTime requestEffective = request.getEffectiveAt().truncatedTo(ChronoUnit.MICROS);

        // Guard: no duplicate SCHEDULED entry at the same effectiveAt
        List<SubscriptionSchedule> existing = scheduleRepository
                .findBySubscriptionIdOrderByEffectiveAtAsc(subscriptionId);
        boolean conflict = existing.stream()
                .filter(s -> s.getStatus() == SubscriptionScheduleStatus.SCHEDULED)
                .anyMatch(s -> s.getEffectiveAt().truncatedTo(ChronoUnit.MICROS).equals(requestEffective));
        if (conflict) {
            throw SubscriptionException.duplicateScheduleConflict(request.getEffectiveAt());
        }

        SubscriptionSchedule schedule = mapper.toEntity(request);
        // Store the truncated value to ensure DB-stored precision matches
        // the comparison precision used in the duplicate check above.
        schedule.setEffectiveAt(requestEffective);
        schedule.setSubscription(subscription);
        schedule.setStatus(SubscriptionScheduleStatus.SCHEDULED);

        SubscriptionSchedule saved = scheduleRepository.save(schedule);
        log.info("Schedule created: id={}, subscriptionId={}", saved.getId(), subscriptionId);
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionScheduleResponseDTO> listSchedulesForSubscription(Long merchantId, Long subscriptionId) {
        // Validate tenant ownership
        loadSubscriptionOrThrow(merchantId, subscriptionId);
        return scheduleRepository.findBySubscriptionIdOrderByEffectiveAtAsc(subscriptionId)
                .stream()
                .map(mapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SubscriptionScheduleResponseDTO cancelSchedule(Long merchantId, Long subscriptionId, Long scheduleId) {
        log.info("Cancelling schedule id={} for subscriptionId={}", scheduleId, subscriptionId);

        // Validate tenant ownership
        loadSubscriptionOrThrow(merchantId, subscriptionId);

        SubscriptionSchedule schedule = scheduleRepository
                .findByIdAndSubscriptionId(scheduleId, subscriptionId)
                .orElseThrow(() -> SubscriptionException.scheduleNotFound(scheduleId));

        if (schedule.getStatus() != SubscriptionScheduleStatus.SCHEDULED) {
            throw SubscriptionException.scheduleNotCancellable(scheduleId);
        }

        schedule.setStatus(SubscriptionScheduleStatus.CANCELLED);
        return mapper.toResponseDTO(scheduleRepository.save(schedule));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private SubscriptionV2 loadSubscriptionOrThrow(Long merchantId, Long subscriptionId) {
        return subscriptionRepository.findByMerchantIdAndId(merchantId, subscriptionId)
                .orElseThrow(() -> SubscriptionException.notFound(merchantId, subscriptionId));
    }
}
