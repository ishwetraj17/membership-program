# FirstClub Membership Program

A production-aware membership management backend built with Spring Boot 3.2, PostgreSQL 16, and Java 17.

Users subscribe to **Silver / Gold / Platinum** membership tiers with **Monthly / Quarterly / Yearly** billing — 9 plans in total. The system handles the full subscription lifecycle: creation, upgrade, downgrade, cancellation, expiry, and scheduled auto-renewal.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                           REST Layer                                 │
│  MembershipController  │  UserController  │  PlanController          │
│  /api/v1/membership    │  /api/v1/users   │  /api/v1/plans           │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────┐
│                         Service Layer                                │
│  MembershipService (tiers)    │  PlanService (plans)                │
│  SubscriptionService          │  TierEvaluationService              │
│  UserService                  │  SubscriptionRenewalProcessor       │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────┐
│                       Repository Layer                               │
│         Spring Data JPA + explicit JPQL (JOIN FETCH, aggregates)    │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────┐
│              PostgreSQL 16  (Flyway-managed schema)                  │
│  V1: core schema + partial unique index                              │
│  V2: tier eligibility criteria                                       │
│  V3: scheduler performance indexes                                   │
│  V4: optimistic locking version on users                            │
└──────────────────────────────────────────────────────────────────────┘
```

Each service owns one domain concern (SRP). Controllers inject only the services they need.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Runtime | Java 17+ |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 |
| ORM | Spring Data JPA / Hibernate 6 |
| Schema | Flyway |
| Caching | Spring Cache + Caffeine |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Validation | Jakarta Bean Validation 3 |
| Build | Maven 3.6+ |
| Container | Docker (multi-stage build) |
| Tests | JUnit 5 + Mockito + Spring Boot Test |

---

## Fastest Startup — `start.sh` (Recommended)

This is the primary way to run the project locally. One command resets the environment and starts the application.

```bash
./start.sh
```

**What it does, in order:**

1. Kills any existing process on port 8080
2. Ensures PostgreSQL is running (starts it via `brew services` if stopped)
3. Terminates active DB connections, drops `membershipdb`, recreates it empty
4. Launches `mvn spring-boot:run -Dspring-boot.run.profiles=dev` in the background
5. Waits for `Started MembershipApplication` in the log
6. Verifies health endpoint (HTTP 200, `status=UP`) and Swagger UI (HTTP 200)
7. Confirms seed data: 3 tiers, 9 plans, 3 demo users

**After it finishes:**

| Resource | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| Health | http://localhost:8080/api/v1/membership/health |
| Analytics | http://localhost:8080/api/v1/membership/analytics |
| Actuator | http://localhost:8080/actuator/health |

**Prerequisites:**
- Java 17+, Maven 3.6+
- PostgreSQL 16 installed via Homebrew: `brew install postgresql@16`

The script is idempotent. Run it before every demo to guarantee a clean, predictable state.

---

## Manual Local Development

Use this when you want step-by-step control over the environment.

### 1. Install PostgreSQL (once)

```bash
brew install postgresql@16
export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"
```

### 2. Start PostgreSQL

```bash
brew services start postgresql@16
pg_isready -h localhost -p 5432    # should print: localhost:5432 - accepting connections
```

### 3. Create the database

```bash
# Create the postgres superuser (first time only)
psql -h localhost -d postgres -c "CREATE USER postgres WITH SUPERUSER PASSWORD 'postgres';"

# Create the application database
psql -h localhost -U postgres -c "CREATE DATABASE membershipdb;"
```

### 4. Compile

```bash
mvn clean compile
```

### 5. Run the application

```bash
# Dev profile — seeds 3 demo users on first startup, enables SQL logging
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway automatically runs all pending migrations (`V1`, `V2`, `V3`, `V4`) on startup. `ddl-auto: validate` then verifies that Hibernate's view of the schema matches what Flyway created. If there is any mismatch the application refuses to start.

### 6. Stop the application

```bash
pkill -f "spring-boot:run"
# or Ctrl+C in the terminal where it is running
```

### 7. Stop PostgreSQL

```bash
brew services stop postgresql@16
```

### 8. Reset to a clean state

