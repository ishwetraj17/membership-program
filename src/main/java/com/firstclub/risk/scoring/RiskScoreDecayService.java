package com.firstclub.risk.scoring;

import com.firstclub.risk.entity.RiskEvent;
import com.firstclub.risk.repository.RiskEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Applies time-based half-life decay to risk scores.
 *
 * <p>Formula: {@code decayedScore = baseScore × 0.5^(ageHours / halfLifeHours)}
 *
 * <p>At age 0 h the score equals the base. At age == halfLifeHours the score
 * is halved. Default half-life is 72 hours.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskScoreDecayService {

    /** Default half-life: score halves every 72 hours. */
    public static final double DEFAULT_HALF_LIFE_HOURS = 72.0;

    private final RiskEventRepository riskEventRepository;

    /**
     * Compute the decayed score for a single base score given the age of the event.
     *
     * @param baseScore       raw score assigned at evaluation time
     * @param eventCreatedAt  when the event was originally recorded
     * @param halfLifeHours   half-life in hours (must be > 0)
     * @return decayed score (always >= 0, rounded to nearest int)
     */
    public int decayedScore(int baseScore, LocalDateTime eventCreatedAt, double halfLifeHours) {
        if (halfLifeHours <= 0) {
            throw new IllegalArgumentException("halfLifeHours must be positive, got: " + halfLifeHours);
        }
        double ageHours = Duration.between(eventCreatedAt, LocalDateTime.now()).toMinutes() / 60.0;
        if (ageHours < 0) {
            ageHours = 0;
        }
        double factor = Math.pow(0.5, ageHours / halfLifeHours);
        return Math.max(0, (int) Math.round(baseScore * factor));
    }

    /**
     * Convenience overload using the default 72-hour half-life.
     */
    public int decayedScore(int baseScore, LocalDateTime eventCreatedAt) {
        return decayedScore(baseScore, eventCreatedAt, DEFAULT_HALF_LIFE_HOURS);
    }

    /**
     * Recompute and persist {@code decayedScore} for every event in the list
     * whose {@code baseScore} is non-null. Events without a {@code baseScore}
     * are skipped.
     *
     * @param events the events to update
     * @return the events after save (same list, mutated in-place)
     */
    @Transactional
    public List<RiskEvent> decayAll(List<RiskEvent> events) {
        int updated = 0;
        for (RiskEvent event : events) {
            if (event.getBaseScore() == null) {
                continue;
            }
            int fresh = decayedScore(event.getBaseScore(), event.getCreatedAt());
            event.setDecayedScore(fresh);
            updated++;
        }
        if (updated > 0) {
            riskEventRepository.saveAll(events);
            log.debug("Refreshed decayed scores for {} risk events", updated);
        }
        return events;
    }
}
