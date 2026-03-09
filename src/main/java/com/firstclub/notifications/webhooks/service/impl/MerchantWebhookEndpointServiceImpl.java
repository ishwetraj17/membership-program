package com.firstclub.notifications.webhooks.service.impl;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointCreateRequestDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookEndpointResponseDTO;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookEndpointRepository;
import com.firstclub.notifications.webhooks.service.MerchantWebhookEndpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantWebhookEndpointServiceImpl implements MerchantWebhookEndpointService {

    private final MerchantWebhookEndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;

    // ── createEndpoint ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public MerchantWebhookEndpointResponseDTO createEndpoint(Long merchantId,
            MerchantWebhookEndpointCreateRequestDTO request) {
        validateRequest(request);
        String secret = StringUtils.hasText(request.getSecret())
                ? request.getSecret()
                : generateSecret();

        MerchantWebhookEndpoint endpoint = MerchantWebhookEndpoint.builder()
                .merchantId(merchantId)
                .url(request.getUrl())
                .secret(secret)
                .active(request.isActive())
                .subscribedEventsJson(request.getSubscribedEventsJson())
                .build();

        MerchantWebhookEndpoint saved = endpointRepository.save(endpoint);
        log.info("Created webhook endpoint id={} for merchant={}", saved.getId(), merchantId);
        return toDto(saved);
    }

    // ── listEndpoints ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<MerchantWebhookEndpointResponseDTO> listEndpoints(Long merchantId) {
        return endpointRepository.findByMerchantId(merchantId)
                .stream().map(this::toDto).toList();
    }

    // ── updateEndpoint ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public MerchantWebhookEndpointResponseDTO updateEndpoint(Long merchantId, Long endpointId,
            MerchantWebhookEndpointCreateRequestDTO request) {
        validateRequest(request);
        MerchantWebhookEndpoint endpoint = requireOwned(merchantId, endpointId);

        endpoint.setUrl(request.getUrl());
        endpoint.setActive(request.isActive());
        endpoint.setSubscribedEventsJson(request.getSubscribedEventsJson());
        if (StringUtils.hasText(request.getSecret())) {
            endpoint.setSecret(request.getSecret());
        }

        return toDto(endpointRepository.save(endpoint));
    }

    // ── deactivateEndpoint ────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deactivateEndpoint(Long merchantId, Long endpointId) {
        MerchantWebhookEndpoint endpoint = requireOwned(merchantId, endpointId);
        endpoint.setActive(false);
        endpointRepository.save(endpoint);
        log.info("Deactivated webhook endpoint id={} for merchant={}", endpointId, merchantId);
    }

    // ── reenableEndpoint ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public void reenableEndpoint(Long merchantId, Long endpointId) {
        MerchantWebhookEndpoint endpoint = requireOwned(merchantId, endpointId);
        endpoint.setActive(true);
        endpoint.setAutoDisabledAt(null);
        endpoint.setConsecutiveFailures(0);
        endpointRepository.save(endpoint);
        log.info("Re-enabled webhook endpoint id={} for merchant={}", endpointId, merchantId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MerchantWebhookEndpoint requireOwned(Long merchantId, Long endpointId) {
        return endpointRepository.findByMerchantIdAndId(merchantId, endpointId)
                .orElseThrow(() -> new MembershipException(
                        "Webhook endpoint " + endpointId + " not found for merchant " + merchantId,
                        "WEBHOOK_ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private void validateRequest(MerchantWebhookEndpointCreateRequestDTO request) {
        if (!StringUtils.hasText(request.getUrl())) {
            throw new MembershipException("URL is required",
                    "INVALID_WEBHOOK_URL", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String url = request.getUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new MembershipException("URL must start with http:// or https://",
                    "INVALID_WEBHOOK_URL", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (!StringUtils.hasText(request.getSubscribedEventsJson())) {
            throw new MembershipException("subscribedEventsJson is required",
                    "INVALID_SUBSCRIBED_EVENTS", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        try {
            List<?> events = objectMapper.readValue(request.getSubscribedEventsJson(), List.class);
            if (events.isEmpty()) {
                throw new MembershipException(
                        "subscribedEventsJson must contain at least one event type",
                        "INVALID_SUBSCRIBED_EVENTS", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } catch (MembershipException e) {
            throw e;
        } catch (Exception e) {
            throw new MembershipException(
                    "subscribedEventsJson must be a valid JSON array",
                    "INVALID_SUBSCRIBED_EVENTS", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private MerchantWebhookEndpointResponseDTO toDto(MerchantWebhookEndpoint ep) {
        return MerchantWebhookEndpointResponseDTO.builder()
                .id(ep.getId())
                .merchantId(ep.getMerchantId())
                .url(ep.getUrl())
                .active(ep.isActive())
                .subscribedEventsJson(ep.getSubscribedEventsJson())
                .consecutiveFailures(ep.getConsecutiveFailures())
                .autoDisabledAt(ep.getAutoDisabledAt())
                .createdAt(ep.getCreatedAt())
                .updatedAt(ep.getUpdatedAt())
                .build();
    }
}
