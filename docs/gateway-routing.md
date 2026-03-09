# Gateway Routing & Failover

Phase 7 — intelligent gateway selection and health-aware failover for payment intents.

---

## Why This Exists

Previously, every `confirmPaymentIntent` call required the caller to specify a `gatewayName`
directly in the request body.  This forced business logic about *which* gateway to use into
callers and made failover impossible at runtime.

Phase 7 replaces that with a **routing layer**: rules stored in the database determine which
gateway handles a given payment, and the health registry allows real-time failover when a
gateway degrades or goes down.

---

## Database Schema

### `gateway_route_rules`

| Column               | Type          | Description                                                          |
|----------------------|---------------|----------------------------------------------------------------------|
| `id`                 | BIGSERIAL PK  | Auto-assigned rule ID                                                |
| `merchant_id`        | BIGINT NULL   | NULL = platform-wide default; non-NULL = merchant-specific override  |
| `priority`           | INT           | Lower number = higher priority (1 = highest)                         |
| `payment_method_type`| VARCHAR(32)   | Matches `PaymentMethodType` enum name: CARD, UPI, NETBANKING, etc.   |
| `currency`           | VARCHAR(10)   | ISO 4217 currency code, e.g. INR, USD                                |
| `country_code`       | VARCHAR(8)    | Optional country filter (informational for now)                      |
| `retry_number`       | INT           | Rule applies when `attemptNumber <= retry_number`                    |
| `preferred_gateway`  | VARCHAR(64)   | Gateway to use first                                                 |
| `fallback_gateway`   | VARCHAR(64)   | Gateway to use if preferred is DOWN (nullable)                       |
| `active`             | BOOLEAN       | Soft-disable a rule without deleting it                              |
| `created_at`         | TIMESTAMP     | Creation timestamp                                                   |

### `gateway_health`

| Column                  | Type           | Description                                     |
|-------------------------|----------------|-------------------------------------------------|
| `gateway_name`          | VARCHAR(64) PK | Gateway identifier (e.g. `razorpay`, `stripe`)  |
| `status`                | VARCHAR(16)    | `HEALTHY`, `DEGRADED`, or `DOWN`                |
| `last_checked_at`       | TIMESTAMP      | When this record was last updated               |
| `rolling_success_rate`  | DECIMAL(8,4)   | 0.00–100.00, rolling success percentage         |
| `rolling_p95_latency_ms`| BIGINT         | 95th-percentile latency in milliseconds         |

Three gateway health records are seeded in `V21__gateway_routing.sql` (razorpay, stripe, payu — all HEALTHY).

---

## Routing Algorithm

`PaymentRoutingServiceImpl.selectGatewayForAttempt(intent, pm, retryNumber)` executes:

```
1. Load merchant-specific rules:
     WHERE active=true AND merchant_id=<intent.merchant.id>
       AND payment_method_type=<pm.methodType.name()>
       AND currency=<intent.currency>
       AND retry_number <= <retryNumber>
     ORDER BY priority ASC, retry_number DESC

2. If no merchant rules exist → repeat query with merchant_id IS NULL (platform defaults)

3. Iterate rules in order:
   a. Check preferred gateway health:
      - HEALTHY or DEGRADED → select preferred, isFallback=false
      - DOWN or absent from health table* → try fallback
   b. If fallbackGateway is set and its health is not DOWN → select fallback, isFallback=true
   c. Otherwise → skip this rule, continue to next

4. If no rule yields a usable gateway → throw RoutingException(NO_ELIGIBLE_GATEWAY, 503)
```

> *Gateways absent from the `gateway_health` table are treated as **HEALTHY** (safe-default for
> sandbox and new integrations).

### Tie-breaking within equal priority

When two rules share the same `priority`, the one with the higher `retry_number` wins.  This lets
you write "on retry 2, switch to gateway B" without creating a second priority tier.

---

## Failover Behaviour

| Preferred | Fallback | Outcome                                      |
|-----------|----------|----------------------------------------------|
| HEALTHY   | any      | preferred selected                           |
| DEGRADED  | any      | preferred selected (best available)          |
| DOWN      | HEALTHY  | fallback selected (`isFallback=true`)        |
| DOWN      | DEGRADED | fallback selected (`isFallback=true`)        |
| DOWN      | DOWN     | rule skipped — next rule evaluated           |
| DOWN      | none     | rule skipped — next rule evaluated           |

If *every* rule is exhausted without a viable gateway, `RoutingException(NO_ELIGIBLE_GATEWAY)`
is thrown (HTTP 503).

---

## Integration with `confirmPaymentIntent`

`PaymentIntentV2ServiceImpl` now injects `PaymentRoutingService`.  In `confirmPaymentIntent`:

