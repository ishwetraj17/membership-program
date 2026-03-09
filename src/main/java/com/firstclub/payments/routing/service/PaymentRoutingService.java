package com.firstclub.payments.routing.service;

import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.routing.dto.GatewayRouteRuleCreateRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleUpdateRequestDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionSnapshot;

import java.util.List;
import java.util.Optional;

public interface PaymentRoutingService {

    /**
     * Select the best available gateway for the given payment intent + payment method.
     * <p>
     * Merchant-specific rules are tried first; if none exist the platform-wide defaults are used.
     * Gateway health (HEALTHY/DEGRADED are eligible, DOWN is not) drives preferred vs fallback
     * selection within each rule.
     * <p>
     * The returned {@link RoutingDecisionDTO} includes a JSON-serialised
     * {@link RoutingDecisionSnapshot} ({@code snapshotJson}) that callers should persist on
     * the corresponding {@code PaymentAttempt.routingSnapshotJson} column.
     *
     * @throws com.firstclub.payments.routing.exception.RoutingException with NO_ELIGIBLE_GATEWAY
     *         if every rule's preferred and fallback gateways are DOWN (or no rules exist).
     */
    RoutingDecisionDTO selectGatewayForAttempt(PaymentIntentV2 intent,
                                               PaymentMethod pm,
                                               int retryNumber);

    GatewayRouteRuleResponseDTO createRouteRule(GatewayRouteRuleCreateRequestDTO request);

    List<GatewayRouteRuleResponseDTO> getAllRouteRules();

    GatewayRouteRuleResponseDTO updateRouteRule(Long ruleId, GatewayRouteRuleUpdateRequestDTO request);

    /**
     * Deactivates a routing rule (sets {@code active = false}) and evicts the corresponding
     * cache entry.  This is a soft-delete; the rule remains in the DB for audit purposes.
     *
     * @param ruleId the PK of the {@code gateway_route_rules} row to deactivate
     * @throws com.firstclub.payments.routing.exception.RoutingException if the rule is not found
     */
    void deactivateRouteRule(Long ruleId);

    /**
     * Returns the {@link RoutingDecisionSnapshot} persisted on the given payment attempt.
     *
     * @param attemptId PK of the {@code payment_attempts} row
     * @return the deserialised snapshot, or {@link Optional#empty()} if the attempt has no
     *         snapshot (pre-Phase 5 attempt, or routing fell back to a request hint)
     * @throws com.firstclub.payments.routing.exception.RoutingException if the attempt is not found
     */
    Optional<RoutingDecisionSnapshot> getRoutingDecision(Long attemptId);
}

