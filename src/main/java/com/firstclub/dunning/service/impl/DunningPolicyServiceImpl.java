package com.firstclub.dunning.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.dunning.dto.DunningPolicyCreateRequestDTO;
import com.firstclub.dunning.dto.DunningPolicyResponseDTO;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.service.DunningPolicyService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DunningPolicyServiceImpl implements DunningPolicyService {

    /** Baseline retry offsets (minutes) used when auto-creating DEFAULT policy. */
    private static final String DEFAULT_OFFSETS = "[60, 360, 1440, 4320]";
    private static final int    DEFAULT_MAX      = 4;
    private static final int    DEFAULT_GRACE    = 7;

    private final DunningPolicyRepository policyRepository;
    private final ObjectMapper objectMapper;

    // ── createPolicy ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DunningPolicyResponseDTO createPolicy(Long merchantId,
                                                 DunningPolicyCreateRequestDTO request) {
        validatePolicyPayload(request);

        if (policyRepository.findByMerchantIdAndPolicyCode(merchantId, request.getPolicyCode()).isPresent()) {
            throw new MembershipException(
                    "Dunning policy '" + request.getPolicyCode() + "' already exists for this merchant",
                    "DUPLICATE_POLICY_CODE", HttpStatus.CONFLICT);
        }

        DunningPolicy policy = DunningPolicy.builder()
                .merchantId(merchantId)
                .policyCode(request.getPolicyCode())
                .retryOffsetsJson(request.getRetryOffsetsJson())
                .maxAttempts(request.getMaxAttempts())
                .graceDays(request.getGraceDays())
                .fallbackToBackupPaymentMethod(request.isFallbackToBackupPaymentMethod())
                .statusAfterExhaustion(DunningTerminalStatus.valueOf(request.getStatusAfterExhaustion()))
                .build();

        DunningPolicyResponseDTO result = toDto(policyRepository.save(policy));
        log.info("Created dunning policy '{}' for merchant {}", policy.getPolicyCode(), merchantId);
        return result;
    }

    // ── listPolicies ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DunningPolicyResponseDTO> listPolicies(Long merchantId) {
        return policyRepository.findByMerchantId(merchantId).stream()
                .map(this::toDto)
                .toList();
    }

    // ── getPolicyByCode ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DunningPolicyResponseDTO getPolicyByCode(Long merchantId, String policyCode) {
        return toDto(loadPolicy(merchantId, policyCode));
    }

    // ── getPolicyById ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DunningPolicyResponseDTO getPolicyById(Long merchantId, Long policyId) {
        DunningPolicy policy = policyRepository.findById(policyId)
                .filter(p -> p.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new MembershipException(
                        "Dunning policy " + policyId + " not found for merchant " + merchantId,
                        "DUNNING_POLICY_NOT_FOUND", HttpStatus.NOT_FOUND));
        return toDto(policy);
    }

    // ── resolvePolicy ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DunningPolicy resolvePolicy(Long merchantId) {
        // 1. Explicit DEFAULT policy
        return policyRepository.findByMerchantIdAndPolicyCode(merchantId, "DEFAULT")
                // 2. Any other policy for this merchant
                .or(() -> policyRepository.findByMerchantId(merchantId).stream().findFirst())
                // 3. Auto-create baseline DEFAULT
                .orElseGet(() -> {
                    DunningPolicy auto = DunningPolicy.builder()
                            .merchantId(merchantId)
                            .policyCode("DEFAULT")
                            .retryOffsetsJson(DEFAULT_OFFSETS)
                            .maxAttempts(DEFAULT_MAX)
                            .graceDays(DEFAULT_GRACE)
                            .fallbackToBackupPaymentMethod(false)
                            .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED)
                            .build();
                    DunningPolicy saved = policyRepository.save(auto);
                    log.info("Auto-created DEFAULT dunning policy for merchant {}", merchantId);
                    return saved;
                });
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validatePolicyPayload(DunningPolicyCreateRequestDTO req) {
        // Parse and validate retry offsets
        List<Integer> offsets;
        try {
            offsets = objectMapper.readValue(req.getRetryOffsetsJson(),
                    new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            throw new MembershipException(
                    "retryOffsetsJson must be a valid JSON array of integers (e.g. \"[60, 360, 1440]\")",
                    "INVALID_RETRY_OFFSETS_JSON", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (offsets.isEmpty()) {
            throw new MembershipException(
                    "retryOffsetsJson must contain at least one offset",
                    "INVALID_RETRY_OFFSETS_JSON", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        for (int o : offsets) {
            if (o <= 0) {
                throw new MembershipException(
                        "Each retry offset must be a positive integer (got " + o + ")",
                        "INVALID_RETRY_OFFSETS_JSON", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        // Validate maxAttempts
        if (req.getMaxAttempts() <= 0) {
            throw new MembershipException(
                    "maxAttempts must be positive",
                    "INVALID_MAX_ATTEMPTS", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Validate statusAfterExhaustion value
        try {
            DunningTerminalStatus.valueOf(req.getStatusAfterExhaustion());
        } catch (IllegalArgumentException e) {
            throw new MembershipException(
                    "statusAfterExhaustion must be SUSPENDED or CANCELLED",
                    "INVALID_TERMINAL_STATUS", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DunningPolicy loadPolicy(Long merchantId, String policyCode) {
        return policyRepository.findByMerchantIdAndPolicyCode(merchantId, policyCode)
                .orElseThrow(() -> new MembershipException(
                        "Dunning policy '" + policyCode + "' not found for merchant " + merchantId,
                        "DUNNING_POLICY_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    /** Parse the JSON offsets array — throws MembershipException on malformed input. */
    public List<Integer> parseOffsets(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            throw new MembershipException(
                    "Invalid retry_offsets_json: " + json,
                    "INVALID_RETRY_OFFSETS_JSON", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private DunningPolicyResponseDTO toDto(DunningPolicy p) {
        return DunningPolicyResponseDTO.builder()
                .id(p.getId())
                .merchantId(p.getMerchantId())
                .policyCode(p.getPolicyCode())
                .retryOffsetsJson(p.getRetryOffsetsJson())
                .maxAttempts(p.getMaxAttempts())
                .graceDays(p.getGraceDays())
                .fallbackToBackupPaymentMethod(p.isFallbackToBackupPaymentMethod())
                .statusAfterExhaustion(p.getStatusAfterExhaustion())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
