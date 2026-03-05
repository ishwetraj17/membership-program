package com.firstclub.membership.service.impl;

import com.firstclub.membership.security.JwtTokenProvider;
import com.firstclub.membership.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist.
 *
 * Stores revoked tokens mapped to their expiry timestamp.
 * A scheduled task evicts expired entries every 10 minutes to prevent
 * unbounded memory growth.
 *
 * NOTE: This is node-local. For a multi-instance deployment replace with
 *       a Redis-backed implementation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private final JwtTokenProvider jwtTokenProvider;

    /** token string → expiry epoch (seconds). */
    private final ConcurrentHashMap<String, Long> blacklist = new ConcurrentHashMap<>();

    @Override
    public void blacklist(String token) {
        try {
            Date expiry = jwtTokenProvider.getExpirationFromToken(token);
            blacklist.put(token, expiry.toInstant().getEpochSecond());
            log.debug("Token blacklisted, expires at: {}", expiry);
        } catch (Exception e) {
            // If we can't parse the expiry, blacklist indefinitely with a 24-h default
            blacklist.put(token, Instant.now().getEpochSecond() + 86400);
            log.warn("Could not parse token expiry during blacklisting — using 24-hour default");
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        return blacklist.containsKey(token);
    }

    /** Remove expired entries every 10 minutes. */
    @Scheduled(fixedRate = 600_000)
    public void evictExpired() {
        long now = Instant.now().getEpochSecond();
        int before = blacklist.size();
        blacklist.entrySet().removeIf(e -> e.getValue() < now);
        int removed = before - blacklist.size();
        if (removed > 0) {
            log.debug("Token blacklist eviction: removed {} expired entries", removed);
        }
    }
}
