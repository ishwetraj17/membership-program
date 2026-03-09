package com.firstclub.catalog.repository;

import com.firstclub.catalog.entity.PriceVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link PriceVersion}.
 */
public interface PriceVersionRepository extends JpaRepository<PriceVersion, Long> {

    /** All versions for a price, newest effective_from first. */
    List<PriceVersion> findByPriceIdOrderByEffectiveFromDesc(Long priceId);

    /**
     * The most recently started version that is at or before the given timestamp —
     * i.e. the version that was commercially effective at {@code asOf}.
     */
    Optional<PriceVersion> findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long priceId, LocalDateTime asOf);

    /**
     * The latest version with {@code effective_from} strictly after the given
     * timestamp — used to detect overlap when scheduling a future version.
     */
    Optional<PriceVersion> findTopByPriceIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(
            Long priceId, LocalDateTime after);

    /** The open-ended (current) version — where effective_to is null. */
    Optional<PriceVersion> findTopByPriceIdAndEffectiveToIsNullOrderByEffectiveFromDesc(Long priceId);
}