```bash
# Terminate active connections, drop, recreate
psql -h localhost -U postgres -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='membershipdb';"
psql -h localhost -U postgres -c "DROP DATABASE IF EXISTS membershipdb;"
psql -h localhost -U postgres -c "CREATE DATABASE membershipdb;"
```

Flyway will re-run all migrations on the next application startup.

### How the pieces fit together

```
pg_ctl / brew services  →  controls the PostgreSQL server process (port 5432)
membershipdb            →  the database; exists inside PostgreSQL; created once, reset manually
Flyway                  →  runs SQL migrations inside membershipdb at app startup
Spring Boot             →  connects to membershipdb via HikariCP; ddl-auto=validate
start.sh                →  orchestrates all of the above in order
```

---

## Docker

A multi-stage `Dockerfile` and `docker-compose.yml` are provided for containerised deployment.

```bash
docker compose up
```

Starts PostgreSQL 16 and the application together. Flyway migrations run automatically. The app container waits for `pg_isready` before starting (`depends_on: condition: service_healthy`).

**Note:** Docker is not required for local development. `start.sh` is faster for iterative work and interview demos.

---

## Running Tests

```bash
mvn test
```

Tests run against **H2 in-memory** — no PostgreSQL required. Flyway is disabled; Hibernate creates the schema from entity metadata so PostgreSQL-specific DDL (`BIGSERIAL`, partial indexes) does not block the test suite.

**51 tests across two strategies:**

| Strategy | Count | Scope |
|---|---|---|
| Mockito unit tests (`SubscriptionServiceTest`) | 17 | Service logic in isolation — create, cancel, upgrade, downgrade, renew, batch renewal, optimistic lock rejection |
| Spring Boot integration tests (`MembershipApplicationTests`) | 34 | Full Spring context, real Tomcat on random port, `TestRestTemplate` — context load, business rules, REST endpoints, exception handling, tier eligibility |

---

## Interview Demo Flow

Run `./start.sh` first. Then use Swagger UI at `http://localhost:8080/swagger-ui/index.html`.

### Recommended sequence

| Step | Endpoint | What to show |
|---|---|---|
| 1 | `GET /api/v1/membership/health` | status=UP, live counts — confirms everything is running |
| 2 | `GET /api/v1/membership/tiers` | Silver / Gold / Platinum with all benefit flags |
| 3 | `GET /api/v1/plans/grouped` | 3×3 grid — tier → duration → plan with pricing |
| 4 | `GET /api/v1/plans/recommendations` | mostPopular, bestValue, beginnerFriendly |
| 5 | `POST /api/v1/users` | Create a user (use any unique email) |
| 6 | `GET /api/v1/users/{id}/tier-eligibility` | Dynamic eligibility evaluation — shows orderCount, spend, eligible tier |
| 7 | `POST /api/v1/membership/subscriptions` | Subscribe to Gold Monthly (`planId: 4`) |
| 8 | `PUT /api/v1/membership/subscriptions/{id}/upgrade` | Upgrade to Gold Yearly (`newPlanId: 6`) — shows pro-rated `paidAmount` |
| 9 | `PUT /api/v1/membership/subscriptions/{id}/downgrade` | Downgrade to Silver Monthly (`newPlanId: 1`) — `daysRemaining` drops to ~30, confirming `endDate` resets to the new plan's duration |
| 10 | `PUT /api/v1/membership/subscriptions/{id}/cancel` | Cancel — `cancelledAt` and reason populated |
| 11 | `GET /api/v1/membership/analytics` | Revenue, tier distribution, subscription counts |

### Concurrency demo (adversarial)

```bash
# Fire 5 concurrent subscription creates for the same user
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/membership/subscriptions \
    -H "Content-Type: application/json" \
    -d '{"userId":1,"planId":4,"autoRenewal":true}' &
done
wait
```

Expected: exactly 1 × HTTP 201, 4 × HTTP 409 (`USER_ALREADY_SUBSCRIBED` or `DATA_INTEGRITY_VIOLATION` — both are 409; which fires depends on whether the application-level guard or the DB partial unique index catches the race).

---

## Domain Model

### Tiers

