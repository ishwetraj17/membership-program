package com.firstclub.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.platform.idempotency.annotation.Idempotent;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector.ConflictKind;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Servlet filter that enforces idempotency for endpoints annotated with
 * {@link Idempotent}.
 *
 * <h3>Phase 4 — full idempotency foundation</h3>
 * <pre>
 * incoming request
 *   │
 *   ├─ handler has @Idempotent? ──── no  ──► pass through
 *   ├─ Idempotency-Key header? ── no ──────► 400 IDEMPOTENCY_KEY_REQUIRED
 *   ├─ key length > 80? ── yes ────────────► 400 IDEMPOTENCY_KEY_TOO_LONG
 *   │
 *   ├─ [Redis fast path — skipped when Redis is unavailable]
 *   │   ├─ response cached? YES + endpoint mismatch  ──► 422 ENDPOINT_MISMATCH
 *   │   ├─ response cached? YES + body mismatch      ──► 422 BODY_MISMATCH
 *   │   ├─ response cached? YES + match ─────────────► replay (+ X-Idempotency-Replayed)
 *   │   └─ in-flight lock present? ──────────────────► 409 IN_FLIGHT
 *   │
 *   ├─ DB lookup: findByMerchantAndKey
 *   │   ├─ found + endpoint mismatch ──────────────► 422 ENDPOINT_MISMATCH
 *   │   ├─ found + body mismatch ───────────────────► 422 BODY_MISMATCH
 *   │   ├─ found + match + completed ──────────────► replay + seed Redis + headers
 *   │   └─ found + match + processing ─────────────► 409 IN_FLIGHT
 *   │
 *   └─ first request:
 *       ├─ acquire Redis NX lock (→ 409 if contested)
 *       ├─ createPlaceholder in DB (PROCESSING status)
 *       ├─ proceed with request
 *       └─ storeResponse (COMPLETED) / markFailed (5xx) + cache Redis + release lock
 * </pre>
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String IDEMPOTENCY_KEY_HEADER      = "Idempotency-Key";
    static final String REPLAYED_HEADER             = "X-Idempotency-Replayed";
    static final String ORIGINAL_AT_HEADER          = "X-Idempotency-Original-At";
    private static final String ANONYMOUS           = "anonymous";

    private final IdempotencyService        idempotencyService;
    private final RedisIdempotencyStore     redisStore;
    private final ObjectMapper              objectMapper;
    private final IdempotencyConflictDetector conflictDetector;

    @Autowired
    @Lazy
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    public IdempotencyFilter(IdempotencyService idempotencyService,
                             RedisIdempotencyStore redisStore,
                             ObjectMapper objectMapper,
                             IdempotencyConflictDetector conflictDetector) {
        this.idempotencyService = idempotencyService;
        this.redisStore = redisStore;
        this.objectMapper = objectMapper;
        this.conflictDetector = conflictDetector;
    }

    /**
     * Backward-compatible constructor for tests / config that does not supply a
     * {@link IdempotencyConflictDetector}.  A default (stateless) instance is
     * created automatically.
     *
     * @deprecated Prefer the four-argument constructor so the detector bean is
     *             properly managed by Spring.
     */
    @Deprecated(since = "Phase 4")
    public IdempotencyFilter(IdempotencyService idempotencyService,
                             RedisIdempotencyStore redisStore,
                             ObjectMapper objectMapper) {
        this(idempotencyService, redisStore, objectMapper, new IdempotencyConflictDetector());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Step 1 — only process requests targeting an @Idempotent handler
        Idempotent idempotent = resolveIdempotentAnnotation(request);
        if (idempotent == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 2 — validate Idempotency-Key header
        String rawKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            writeError(response, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required for this endpoint",
                    "IDEMPOTENCY_KEY_REQUIRED");
            return;
        }
        if (rawKey.length() > 80) {
            writeError(response, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must not exceed 80 characters",
                    "IDEMPOTENCY_KEY_TOO_LONG");
            return;
        }

        // Step 3 — resolve merchant identity
        String merchantId = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : ANONYMOUS;

        // Step 4 — read + cache body; compute request hash
        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        BodyCachingRequestWrapper wrappedRequest = new BodyCachingRequestWrapper(request, bodyBytes);
        String requestHash = computeHash(request.getMethod(), request.getRequestURI(), bodyBytes);

        // Step 5 — derive endpoint signature (set as request attribute by Spring MVC)
        String endpointSignature = buildEndpointSignature(request);

        // Step 6 — Redis fast path (skipped transparently when Redis is unavailable)
        if (redisStore.isEnabled()) {
            Optional<IdempotencyResponseEnvelope> cached =
                    redisStore.tryGetCachedResponse(merchantId, rawKey);
            if (cached.isPresent()) {
                IdempotencyResponseEnvelope env = cached.get();
                // Endpoint check first (→ 422), then body check (→ 422)
                if (!endpointSignature.equals(env.endpointSignature())) {
                    writeError(response, HttpStatus.UNPROCESSABLE_ENTITY,
                            "Idempotency-Key was previously used on a different endpoint: "
                                    + env.endpointSignature(),
                            "IDEMPOTENCY_CONFLICT");
                    return;
                }
                if (!requestHash.equals(env.requestHash())) {
                    writeError(response, HttpStatus.UNPROCESSABLE_ENTITY,
                            "Idempotency-Key was previously used with a different request body",
                            "IDEMPOTENCY_CONFLICT");
                    return;
                }
                log.debug("Redis HIT: replaying idempotent response merchant={} key={} status={}",
                        merchantId, rawKey, env.statusCode());
                // originalAt not available in Redis envelope (null = omit the header)
                replayResponse(response, env.statusCode(),
                        env.contentType() != null ? env.contentType() : MediaType.APPLICATION_JSON_VALUE,
                        env.responseBody(), null);
                return;
            }
            if (redisStore.getProcessingMarker(merchantId, rawKey).isPresent()) {
                writeError(response, HttpStatus.CONFLICT,
                        "Request with Idempotency-Key '" + rawKey + "' is already being processed",
                        "IDEMPOTENCY_IN_PROGRESS");
                return;
            }
        }

        // Step 7 — DB lookup
        Optional<IdempotencyKeyEntity> existing =
                idempotencyService.findByMerchantAndKey(merchantId, rawKey);
        if (existing.isPresent()) {
            IdempotencyKeyEntity record = existing.get();

            // Use the conflict detector for authoritative 422/409 semantics
            Optional<IdempotencyConflictDetector.ConflictResult> conflict =
                    conflictDetector.detect(requestHash, endpointSignature, record);
            if (conflict.isPresent()) {
                IdempotencyConflictDetector.ConflictResult c = conflict.get();
                HttpStatus httpStatus = (c.kind() == ConflictKind.IN_FLIGHT)
                        ? HttpStatus.CONFLICT
                        : HttpStatus.UNPROCESSABLE_ENTITY;
                String errorCode = (c.kind() == ConflictKind.IN_FLIGHT)
                        ? "IDEMPOTENCY_IN_PROGRESS"
                        : "IDEMPOTENCY_CONFLICT";
                writeError(response, httpStatus, c.message(), errorCode);
                return;
            }

            // No conflict — record must be COMPLETED (PROCESSING was already caught above)
            if (record.isCompleted()) {
                log.debug("DB HIT: replaying idempotent response merchant={} key={} status={}",
                        merchantId, rawKey, record.getStatusCode());
                // Seed Redis cache so future duplicates skip the DB
                if (redisStore.isEnabled()) {
                    long remainingTtl = java.time.Duration.between(
                            LocalDateTime.now(), record.getExpiresAt()).getSeconds();
                    if (remainingTtl > 0) {
                        IdempotencyResponseEnvelope env = new IdempotencyResponseEnvelope(
                                requestHash, endpointSignature,
                                record.getStatusCode(), record.getResponseBody(),
                                record.getContentType() != null ? record.getContentType()
                                        : MediaType.APPLICATION_JSON_VALUE);
                        redisStore.cacheResponse(merchantId, rawKey, env, remainingTtl);
                    }
                }
                replayResponse(response, record.getStatusCode(),
                        record.getContentType() != null ? record.getContentType()
                                : MediaType.APPLICATION_JSON_VALUE,
                        record.getResponseBody(),
                        record.getCompletedAt());
                return;
            }

            // FAILED_RETRYABLE / FAILED_FINAL — let the conflict detector message surface
            // (these cases fall through because conflictDetector returns empty for them;
            //  they should be treated as a fresh request — fall through to Step 8)
        }

        // Step 8 — first request: acquire Redis NX lock then create DB placeholder
        String requestId = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        if (redisStore.isEnabled()) {
            IdempotencyProcessingMarker marker = new IdempotencyProcessingMarker(
                    requestHash, endpointSignature, LocalDateTime.now().toString(), requestId);
            lockAcquired = redisStore.tryAcquireLock(merchantId, rawKey, marker);
            if (!lockAcquired) {
                writeError(response, HttpStatus.CONFLICT,
                        "Request with Idempotency-Key '" + rawKey + "' is already being processed",
                        "IDEMPOTENCY_IN_PROGRESS");
                return;
            }
        }

        try {
            idempotencyService.createPlaceholder(
                    merchantId, rawKey, requestHash, endpointSignature, idempotent.ttlHours());
        } catch (Exception ex) {
            if (lockAcquired) redisStore.releaseLock(merchantId, rawKey);
            log.debug("Idempotency placeholder insert conflict merchant={} key={}", merchantId, rawKey);
            writeError(response, HttpStatus.CONFLICT,
                    "Request with Idempotency-Key '" + rawKey + "' is already being processed",
                    "IDEMPOTENCY_IN_PROGRESS");
            return;
        }

        // Step 9 — proceed and capture the response
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            String responseBody    = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            String owner           = ANONYMOUS.equals(merchantId) ? null : merchantId;
            String respContentType = wrappedResponse.getContentType();
            int    statusCode      = wrappedResponse.getStatus();

            try {
                if (statusCode >= 500) {
                    // 5xx: transient failure — client may retry with the same key
                    idempotencyService.markFailed(merchantId, rawKey, true);
                } else {
                    idempotencyService.storeResponse(
                            merchantId, rawKey, requestHash,
                            statusCode, responseBody, owner, respContentType);
                    // Cache definitive (non-5xx) responses in Redis
                    if (redisStore.isEnabled()) {
                        IdempotencyResponseEnvelope env = new IdempotencyResponseEnvelope(
                                requestHash, endpointSignature, statusCode, responseBody,
                                respContentType != null ? respContentType : MediaType.APPLICATION_JSON_VALUE);
                        redisStore.cacheResponse(merchantId, rawKey, env,
                                (long) idempotent.ttlHours() * 3600);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to persist idempotency response merchant={} key={} " +
                         "— idempotency guarantee degraded", merchantId, rawKey, ex);
            } finally {
                if (lockAcquired) redisStore.releaseLock(merchantId, rawKey);
            }
            wrappedResponse.copyBodyToResponse();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Idempotent resolveIdempotentAnnotation(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = requestMappingHandlerMapping.getHandler(request);
            if (chain == null) return null;
            Object handler = chain.getHandler();
            if (handler instanceof HandlerMethod handlerMethod) {
                return handlerMethod.getMethodAnnotation(Idempotent.class);
            }
        } catch (Exception e) {
            log.trace("Handler resolution failed for idempotency check on {}: {}",
                    request.getRequestURI(), e.getMessage());
        }
        return null;
    }

    private static String buildEndpointSignature(HttpServletRequest request) {
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = (pattern != null) ? pattern : request.getRequestURI();
        return request.getMethod() + ":" + path;
    }

    /**
     * Writes the stored response back to the client, adding Phase-4 replay headers.
     *
     * @param originalAt when the original request completed; may be {@code null} for
     *                   legacy Redis entries where {@code completed_at} was not stored
     */
    static void replayResponse(HttpServletResponse response,
                                int statusCode,
                                String contentType,
                                String body,
                                LocalDateTime originalAt) throws IOException {
        response.setStatus(statusCode);
        response.setContentType(contentType);
        response.setHeader(REPLAYED_HEADER, "true");
        if (originalAt != null) {
            response.setHeader(ORIGINAL_AT_HEADER, originalAt.toString());
        }
        response.getWriter().write(body);
    }

    /** Computes a hex-encoded SHA-256 hash of method + path + body. */
    static String computeHash(String method, String uri, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update(uri.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private void writeError(HttpServletResponse response,
                            HttpStatus status,
                            String message,
                            String errorCode) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("errorCode", errorCode);
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("httpStatus", status.value());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    // ── Inner helpers ─────────────────────────────────────────────────────────

    static final class BodyCachingRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        BodyCachingRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final InputStream source;

        ByteArrayServletInputStream(byte[] data) {
            this.source = new ByteArrayInputStream(data);
        }

        @Override
        public boolean isFinished() {
            try { return source.available() == 0; } catch (IOException e) { return true; }
        }

        @Override public boolean isReady() { return true; }

        @Override public void setReadListener(ReadListener listener) { /* no-op for sync */ }

        @Override public int read() throws IOException { return source.read(); }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            return source.read(b, off, len);
        }
    }
}
