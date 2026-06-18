package com.firstclub.membership.service;

import com.firstclub.membership.dto.CreateIntroOfferRequest;
import com.firstclub.membership.dto.IntroOfferDTO;
import com.firstclub.membership.entity.IntroductoryOffer;

import java.util.List;

/**
 * Manages introductory (first-period) offers and resolves them for the subscription flow.
 */
public interface IntroductoryOfferService {

    /** Resolve an active offer valid for the plan, or throw {@code INVALID_INTRO_OFFER}. */
    IntroductoryOffer resolve(String code, Long planId);

    IntroOfferDTO create(CreateIntroOfferRequest request);

    List<IntroOfferDTO> list();
}