| Tier | Level | Monthly Price | Discount | Free Delivery | Priority Support |
|---|---|---|---|---|---|
| Silver | 1 | ₹299 | 5% | No | No |
| Gold | 2 | ₹499 | 10% | Yes | No |
| Platinum | 3 | ₹799 | 15% | Yes | Yes |

### Plans (per tier)

| Type | Duration | Saving vs. monthly |
|---|---|---|
| Monthly | 1 month | — |
| Quarterly | 3 months | 5% off (0.95× multiplier) |
| Yearly | 12 months | 15% off (0.85× multiplier) |

All prices and multipliers are in `application.yml` — no code change needed to reprice.

### Subscription State Machine

```
PENDING ──► ACTIVE ──► CANCELLED  (terminal)
                  └──► SUSPENDED ──► ACTIVE
                  └──► EXPIRED   ──► ACTIVE  (via PUT /renew only)
```

One ACTIVE subscription per user is enforced at the database level by a PostgreSQL partial unique index.

---

## Key Design Decisions

### Three-layer concurrency protection

Duplicate active subscriptions are blocked by three independent mechanisms:

1. **Application guard** — `findActiveSubscriptionByUser()` check before `save()` handles the common single-threaded case.
2. **Optimistic locking** — `@Version Long version` on `Subscription`. Hibernate issues `UPDATE ... WHERE id=? AND version=?`; 0 rows updated → `StaleObjectStateException` → Spring Data wraps it → `JpaOptimisticLockingFailureException`. Both the Spring exception and the JPA spec exception (`jakarta.persistence.OptimisticLockException`) are caught and returned as 409. The bulk expiry scheduler also increments `version` in its UPDATE to invalidate concurrent stale reads:
   ```java
   @Modifying
   @Query("UPDATE Subscription s SET s.status = 'EXPIRED', s.version = s.version + 1
           WHERE s.status = 'ACTIVE' AND s.endDate < :now")
   int bulkExpireSubscriptions(@Param("now") LocalDateTime now);
   ```
3. **Database constraint** — a PostgreSQL partial unique index enforced at write time:
   ```sql
   CREATE UNIQUE INDEX uq_user_active_subscription
     ON subscriptions(user_id) WHERE status = 'ACTIVE';
   ```
   Even if two threads simultaneously pass the application check, only one INSERT succeeds. The second throws `DataIntegrityViolationException` → 409. The partial clause excludes cancelled and expired rows, so historical data is never blocked.

### REQUIRES_NEW renewal isolation

Auto-renewals run in a nightly batch. Each renewal commits in its own `REQUIRES_NEW` transaction via `SubscriptionRenewalProcessor` — a separate Spring bean so the `@Transactional` annotation is honoured through the proxy (self-invocation bypass avoided). One failed renewal never rolls back the rest.

`processRenewals()` runs with `Propagation.NOT_SUPPORTED` so entities are loaded detached, then each `renewSingle()` call merges cleanly into its own fresh transaction.

### Configurable pricing — zero hardcoded numbers

All business parameters live in `application.yml` under `membership:`, bound via `@ConfigurationProperties`:

```yaml
membership:
  tiers:
    gold:
      base-price: 499
      discount-percentage: 10.00
      free-delivery: true
      delivery-days: 3
  plan-discounts:
    quarterly-multiplier: 0.95
    yearly-multiplier: 0.85
```

Adding a Diamond tier or repricing Silver requires only a YAML change and restart — no code change, no recompile.

### Flyway over Hibernate DDL

`ddl-auto: validate` means Hibernate validates the schema after Flyway creates it. The application refuses to start if they diverge. Every structural change is a named, ordered, checksummed SQL file in source control. `flyway.clean-disabled: true` in the prod profile prevents accidental schema wipe.

### Performance — JOIN FETCH and partial indexes

List queries use explicit `JOIN FETCH` to prevent N+1. The paginated admin query uses a separate `countQuery` — required by Hibernate when a value query contains `JOIN FETCH`, otherwise Hibernate applies pagination in memory and logs `HHH90003004`.

V3 adds partial indexes precisely targeting the scheduler queries:

