# FirstClub Membership Program

A production-aware membership management backend built with Spring Boot 3.2, PostgreSQL, and Java 17.

Users subscribe to **Silver / Gold / Platinum** membership tiers with **Monthly / Quarterly / Yearly** billing — 9 plans in total. The system handles the full subscription lifecycle: creation, upgrade, downgrade, cancellation, and scheduled renewal.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      REST Layer                              │
│  MembershipController  │  UserController  │  PlanController  │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│                     Service Layer                            │
│  MembershipService (tiers)  │  PlanService  │  UserService   │
│  SubscriptionService        │  TierEvaluationService         │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│                   Repository Layer                           │
│         Spring Data JPA + custom JPQL queries                │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│                PostgreSQL 15  (Flyway-managed)               │
│   V1: core schema + partial unique index                     │
│   V2: tier eligibility criteria                              │
└──────────────────────────────────────────────────────────────┘
```

The service layer follows the Single Responsibility Principle — each service owns one domain concern. Controllers inject only the services they need.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Runtime | Java 17 |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 15 |
| ORM | Spring Data JPA / Hibernate |
| Migrations | Flyway |
| Caching | Spring Cache + Caffeine |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Validation | Jakarta Bean Validation 3 |
| Build | Maven 3.6+ |
| Container | Docker (multi-stage build) |
| Test | JUnit 5 + Mockito + Spring Boot Test |

---

## Quick Start

### Option A — Docker (recommended, no local JDK required)

```bash
docker compose up
```

Starts PostgreSQL and the application in one command. Flyway migrations run automatically.

### Option B — Local development

**Prerequisites:** Java 17+, Maven 3.6+, Docker

```bash
# 1. Start PostgreSQL only
docker compose up -d postgres

# 2. Run the app with the dev profile (seeds 3 demo users)
mvn spring-boot:run -Dspring.profiles.active=dev
```

Once started:

| Resource | URL |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health endpoint | http://localhost:8080/api/v1/membership/health |
| Analytics | http://localhost:8080/api/v1/membership/analytics |
| Actuator | http://localhost:8080/actuator/health |

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

---

## Swagger Demo Flow

1. `GET /api/v1/users` — view the three pre-seeded demo users
2. `GET /api/v1/membership/plans` — browse all 9 plans with pricing and savings
3. `POST /api/v1/membership/subscriptions` — subscribe user 1 to Gold Monthly (plan 4):
   ```json
   { "userId": 1, "planId": 4, "autoRenewal": true }
   ```
4. `GET /api/v1/membership/subscriptions/user/1/active` — confirm active subscription
5. `GET /api/v1/users/1/tier-eligibility` — evaluate tier upgrade eligibility
6. `GET /api/v1/membership/health` — live system metrics
7. `GET /api/v1/membership/analytics` — revenue and tier popularity breakdown

---

## Domain Model

### Tiers

| Tier | Level | Monthly Price | Discount | Free Delivery |
|---|---|---|---|---|
| Silver | 1 | ₹299 | 5% | No |
| Gold | 2 | ₹499 | 10% | Yes |
| Platinum | 3 | ₹799 | 15% | Yes |

### Plans (per tier)

| Type | Duration | Discount vs. Monthly |
|---|---|---|
| Monthly | 1 month | — |
| Quarterly | 3 months | 5% off |
| Yearly | 12 months | 15% off |

### Subscription State Machine

```
PENDING ──► ACTIVE ──► CANCELLED
                  └──► SUSPENDED ──► ACTIVE
                  └──► EXPIRED   ──► ACTIVE (renewal)
