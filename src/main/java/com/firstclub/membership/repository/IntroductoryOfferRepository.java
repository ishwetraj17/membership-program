package com.firstclub.membership.repository;

import com.firstclub.membership.entity.IntroductoryOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IntroductoryOfferRepository extends JpaRepository<IntroductoryOffer, Long> {

    Optional<IntroductoryOffer> findByCodeAndActiveTrue(String code);

    boolean existsByCode(String code);
}