```java
String selectedGateway;
try {
    RoutingDecisionDTO decision = paymentRoutingService.selectGatewayForAttempt(intent, pm, nextAttemptNumber);
    selectedGateway = decision.getSelectedGateway();
} catch (RoutingException re) {
    if (request.getGatewayName() != null && !request.getGatewayName().isBlank()) {
        selectedGateway = request.getGatewayName();   // backward-compat hint
    } else {
        throw re;
    }
}
```

### Backward compatibility

`PaymentIntentConfirmRequestDTO.gatewayName` is now **optional** (`@NotBlank` removed, `@Size(max=64)` kept).
Callers that still supply `gatewayName` will have it used as a fallback hint whenever no routing
rules are configured — existing tests and clients require no changes.

---

## Admin APIs

### Gateway Route Rules

| Method | URL                                         | Description                     |
|--------|---------------------------------------------|---------------------------------|
| POST   | `/api/v2/admin/gateway-routes`              | Create a new route rule         |
| GET    | `/api/v2/admin/gateway-routes`              | List all rules (by priority)    |
| PUT    | `/api/v2/admin/gateway-routes/{routeId}`    | Update priority / gateways / active flag |

**Create request example:**
```json
{
  "priority": 10,
  "paymentMethodType": "CARD",
  "currency": "INR",
  "retryNumber": 1,
  "preferredGateway": "razorpay",
  "fallbackGateway": "stripe"
}
```

**Platform-wide rule** (no `merchantId`) applies to all merchants that have no merchant-specific rule.

**Merchant-specific rule** (with `merchantId`) takes precedence over platform defaults.

### Gateway Health

| Method | URL                                              | Description                             |
|--------|--------------------------------------------------|-----------------------------------------|
| GET    | `/api/v2/admin/gateways/health`                  | List all gateway health snapshots       |
| PUT    | `/api/v2/admin/gateways/health/{gatewayName}`    | Upsert gateway health status / metrics  |

**Update / register example:**
```json
{
  "status": "DOWN",
  "rollingSuccessRate": 0.0,
  "rollingP95LatencyMs": 9999
}
```

Marking a gateway `DOWN` immediately stops the router from selecting it until it is updated back
to `HEALTHY` or `DEGRADED`.

---

## Package Structure

```
com.firstclub.payments.routing
├── controller
│   ├── GatewayRouteAdminController   (POST/GET/PUT /api/v2/admin/gateway-routes)
│   └── GatewayHealthAdminController  (GET/PUT /api/v2/admin/gateways/health)
├── dto
│   ├── GatewayRouteRuleCreateRequestDTO
│   ├── GatewayRouteRuleUpdateRequestDTO
│   ├── GatewayRouteRuleResponseDTO
│   ├── GatewayHealthResponseDTO
│   ├── GatewayHealthUpdateRequestDTO
│   └── RoutingDecisionDTO
├── entity
│   ├── GatewayHealthStatus       (HEALTHY | DEGRADED | DOWN)
│   ├── GatewayRouteRule
│   └── GatewayHealth
├── exception
│   └── RoutingException
├── repository
│   ├── GatewayRouteRuleRepository
│   └── GatewayHealthRepository
└── service
    ├── PaymentRoutingService     (interface)
    ├── GatewayHealthService      (interface)
    └── impl
        ├── PaymentRoutingServiceImpl
        └── GatewayHealthServiceImpl
```

---

## Error Codes

| Code                   | HTTP | When                                                          |
|------------------------|------|---------------------------------------------------------------|
| `NO_ELIGIBLE_GATEWAY`  | 503  | All gateway candidates are DOWN, or no rules exist            |
| `ROUTE_RULE_NOT_FOUND` | 404  | `PUT /gateway-routes/{id}` — ID does not exist               |
| `GATEWAY_NOT_FOUND`    | 404  | `GET /gateways/health/{name}` — gateway not in health table  |

---

## Test Coverage

| Test class                          | Type        | Scenarios                                                      |
|-------------------------------------|-------------|----------------------------------------------------------------|
| `PaymentRoutingServiceTest`         | Unit        | HEALTHY preferred, DEGRADED preferred, preferred-DOWN fallback, both-DOWN throws, no rules throws, merchant overrides platform, unknown-gw treated as HEALTHY |
| `GatewayHealthServiceTest`          | Unit        | getSnapshot found/not-found, getAllSnapshots, updateHealth upsert existing and create new |
| `GatewayRouteAdminControllerTest`   | Integration | POST 201 with fallback, POST 201 platform rule, POST 400 validation, GET 200, PUT 200 update, PUT 404 |
| `GatewayHealthAdminControllerTest`  | Integration | GET 200 list, PUT 200 create new, PUT 200 update to DOWN, PUT 400 null status |
