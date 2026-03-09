package com.firstclub.risk.service;

import com.firstclub.risk.entity.IpBlocklist;
import com.firstclub.risk.entity.RiskEvent;
import com.firstclub.risk.entity.RiskEvent.RiskEventType;
import com.firstclub.risk.entity.RiskEvent.RiskSeverity;
import com.firstclub.risk.repository.IpBlocklistRepository;
import com.firstclub.risk.repository.RiskEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Performs pre-payment risk checks and persists risk events.
 *
 * <p>Two checks are applied in order:
 * <ol>
 *   <li><b>IP block</b> — the IP is looked up in {@code ip_blocklist}; if
 *       found a {@link RiskViolationException} with type {@code IP_BLOCKED} is
 *       thrown (→ 403).
 *   <li><b>Velocity</b> — if the authenticated user has already made ≥ 5
 *       payment attempts in the past hour a {@link RiskViolationException}
 *       with type {@code VELOCITY_EXCEEDED} is thrown (→ 429).
 * </ol>
 *
 * <p>Both violations are recorded as {@link RiskEvent} rows BEFORE the
 * exception is raised so they survive even if the calling transaction rolls
 * back (each save call has {@code REQUIRES_NEW} propagation).
 * Normal payment attempts are also recorded for velocity bookkeeping.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskService {

    /** Maximum payment attempts allowed per user per hour. */
    static final int VELOCITY_LIMIT = 5;

    private final RiskEventRepository riskEventRepository;
    private final IpBlocklistRepository ipBlocklistRepository;

    // ------------------------------------------------------------------
    // Main check — call this before confirming a payment intent
    // ------------------------------------------------------------------

    /**
     * Runs IP-block and velocity checks, then records a
     * {@code PAYMENT_ATTEMPT} event if everything passes.
     *
     * @param userId   resolved user ID (may be null for anonymous requests)
     * @param ip       remote IP address from the HTTP request
     * @param deviceId value of the {@code X-Device-Id} request header (may be null)
     * @throws RiskViolationException if the request is blocked
     */
    @Transactional
    public void checkAndRecord(Long userId, String ip, String deviceId) {

        // 1. IP block check
        if (ipBlocklistRepository.existsById(ip)) {
            persistEvent(RiskEventType.IP_BLOCKED, RiskSeverity.HIGH, userId, ip, deviceId,
                    "IP " + ip + " is on the block-list");
            log.warn("Risk: IP_BLOCKED ip={} userId={}", ip, userId);
            throw new RiskViolationException(RiskEventType.IP_BLOCKED,
                    "Your IP address has been blocked. Contact support.");
        }

        // 2. Per-user velocity check (only when userId is known)
        if (userId != null) {
            LocalDateTime since = LocalDateTime.now().minusHours(1);
            long attempts = riskEventRepository.countPaymentAttemptsByUserSince(userId, since);
            if (attempts >= VELOCITY_LIMIT) {
                persistEvent(RiskEventType.VELOCITY_EXCEEDED, RiskSeverity.MEDIUM, userId, ip,
                        deviceId, "User " + userId + " exceeded " + VELOCITY_LIMIT
                                  + " payment attempts in the last hour (count=" + attempts + ")");
                log.warn("Risk: VELOCITY_EXCEEDED userId={} attempts={}", userId, attempts);
                throw new RiskViolationException(RiskEventType.VELOCITY_EXCEEDED,
                        "Too many payment attempts. Please try again later.");
            }
        }

        // 3. Record the attempt for future velocity counting
        persistEvent(RiskEventType.PAYMENT_ATTEMPT, RiskSeverity.LOW, userId, ip, deviceId, null);
        log.debug("Risk: PAYMENT_ATTEMPT recorded userId={} ip={}", userId, ip);
    }

    // ------------------------------------------------------------------
    // Admin helpers
    // ------------------------------------------------------------------

    /**
     * Adds the given IP to the block-list.  Idempotent — if the IP is already
     * blocked the reason is updated.
     */
    @Transactional
    public void blockIp(String ip, String reason) {
        IpBlocklist entry = IpBlocklist.builder().ip(ip).reason(reason).build();
        ipBlocklistRepository.save(entry);
        log.info("Risk: IP {} added to block-list, reason='{}'", ip, reason);
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /** Saves a risk event in a brand-new transaction so it is durably stored
     *  even when the outer transaction later rolls back. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void persistEvent(RiskEventType type, RiskSeverity severity,
                              Long userId, String ip, String deviceId, String metadata) {
        riskEventRepository.save(RiskEvent.builder()
                .type(type)
                .severity(severity)
                .userId(userId)
                .ip(ip)
                .deviceId(deviceId)
                .metadata(metadata)
                .build());
    }
}
