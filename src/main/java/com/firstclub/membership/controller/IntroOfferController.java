package com.firstclub.membership.controller;

import com.firstclub.membership.dto.CreateIntroOfferRequest;
import com.firstclub.membership.dto.IntroOfferDTO;
import com.firstclub.membership.service.IntroductoryOfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin configuration of introductory (first-period) offers — ₹1 first month, 50% off first month,
 * free first month, etc. Secured to ADMIN by the {@code /api/v1/admin/**} matcher.
 */
@RestController
@RequestMapping("/api/v1/admin/intro-offers")
@RequiredArgsConstructor
@Tag(name = "Admin — Introductory Offers", description = "Configure first-period acquisition offers")
public class IntroOfferController {

    private final IntroductoryOfferService introductoryOfferService;

    @GetMapping
    @Operation(summary = "List introductory offers (admin)")
    public ResponseEntity<List<IntroOfferDTO>> list() {
        return ResponseEntity.ok(introductoryOfferService.list());
    }

    @PostMapping
    @Operation(summary = "Create an introductory offer (admin)")
    public ResponseEntity<IntroOfferDTO> create(@Valid @RequestBody CreateIntroOfferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(introductoryOfferService.create(request));
    }
}
