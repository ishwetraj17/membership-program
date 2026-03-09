package com.firstclub.payments.routing.service.impl;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.routing.cache.GatewayHealthCache;
import com.firstclub.payments.routing.cache.RoutingRuleCache;
import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleCreateRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleUpdateRequestDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionSnapshot;
import com.firstclub.payments.routing.entity.GatewayHealth;
import com.firstclub.payments.routing.entity.GatewayHealthStatus;
import com.firstclub.payments.routing.entity.GatewayRouteRule;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.repository.GatewayHealthRepository;
import com.firstclub.payments.routing.repository.GatewayRouteRuleRepository;
import com.firstclub.payments.routing.service.PaymentRoutingService;
import com.firstclub.platform.redis.RedisJsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@Slf4j
public class PaymentRoutingServiceImpl implements PaymentRoutingService {

    private final GatewayRouteRuleRepository routeRuleRepository;
    private final GatewayHealthRepository healthRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final GatewayHealthCache gatewayHealthCache;
    private final RoutingRuleCache routingRuleCache;
    private final RedisJsonCodec codec;

    public PaymentRoutingServiceImpl(GatewayRouteRuleRepository routeRuleRepository,
                                     GatewayHealthRepository healthRepository,
                                     PaymentAttemptRepository paymentAttemptRepository,
                                     GatewayHealthCache gatewayHealthCache,
                                     RoutingRuleCache routingRuleCache,
                                     RedisJsonCodec codec) {
        this.routeRuleRepository = routeRuleRepository;
        this.healthRepository = healthRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.gatewayHealthCache = gatewayHealthCache;
        this.routingRuleCache = routingRuleCache;
        this.codec = codec;
    }

    @Override
    @Transactional(readOnly = true)
    public RoutingDecisionDTO selectGatewayForAttempt(PaymentIntentV2 intent,
                                                      PaymentMethod pm,
                                                      int retryNumber) {
        String methodType = pm.getMethodType().name();
        String currency = intent.getCurrency();
        Long merchantId = intent.getMerchant().getId();
        String merchantScope = merchantId.toString();

        // 1. Try merchant-specific rules — cache first, DB fallback on miss
        List<GatewayRouteRuleResponseDTO> rules = loadRules(merchantScope, methodType, currency, retryNumber);

        boolean merchantSpecific = !rules.isEmpty();
        if (!merchantSpecific) {
            // 2. Fall back to platform-wide defaults — cache first, DB fallback on miss
            rules = loadRules("global", methodType, currency, retryNumber);
        }

        // 3. Walk rules in priority order, selecting the healthiest available gateway
        for (GatewayRouteRuleResponseDTO rule : rules) {
            GatewayHealthStatus preferredStatus = gatewayStatus(rule.getPreferredGateway());
            GatewayHealthStatus fallbackStatus = rule.getFallbackGateway() != null
                    ? gatewayStatus(rule.getFallbackGateway()) : null;

            String gateway;
            boolean isFallback;
            if (preferredStatus != GatewayHealthStatus.DOWN) {
                gateway = rule.getPreferredGateway();
                isFallback = false;
            } else if (rule.getFallbackGateway() != null
                    && fallbackStatus != GatewayHealthStatus.DOWN) {
                gateway = rule.getFallbackGateway();
                isFallback = true;
            } else {
                continue; // both DOWN or no fallback — try next rule
            }

            String reason = isFallback
                    ? "preferred gateway '" + rule.getPreferredGateway() + "' is DOWN; using fallback"
                    : "preferred gateway selected";

            String snapshotJson = buildSnapshotJson(gateway, rule, isFallback, merchantSpecific,
                    methodType, currency, retryNumber, preferredStatus, fallbackStatus, reason);

            log.debug("Routing decision: gateway={} ruleId={} fallback={} scope={}",
                    gateway, rule.getId(), isFallback, merchantSpecific ? merchantScope : "global");

            return new RoutingDecisionDTO(gateway, rule.getId(), isFallback, reason, snapshotJson);
        }

        throw RoutingException.noEligibleGateway(methodType, currency);
    }

