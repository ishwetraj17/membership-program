package com.firstclub.notifications.webhooks.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookDeliveryResponseDTO;
import com.firstclub.notifications.webhooks.dto.MerchantWebhookPingResponseDTO;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookEndpoint;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookEndpointRepository;
import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import com.firstclub.notifications.webhooks.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the full outbound webhook lifecycle:
 * enqueue → sign → dispatch (via {@link WebhookDispatcher}) → retry/backoff → terminal state.
 *
 * <h3>Retry backoff</h3>
 * Retry intervals (seconds) are: 60 / 300 / 900 / 3 600 / 7 200
 * ({@code attemptCount} indexes into {@link #BACKOFF_SECONDS}).
 *
 * <h3>Auto-disable: two independent mechanisms</h3>
 * <ol>
 *   <li><b>Consecutive-failure threshold</b> ({@link #CONSECUTIVE_FAILURE_THRESHOLD}):
 *       incremented on every failed {@link #dispatchOne} call; reset to 0 on any 2xx.
 *       When the counter reaches the threshold, {@code autoDisabledAt} is set and
 *       {@code active} becomes false immediately.</li>
 *   <li><b>GAVE_UP accumulation</b> ({@link #DISABLE_AFTER_GAVE_UP}):
 *       after N deliveries reach GAVE_UP the endpoint is deactivated (secondary guard).</li>
 * </ol>
 *
 * <h3>Idempotent enqueue</h3>
 * A delivery fingerprint (SHA-256 of endpointId|eventType|payload) is stored on each
 * delivery row. Enqueueing skips an endpoint if a {@code DELIVERED} row with the same
 * fingerprint already exists, preventing silent re-delivery.
 *
 * <h3>Processing visibility</h3>
 * {@code processingOwner} and {@code processingStartedAt} are stamped when dispatch
 * begins and cleared after the delivery reaches any terminal/retry state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantWebhookDeliveryServiceImpl implements MerchantWebhookDeliveryService {

    /** Backoff delays in seconds indexed by attemptCount (capped at last entry). */
    private static final int[] BACKOFF_SECONDS = {60, 300, 900, 3_600, 7_200};

    /** Maximum number of delivery attempts before transitioning to GAVE_UP. */
    public static final int MAX_ATTEMPTS = 6;

    /** Number of GAVE_UP deliveries that trigger auto-disable of the endpoint (secondary guard). */
    public static final int DISABLE_AFTER_GAVE_UP = 5;

    /**
     * Number of consecutive failed dispatch calls (any non-2xx or connection error)
     * that trigger endpoint auto-disable (primary, immediate guard).
     */
    public static final int CONSECUTIVE_FAILURE_THRESHOLD = 5;

    /** Synthetic event type sent by {@link #pingEndpoint}. */
    public static final String PING_EVENT_TYPE = "webhook.ping";

    private final MerchantWebhookEndpointRepository endpointRepository;
    private final MerchantWebhookDeliveryRepository deliveryRepository;
    private final WebhookDispatcher                  webhookDispatcher;
    private final ObjectMapper                        objectMapper;

    // ── enqueueDeliveryForMerchantEvent ───────────────────────────────────────

    @Override
    @Transactional
    public void enqueueDeliveryForMerchantEvent(Long merchantId, String eventType, String payloadJson) {
        List<MerchantWebhookEndpoint> endpoints = endpointRepository.findActiveByMerchantId(merchantId);
        int enqueued = 0;
        for (MerchantWebhookEndpoint endpoint : endpoints) {
            if (!isSubscribed(endpoint, eventType)) {
                continue;
            }
            // Skip endpoints that have been auto-disabled
            if (endpoint.getAutoDisabledAt() != null) {
                log.warn("Skipping auto-disabled endpoint id={} for merchant={}", endpoint.getId(), merchantId);
                continue;
            }
            // Idempotent enqueue: skip if a DELIVERED delivery with the same fingerprint exists
            String fingerprint = computeDeliveryFingerprint(endpoint.getId(), eventType, payloadJson);
            if (deliveryRepository.findTopByDeliveryFingerprintAndStatus(
                    fingerprint, MerchantWebhookDeliveryStatus.DELIVERED).isPresent()) {
                log.debug("Delivery fingerprint already DELIVERED for endpoint={}, skipping", endpoint.getId());
                continue;
            }
            String signature = signPayload(payloadJson, endpoint.getSecret());
            MerchantWebhookDelivery delivery = MerchantWebhookDelivery.builder()
                    .endpointId(endpoint.getId())
                    .eventType(eventType)
                    .payload(payloadJson)
                    .signature(signature)
                    .status(MerchantWebhookDeliveryStatus.PENDING)
                    .attemptCount(0)
                    .nextAttemptAt(LocalDateTime.now())
                    .deliveryFingerprint(fingerprint)
                    .build();
            deliveryRepository.save(delivery);
            enqueued++;
        }
        log.debug("Enqueued {} webhook deliveries for merchant={} event={}",
                enqueued, merchantId, eventType);
    }

    // ── signPayload ───────────────────────────────────────────────────────────

    @Override
    public String signPayload(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }

    // ── retryDueDeliveries ────────────────────────────────────────────────────

    /**
     * Claims and processes due webhook deliveries.
     *
     * <p><b>Concurrency guard:</b> {@code FOR UPDATE SKIP LOCKED} ensures that
     * concurrent scheduler pods each receive a disjoint set of deliveries,
     * preventing double-dispatch of the same delivery.
     *
     * <p>Processing visibility: each claimed delivery has
     * {@code processingStartedAt} and {@code processingOwner} stamped before
     * the HTTP call and cleared on completion.
     */
    @Override
    @Transactional
    public void retryDueDeliveries() {
        List<MerchantWebhookDelivery> due = deliveryRepository
                .findDueForProcessingWithSkipLocked(LocalDateTime.now(), 100);
        if (due.isEmpty()) {
            return;
        }
        log.info("Processing {} due webhook deliveries [SKIP LOCKED batch]", due.size());
        for (MerchantWebhookDelivery delivery : due) {
            try {
                dispatchOne(delivery);
            } catch (Exception e) {
                log.error("Unexpected error dispatching delivery {}: {}", delivery.getId(), e.getMessage(), e);
                handleFailure(delivery, null, "Unexpected error: " + e.getMessage());
            }
        }
    }

    // ── listDeliveriesForMerchant ─────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<MerchantWebhookDeliveryResponseDTO> listDeliveriesForMerchant(Long merchantId) {
        return deliveryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)
                .stream().map(this::toDto).toList();
    }

    // ── getDelivery ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public MerchantWebhookDeliveryResponseDTO getDelivery(Long merchantId, Long deliveryId) {
        MerchantWebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new MembershipException(
                        "Webhook delivery " + deliveryId + " not found",
                        "WEBHOOK_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND));

        endpointRepository.findByMerchantIdAndId(merchantId, delivery.getEndpointId())
                .orElseThrow(() -> new MembershipException(
                        "Webhook delivery " + deliveryId + " not found for merchant " + merchantId,
                        "WEBHOOK_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND));

        return toDto(delivery);
    }

    // ── pingEndpoint ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public MerchantWebhookPingResponseDTO pingEndpoint(Long merchantId, Long endpointId) {
        MerchantWebhookEndpoint endpoint = endpointRepository
                .findByMerchantIdAndId(merchantId, endpointId)
                .orElseThrow(() -> new MembershipException(
                        "Webhook endpoint " + endpointId + " not found for merchant " + merchantId,
                        "WEBHOOK_ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND));

        if (!endpoint.isActive()) {
            throw new MembershipException(
                    "Endpoint " + endpointId + " is not active",
                    "WEBHOOK_ENDPOINT_INACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String pingPayload = buildPingPayload(merchantId, endpointId);
        String signature   = signPayload(pingPayload, endpoint.getSecret());

        MerchantWebhookDelivery delivery = deliveryRepository.save(
                MerchantWebhookDelivery.builder()
                        .endpointId(endpointId)
                        .eventType(PING_EVENT_TYPE)
                        .payload(pingPayload)
                        .signature(signature)
                        .status(MerchantWebhookDeliveryStatus.PENDING)
                        .attemptCount(0)
                        .nextAttemptAt(LocalDateTime.now())
                        .build());

        // Dispatch immediately — do not count consecutive failures for ping
        dispatchOnePing(delivery, endpoint);

        boolean success = delivery.getStatus() == MerchantWebhookDeliveryStatus.DELIVERED;
        return MerchantWebhookPingResponseDTO.builder()
                .deliveryId(delivery.getId())
                .endpointId(endpointId)
                .status(delivery.getStatus().name())
                .message(success ? "Ping delivered successfully"
                                 : "Ping failed: " + delivery.getLastError())
                .build();
    }

    // ── searchDeliveries ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<MerchantWebhookDeliveryResponseDTO> searchDeliveries(
            Long merchantId, String eventType, String status,
            Integer responseCode, LocalDateTime from, LocalDateTime to, int limit) {

        MerchantWebhookDeliveryStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = MerchantWebhookDeliveryStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new MembershipException(
                        "Unknown delivery status: " + status,
                        "INVALID_DELIVERY_STATUS", HttpStatus.BAD_REQUEST);
            }
        }
        int effectiveLimit = Math.min(limit > 0 ? limit : 50, 500);
        return deliveryRepository.searchDeliveries(
                merchantId, eventType, statusEnum, responseCode, from, to,
                PageRequest.of(0, effectiveLimit, Sort.by("createdAt").descending()))
                .stream().map(this::toDto).toList();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void dispatchOne(MerchantWebhookDelivery delivery) {
        MerchantWebhookEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId())
                .orElse(null);
        if (endpoint == null || !endpoint.isActive()) {
            delivery.setStatus(MerchantWebhookDeliveryStatus.GAVE_UP);
            delivery.setLastError("Endpoint not found or disabled");
            delivery.setNextAttemptAt(null);
            delivery.setProcessingStartedAt(null);
            delivery.setProcessingOwner(null);
            deliveryRepository.save(delivery);
            return;
        }
        dispatchWithEndpoint(delivery, endpoint, true);
    }

    /** Ping variant: uses the already-loaded endpoint; does not alter consecutive-failure counters. */
    private void dispatchOnePing(MerchantWebhookDelivery delivery, MerchantWebhookEndpoint endpoint) {
        dispatchWithEndpoint(delivery, endpoint, false);
    }

    private void dispatchWithEndpoint(MerchantWebhookDelivery delivery,
                                       MerchantWebhookEndpoint endpoint,
                                       boolean trackConsecutiveFailures) {
        // Stamp processing lease for visibility
        delivery.setProcessingStartedAt(LocalDateTime.now());
        delivery.setProcessingOwner(processingOwner());
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);

        int statusCode = webhookDispatcher.dispatch(
                endpoint.getUrl(), delivery.getPayload(),
                delivery.getSignature(), delivery.getEventType(), delivery.getId());

        delivery.setLastResponseCode(statusCode > 0 ? statusCode : null);

        if (statusCode >= 200 && statusCode < 300) {
            delivery.setStatus(MerchantWebhookDeliveryStatus.DELIVERED);
            delivery.setNextAttemptAt(null);
            delivery.setProcessingStartedAt(null);
            delivery.setProcessingOwner(null);
            deliveryRepository.save(delivery);
            log.info("Webhook delivery {} → DELIVERED (HTTP {})", delivery.getId(), statusCode);

            if (trackConsecutiveFailures && endpoint.getConsecutiveFailures() > 0) {
                endpoint.setConsecutiveFailures(0);
                endpointRepository.save(endpoint);
            }
        } else {
            String error = statusCode > 0 ? "HTTP " + statusCode : "Connection failure";
            handleFailure(delivery, statusCode > 0 ? statusCode : null, error);
            if (trackConsecutiveFailures) {
                incrementConsecutiveFailures(endpoint);
            }
        }
    }

    private void handleFailure(MerchantWebhookDelivery delivery, Integer responseCode, String error) {
        if (responseCode != null) {
            delivery.setLastResponseCode(responseCode);
        }
        delivery.setLastError(error);
        delivery.setProcessingStartedAt(null);
        delivery.setProcessingOwner(null);

        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            delivery.setStatus(MerchantWebhookDeliveryStatus.GAVE_UP);
            delivery.setNextAttemptAt(null);
            deliveryRepository.save(delivery);
            log.warn("Webhook delivery {} → GAVE_UP after {} attempts",
                    delivery.getId(), delivery.getAttemptCount());
            maybeDisableUnhealthyEndpoint(delivery.getEndpointId());
        } else {
            delivery.setStatus(MerchantWebhookDeliveryStatus.FAILED);
            int delayIdx = Math.min(delivery.getAttemptCount(), BACKOFF_SECONDS.length - 1);
            delivery.setNextAttemptAt(LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[delayIdx]));
            deliveryRepository.save(delivery);
            log.debug("Webhook delivery {} → FAILED (attempt {}), retry in {}s",
                    delivery.getId(), delivery.getAttemptCount(), BACKOFF_SECONDS[delayIdx]);
        }
    }

    /**
     * Increments the consecutive-failure counter on the endpoint and auto-disables
     * it if the threshold is reached.  Called after every non-2xx dispatch outcome.
     */
    private void incrementConsecutiveFailures(MerchantWebhookEndpoint endpoint) {
        endpoint.setConsecutiveFailures(endpoint.getConsecutiveFailures() + 1);
        if (endpoint.getConsecutiveFailures() >= CONSECUTIVE_FAILURE_THRESHOLD
                && endpoint.getAutoDisabledAt() == null) {
            endpoint.setAutoDisabledAt(LocalDateTime.now());
            endpoint.setActive(false);
            log.warn("Auto-disabled webhook endpoint {} after {} consecutive failures",
                    endpoint.getId(), endpoint.getConsecutiveFailures());
        }
        endpointRepository.save(endpoint);
    }

    /** Secondary auto-disable based on accumulated GAVE_UP delivery count. */
    private void maybeDisableUnhealthyEndpoint(Long endpointId) {
        long gaveUpCount = deliveryRepository.countByEndpointIdAndStatus(
                endpointId, MerchantWebhookDeliveryStatus.GAVE_UP);
        if (gaveUpCount >= DISABLE_AFTER_GAVE_UP) {
            endpointRepository.findById(endpointId).ifPresent(ep -> {
                if (ep.isActive()) {
                    ep.setActive(false);
                    endpointRepository.save(ep);
                    log.warn("Auto-disabled webhook endpoint {} after {} GAVE_UP deliveries",
                            endpointId, gaveUpCount);
                }
            });
        }
    }

    private boolean isSubscribed(MerchantWebhookEndpoint endpoint, String eventType) {
        try {
            List<String> subscribed = objectMapper.readValue(
                    endpoint.getSubscribedEventsJson(), new TypeReference<>() {});
            return subscribed.contains("*") || subscribed.contains(eventType);
        } catch (Exception e) {
            log.warn("Failed to parse subscribedEventsJson for endpoint {}: {}",
                    endpoint.getId(), e.getMessage());
            return false;
        }
    }

    /** SHA-256 of {@code endpointId|eventType|payload} — stable fingerprint for idempotency. */
    public static String computeDeliveryFingerprint(Long endpointId, String eventType, String payload) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String input = endpointId + "|" + eventType + "|" + payload;
            return HexFormat.of().formatHex(sha256.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String buildPingPayload(Long merchantId, Long endpointId) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("eventId",    UUID.randomUUID().toString());
            p.put("eventType",  PING_EVENT_TYPE);
            p.put("merchantId", merchantId);
            p.put("endpointId", endpointId);
            p.put("createdAt",  LocalDateTime.now().toString());
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build ping payload", e);
        }
    }

    private static String processingOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "unknown:" + ProcessHandle.current().pid();
        }
    }

    private MerchantWebhookDeliveryResponseDTO toDto(MerchantWebhookDelivery d) {
        return MerchantWebhookDeliveryResponseDTO.builder()
                .id(d.getId())
                .endpointId(d.getEndpointId())
                .eventType(d.getEventType())
                .payload(d.getPayload())
                .signature(d.getSignature())
                .status(d.getStatus())
                .attemptCount(d.getAttemptCount())
                .lastResponseCode(d.getLastResponseCode())
                .lastError(d.getLastError())
                .nextAttemptAt(d.getNextAttemptAt())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