```

One active subscription per user is enforced at the database level.

---

## Key Design Decisions

### Flyway over Hibernate DDL auto-create

`ddl-auto: validate` ensures the database schema is explicitly versioned and never silently altered. Flyway provides a reproducible, auditable migration history. Every structural change is a named, ordered SQL file.

### Three-layer concurrency protection

Preventing duplicate active subscriptions under concurrent requests:

1. **Application guard** — `findActiveSubscriptionByUser()` before `save()` catches the common case fast.
2. **Optimistic locking** — `@Version Long version` on `Subscription` increments on every UPDATE. Concurrent modifications from two transactions fail with `OptimisticLockException` → 409.
3. **Database constraint** — a PostgreSQL partial unique index:
   ```sql
   CREATE UNIQUE INDEX uq_user_active_subscription
     ON subscriptions(user_id) WHERE status = 'ACTIVE';
   ```
   Even if two threads simultaneously pass the application check, only one INSERT succeeds. The other gets a `DataIntegrityViolationException` → 409.

Defense-in-depth: the DB constraint is the authoritative backstop.

### Configurable pricing — no hardcoded numbers

All business parameters live in `application.yml` under the `membership:` prefix, bound via `@ConfigurationProperties`. To change Silver's price or Gold's delivery SLA, edit YAML and restart — no code change, no recompilation:

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

### Service split (SRP)

`MembershipService` (tiers) / `PlanService` (plans) / `SubscriptionService` (lifecycle + queries) have clear ownership boundaries. Controllers inject only what they need. This is an explicit decision against a god-service pattern.

### Bulk scheduler

Subscription expiry uses a single JPQL bulk `UPDATE` instead of fetching rows into memory:

```java
@Modifying
@Query("UPDATE Subscription s SET s.status = 'EXPIRED' WHERE s.status = 'ACTIVE' AND s.endDate < :now")
int bulkExpireSubscriptions(@Param("now") LocalDateTime now);
```

The `@Scheduled` runner fires hourly. Renewals run daily at 6 AM.

### Caffeine caching

Plan and tier lists — stable reference data read on every request — are cached for 10 minutes. This avoids repeated database roundtrips for purely static reads:

```
@Cacheable("plans") → PlanService.getActivePlans()
@Cacheable("tiers") → MembershipService.getAllTiers()
```

---

## API Reference

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/membership/plans` | All active plans |
| GET | `/api/v1/membership/tiers` | All tiers with benefits list |
| POST | `/api/v1/membership/subscriptions` | Create subscription |
| PUT | `/api/v1/membership/subscriptions/{id}/cancel` | Cancel subscription |
| PUT | `/api/v1/membership/subscriptions/{id}/upgrade` | Upgrade to higher tier/duration |
| POST | `/api/v1/membership/subscriptions/{id}/renew` | Renew expired subscription |
| GET | `/api/v1/membership/subscriptions?page=0&size=20` | Paginated subscription list |
| GET | `/api/v1/users/{id}/tier-eligibility` | Tier eligibility evaluation |
| GET | `/api/v1/membership/health` | System health + live metrics |
| GET | `/api/v1/membership/analytics` | Revenue and tier analytics |
| GET | `/api/v1/plans/grouped` | Plans nested by tier then duration |
| GET | `/api/v1/plans/compare?planIds=1,4,7` | Side-by-side plan comparison |
| GET | `/api/v1/plans/recommendations` | Opinionated plan picks |

Full interactive docs: `http://localhost:8080/swagger-ui.html`

---

## Running Tests

```bash
mvn test
```

Tests run against H2 in-memory — no PostgreSQL required. Flyway is disabled; Hibernate creates the schema from entity metadata.

**34 tests:**
- 27 integration tests (`@SpringBootTest` + `TestRestTemplate`) covering context load, business rules, REST endpoints, exception handling, and tier eligibility
- 7 Mockito unit tests for `SubscriptionService` — create/cancel/upgrade paths with mocked repositories

---

## Production Deployment

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_HOST=<host> DB_NAME=<db> DB_USERNAME=<user> DB_PASSWORD=<secret> \
java -jar target/membership-program-1.0.0.jar
```

The `prod` profile:
- Restricts actuator to `/actuator/health` only
- Sets `show-details: never` (no system internals exposed to unauthenticated callers)
- Disables Flyway `clean` (schema wipe is prevented)
- Increases connection pool (20 max, 5 idle minimum)

---

## Intentional Tradeoffs

| Decision | Rationale |
|---|---|
| No authentication | Out of scope; `requireUserOwnsSubscription()` demonstrates ownership-awareness |
| Tier eligibility stub | Order data is external; the interface isolates the integration point for easy replacement |
| No payment gateway | Amounts tracked in `paid_amount`; pro-rating logic implemented without a real payment call |
| Caffeine over Redis | Single-instance caching fits this scale; Redis adds operational complexity without benefit here |
| Monolith, not microservices | A membership domain at startup scale doesn't justify service decomposition overhead |

---

## What Was Intentionally Not Built

- **Auth (OAuth2/JWT)** — a cross-cutting concern that belongs in its own layer
- **Webhook/event system** — requires a message broker; significant infrastructure addition
- **Admin role separation** — RBAC belongs after the auth layer is in place
- **Full order service integration** — tier eligibility uses a stub; the `TierEvaluationService` interface contract is defined and ready for a real implementation