    @Override
    public GatewayRouteRuleResponseDTO createRouteRule(GatewayRouteRuleCreateRequestDTO request) {
        GatewayRouteRule rule = new GatewayRouteRule();
        rule.setMerchantId(request.getMerchantId());
        rule.setPriority(request.getPriority());
        rule.setPaymentMethodType(request.getPaymentMethodType());
        rule.setCurrency(request.getCurrency());
        rule.setCountryCode(request.getCountryCode());
        rule.setRetryNumber(request.getRetryNumber());
        rule.setPreferredGateway(request.getPreferredGateway());
        rule.setFallbackGateway(request.getFallbackGateway());
        rule.setActive(true);
        GatewayRouteRule saved = routeRuleRepository.save(rule);

        // Evict cache so the new rule is visible immediately
        evictRuleCache(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GatewayRouteRuleResponseDTO> getAllRouteRules() {
        return routeRuleRepository.findAllByOrderByPriorityAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public GatewayRouteRuleResponseDTO updateRouteRule(Long ruleId,
                                                       GatewayRouteRuleUpdateRequestDTO request) {
        GatewayRouteRule rule = routeRuleRepository.findById(ruleId)
                .orElseThrow(() -> RoutingException.ruleNotFound(ruleId));

        if (request.getPriority() != null) rule.setPriority(request.getPriority());
        if (request.getPreferredGateway() != null) rule.setPreferredGateway(request.getPreferredGateway());
        if (request.getFallbackGateway() != null) rule.setFallbackGateway(request.getFallbackGateway());
        if (request.getActive() != null) rule.setActive(request.getActive());

        GatewayRouteRule saved = routeRuleRepository.save(rule);
        evictRuleCache(saved);
        return toResponse(saved);
    }

    @Override
    public void deactivateRouteRule(Long ruleId) {
        GatewayRouteRule rule = routeRuleRepository.findById(ruleId)
                .orElseThrow(() -> RoutingException.ruleNotFound(ruleId));
        rule.setActive(false);
        GatewayRouteRule saved = routeRuleRepository.save(rule);
        evictRuleCache(saved);
        log.info("Deactivated routing rule id={}", ruleId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoutingDecisionSnapshot> getRoutingDecision(Long attemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new RoutingException(
                        "PaymentAttempt " + attemptId + " not found",
                        "ATTEMPT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        String json = attempt.getRoutingSnapshotJson();
        if (json == null || json.isBlank()) return Optional.empty();
        return codec.tryFromJson(json, RoutingDecisionSnapshot.class);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the ordered list of active routing rules for the given discriminator combination.
     * Checks the Redis cache first; falls back to the DB on a miss and populates the cache.
     *
     * @param scope       merchant-id string or {@code "global"}
     * @param methodType  payment method type
     * @param currency    ISO-4217 currency code
     * @param retryNumber attempt number
     * @return ordered (priority ASC) list of rules; empty when no active rules match
     */
    private List<GatewayRouteRuleResponseDTO> loadRules(String scope, String methodType,
                                                         String currency, int retryNumber) {
        Optional<List<GatewayRouteRuleResponseDTO>> cached =
                routingRuleCache.get(scope, methodType, currency, retryNumber);
        if (cached.isPresent()) {
            return cached.get();
        }

        // DB fallback
        List<GatewayRouteRule> dbRules;
        if ("global".equals(scope)) {
            dbRules = routeRuleRepository.findPlatformDefaultRules(methodType, currency, retryNumber);
        } else {
            Long merchantId = Long.parseLong(scope);
            dbRules = routeRuleRepository.findActiveRulesForMerchantAndMethodAndCurrency(
                    merchantId, methodType, currency, retryNumber);
        }

        List<GatewayRouteRuleResponseDTO> dtos = dbRules.stream().map(this::toResponse).toList();
        routingRuleCache.put(scope, methodType, currency, retryNumber, dtos);
        return dtos;
    }

    /**
     * Returns the health status for a gateway, checking the Redis cache first,
     * then falling back to the DB.  Defaults to {@link GatewayHealthStatus#HEALTHY}
     * for unregistered gateways.
     */
    GatewayHealthStatus gatewayStatus(String gatewayName) {
        Optional<GatewayHealthResponseDTO> cached = gatewayHealthCache.get(gatewayName);
        if (cached.isPresent()) {
            return cached.get().getStatus();
        }
        return healthRepository.findByGatewayName(gatewayName)
                .map(GatewayHealth::getStatus)
                .orElse(GatewayHealthStatus.HEALTHY);
    }

    /**
     * Serialises a {@link RoutingDecisionSnapshot} to JSON; returns {@code null} on any error
     * so that snapshot capture never blocks the routing hot path.
     */
    private String buildSnapshotJson(String selected, GatewayRouteRuleResponseDTO rule,
                                     boolean isFallback, boolean merchantSpecific,
                                     String methodType, String currency, int retryNumber,
                                     GatewayHealthStatus preferredStatus,
                                     GatewayHealthStatus fallbackStatus,
                                     String reason) {
        try {
            RoutingDecisionSnapshot snap = new RoutingDecisionSnapshot();
            snap.setSelectedGateway(selected);
            snap.setPreferredGateway(rule.getPreferredGateway());
            snap.setFallbackGateway(rule.getFallbackGateway());
            snap.setFallbackUsed(isFallback);
            snap.setRuleId(rule.getId());
            snap.setMerchantSpecific(merchantSpecific);
            snap.setMethodType(methodType);
            snap.setCurrency(currency);
            snap.setRetryNumber(retryNumber);
            snap.setPreferredGatewayStatus(preferredStatus.name());
            snap.setFallbackGatewayStatus(fallbackStatus != null ? fallbackStatus.name() : null);
            snap.setReasoningSummary(reason);
            snap.setDecidedAt(LocalDateTime.now());
            return codec.toJson(snap);
        } catch (Exception ex) {
            log.warn("Failed to serialise RoutingDecisionSnapshot — snapshot will be null", ex);
            return null;
        }
    }

    /** Evicts the routing rule cache for the scope/methodType/currency/retryNumber of a rule. */
    private void evictRuleCache(GatewayRouteRule rule) {
        String scope = rule.getMerchantId() != null ? rule.getMerchantId().toString() : "global";
        routingRuleCache.evict(scope, rule.getPaymentMethodType(), rule.getCurrency(),
                rule.getRetryNumber());
    }

    private GatewayRouteRuleResponseDTO toResponse(GatewayRouteRule rule) {
        GatewayRouteRuleResponseDTO dto = new GatewayRouteRuleResponseDTO();
        dto.setId(rule.getId());
        dto.setMerchantId(rule.getMerchantId());
        dto.setPriority(rule.getPriority());
        dto.setPaymentMethodType(rule.getPaymentMethodType());
        dto.setCurrency(rule.getCurrency());
        dto.setCountryCode(rule.getCountryCode());
        dto.setRetryNumber(rule.getRetryNumber());
        dto.setPreferredGateway(rule.getPreferredGateway());
        dto.setFallbackGateway(rule.getFallbackGateway());
        dto.setActive(rule.isActive());
        dto.setCreatedAt(rule.getCreatedAt());
        return dto;
    }
}

