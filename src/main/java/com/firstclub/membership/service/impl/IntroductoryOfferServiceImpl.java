package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.CreateIntroOfferRequest;
import com.firstclub.membership.dto.IntroOfferDTO;
import com.firstclub.membership.entity.IntroductoryOffer;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.IntroductoryOfferRepository;
import com.firstclub.membership.service.IntroductoryOfferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IntroductoryOfferServiceImpl implements IntroductoryOfferService {

    private final IntroductoryOfferRepository repository;

    @Override
    @Transactional(readOnly = true)
    public IntroductoryOffer resolve(String code, Long planId) {
        IntroductoryOffer offer = repository.findByCodeAndActiveTrue(code.toUpperCase())
                .orElseThrow(() -> new MembershipException(
                        "Introductory offer '" + code + "' is not valid", "INVALID_INTRO_OFFER", HttpStatus.NOT_FOUND));
        if (offer.getPlanId() != null && !offer.getPlanId().equals(planId)) {
            throw new MembershipException(
                    "Introductory offer '" + code + "' does not apply to this plan", "INVALID_INTRO_OFFER");
        }
        return offer;
    }

    @Override
    @Transactional
    public IntroOfferDTO create(CreateIntroOfferRequest request) {
        String code = request.getCode().toUpperCase();
        if (repository.existsByCode(code)) {
            throw new MembershipException("Introductory offer '" + code + "' already exists",
                    "INTRO_OFFER_EXISTS", HttpStatus.CONFLICT);
        }
        if (request.getOfferType() != IntroductoryOffer.OfferType.FREE && request.getValue() == null) {
            throw new MembershipException("value is required for " + request.getOfferType() + " offers",
                    "INVALID_INTRO_OFFER");
        }
        IntroductoryOffer saved = repository.save(IntroductoryOffer.builder()
                .code(code)
                .description(request.getDescription())
                .offerType(request.getOfferType())
                .value(request.getValue())
                .planId(request.getPlanId())
                .active(true)
                .build());
        return toDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IntroOfferDTO> list() {
        return repository.findAll().stream().map(this::toDTO).toList();
    }

    private IntroOfferDTO toDTO(IntroductoryOffer o) {
        return IntroOfferDTO.builder()
                .id(o.getId()).code(o.getCode()).description(o.getDescription())
                .offerType(o.getOfferType()).value(o.getValue()).planId(o.getPlanId()).active(o.isActive())
                .build();
    }
}