```sql
-- findSubscriptionsForRenewal
CREATE INDEX idx_subscriptions_next_billing
    ON subscriptions(next_billing_date)
    WHERE status = 'ACTIVE' AND auto_renewal = true;
```

### Caffeine caching

Plan and tier lists — stable reference data queried on every request — are cached for 10 minutes (`maximumSize=200, expireAfterWrite=10m`). No `@CacheEvict` is needed because there is no plan management API; the TTL is the backstop for any manual DB change.

### Tier eligibility — demo implementation

`TierEvaluationService` evaluates the highest tier a user qualifies for based on order count, monthly spend, and cohort membership. The evaluation engine, criteria table (`tier_eligibility_criteria`), and API endpoint (`GET /users/{id}/tier-eligibility`) are fully implemented.

**The current implementation uses deterministic demo data**, not real order history, because the assignment does not provide an Order Service, transaction database, or customer segmentation source:

- `fetchOrderSummary` derives order count as `(userId % 20) × 2` and monthly spend as `min(userId × ₹500, ₹10 000)`
- `isUserInCohort` assigns even-numbered user IDs to `PREMIUM_COHORT` (the Platinum cohort gate)

**Tier eligibility is intentionally not enforced during subscription creation.** Any user can subscribe to any plan regardless of their evaluated tier. Enforcing a gate based on synthetic demo data would produce incorrect business behaviour and misrepresent what the production system would do.

In production, only the two private stub methods need replacing:

| Integration point | Method to replace | Data provided |
|---|---|---|
| Order Service | `fetchOrderSummary()` | Rolling-window order count and spend |
| Payments / Commerce Service | `fetchOrderSummary()` | Cumulative spend within the evaluation window |
| Customer Segmentation Service | `isUserInCohort()` | Cohort assignment (e.g. `PREMIUM_COHORT`) |

The `TierEvaluationService` interface, the criteria table, and the evaluation logic are production-ready. The wiring point for enforcement in `createSubscription()` — a call to `isEligibleForTier()` after the duplicate-active check — is intentionally left for when real data is available.

### Upgrade and downgrade — endDate anchoring

**Upgrade** recalculates `endDate` from the original `startDate` (`startDate + newPlan.durationInMonths`). This preserves the billing period anchor and feeds the pro-rated charge calculation: unused days on the current plan are credited against the cost of the remaining days on the new plan.

**Downgrade** resets `endDate` to `now + newPlan.durationInMonths`. The plan change takes effect immediately; the new, shorter duration runs from the moment of the request. `paidAmount` is not adjusted on downgrade — no refund is calculated.

---

## API Reference

### Plans

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/membership/plans` | All 9 active plans with pricing and savings |
| GET | `/api/v1/membership/plans/{id}` | Single plan |
| GET | `/api/v1/membership/plans/tier/{name}` | Plans for a given tier (SILVER, GOLD, PLATINUM) |
| GET | `/api/v1/membership/plans/type/{type}` | Plans by duration (MONTHLY, QUARTERLY, YEARLY) |
| GET | `/api/v1/plans/grouped` | Plans nested by tier then duration |
| GET | `/api/v1/plans/compare?planIds=1,4,7` | Side-by-side comparison |
| GET | `/api/v1/plans/recommendations` | Opinionated picks: mostPopular, bestValue, beginnerFriendly |

### Tiers

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/membership/tiers` | All tiers with computed benefits list |
| GET | `/api/v1/membership/tiers/{name}` | Tier by name |

### Subscriptions

All state transitions use `PUT`. This is deliberate — each is an idempotent targeted update on a sub-resource.

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/membership/subscriptions` | Create subscription |
| GET | `/api/v1/membership/subscriptions` | Paginated admin list (`?page=0&size=20&sort=id,desc`) |
| GET | `/api/v1/membership/subscriptions/user/{userId}` | All subscriptions for a user |
| GET | `/api/v1/membership/subscriptions/user/{userId}/active` | User's current active subscription |
| PUT | `/api/v1/membership/subscriptions/{id}` | Update subscription settings (autoRenewal, status) |
| PUT | `/api/v1/membership/subscriptions/{id}/upgrade` | Upgrade to higher tier or longer duration |
| PUT | `/api/v1/membership/subscriptions/{id}/downgrade` | Downgrade to lower tier |
| PUT | `/api/v1/membership/subscriptions/{id}/cancel` | Cancel active subscription |
| PUT | `/api/v1/membership/subscriptions/{id}/renew` | Renew expired subscription |

### Users

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/users` | Create user |
| GET | `/api/v1/users` | Paginated user list (`?page=0&size=10&sort=name,asc`) |
| GET | `/api/v1/users/{id}` | Get user |
| GET | `/api/v1/users/email/{email}` | Look up user by email |
| PUT | `/api/v1/users/{id}` | Full update (all fields replaced) |
| PATCH | `/api/v1/users/{id}` | Partial update (name, phone, address, city, state, pincode, status) |
| DELETE | `/api/v1/users/{id}` | Delete user (blocked if ACTIVE subscription exists) |
| GET | `/api/v1/users/{id}/tier-eligibility` | Dynamic tier eligibility evaluation |
| GET | `/api/v1/users/{userId}/subscription` | User's active subscription |
| GET | `/api/v1/users/{userId}/subscriptions` | User's subscription history |
| POST | `/api/v1/users/{userId}/subscriptions` | Create subscription (userId taken from path) |
| PUT | `/api/v1/users/{userId}/subscriptions/{subscriptionId}` | User-scoped subscription update (autoRenewal, status) |
| PUT | `/api/v1/users/{userId}/subscriptions/{subscriptionId}/upgrade` | User-scoped upgrade |
| PUT | `/api/v1/users/{userId}/subscriptions/{subscriptionId}/cancel` | User-scoped cancel |

### Observability

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/membership/health` | Status, active subscription count, tier distribution |
| GET | `/api/v1/membership/analytics` | Revenue totals, tier popularity, plan type distribution |
| GET | `/actuator/health` | Spring Boot actuator health |
| GET | `/actuator/metrics` | Micrometer metrics |

Full interactive docs: `http://localhost:8080/swagger-ui/index.html`

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `membershipdb` | Database name |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `SERVER_PORT` | `8080` | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | Profile (`dev` / `prod`) |
| `DB_POOL_SIZE` | `10` | HikariCP max pool size |

---

## Production Deployment

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_HOST=<host> DB_NAME=<db> DB_USERNAME=<user> DB_PASSWORD=<secret> \
java -jar target/membership-program-1.0.0.jar
```

The `prod` profile:
- Restricts actuator exposure to `/actuator/health` only (`show-details: never`)
- Disables `flyway clean` to prevent accidental schema wipe
- Increases HikariCP pool: 20 max, 5 idle minimum
- Sets `show-sql: false`

---

## Intentional Tradeoffs

| Decision | Rationale |
|---|---|
| No authentication | Out of scope; `requireUserOwnsSubscription()` demonstrates ownership awareness |
| Tier eligibility — demo implementation | The evaluation engine and API are fully implemented; the underlying data is deterministic demo data, not real order history. Enforcement is intentionally absent from subscription creation. See the dedicated section in Key Design Decisions above for full context and the production integration map. |
| No payment gateway | `paidAmount` tracks billing; pro-rata logic is fully implemented without a real payment call |
| Caffeine over Redis | Single-instance; Redis adds operational complexity without benefit here |
| Monolith over microservices | Membership domain at startup scale; service decomposition is premature |
| `getUserSubscriptions()` returns a List | Subscription history for one user is bounded in practice; a `Page` return here adds call-site complexity for marginal benefit |

## What Was Intentionally Not Built

- **Auth (OAuth2/JWT)** — cross-cutting concern; belongs in its own layer
- **Webhook / event system** — requires a message broker
- **Admin role separation** — RBAC belongs after the auth layer is in place
- **Tier eligibility enforcement at subscription time** — enforcement is intentionally absent while data is synthetic. When a real Order Service and spend history are available, the gate belongs in `createSubscription()` as a call to `TierEvaluationService.isEligibleForTier()` after the duplicate-active check. The wiring point is unambiguous.
- **Full order service integration** — `TierEvaluationService` isolates the dependency. Only `fetchOrderSummary()` and `isUserInCohort()` need replacing; the interface, criteria table, and evaluation logic are production-ready.
