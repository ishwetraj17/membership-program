# 00 — Engineering Principles

> **Scope:** Platform-wide guardrails. These rules apply to every line of code
> written in this repository. Individual phase documents build on top of these
> principles. When in doubt, the principle here wins.

---

## 1. Harden, don't feature-add

Every change must make the system **more correct, observable, and safe** before
it makes it more capable.  New endpoints and new features are always postponed
until the invariants underneath them are solid.

---

## 2. Technology constraints (locked)

| Concern | Choice | Rationale |
|---|---|---|
| Runtime | Java 17 | LTS, records, sealed classes, pattern matching |
| Framework | Spring Boot 3.4.x | GA, no milestone deps allowed |
| Database | PostgreSQL | ACID, row-level locking, Flyway migrations |
| Cache / lock | Redis 7 | Atomic Lua scripts, sorted sets for rate limiting |
| Metrics | Micrometer | Vendor-neutral; PromQL-compatible label naming |
| Tests | JUnit 5 + Testcontainers | Real DB/Redis in tests; no H2 |
| Schema evolution | Flyway | Sequential versioned scripts, never `spring.jpa.ddl-auto` |

---

## 3. Dependency & injection rules

- **Constructor injection only.**  Field injection (`@Autowired` on fields) is
  banned — it hides dependencies and makes testing harder.
- No Lombok `@RequiredArgsConstructor` on `@Configuration` or `@Component`
  classes that hold injected beans.  Write the constructor explicitly.
- No circular dependencies.  If a cycle appears, extract an interface or a
  shared helper.

---

## 4. Time rule (do not call `Instant.now()` directly)

```java
// ❌ Forbidden — unbounded, untestable, fails in frozen-time tests
Instant.now();
LocalDate.now();
System.currentTimeMillis();

// ✅ Required — injectable, deterministic, testable
@Component
class MyService {
    private final ClockService clock;   // constructor-injected
    
    void doWork() {
        Instant now = clock.now();
    }
}
```

**Why:** Production tests that verify time-sensitive logic (SLA breach,
expiry detection, dunning schedule) need to run with a frozen clock.  See
`00-time-and-money-rules.md` for the full contract.

---

## 5. Money rule (no `double`, no `float`, no raw `BigDecimal` columns)

```java
// ❌ Forbidden — floating-point is non-associative; rounding differs by JVM
double amount = 100.50;

// ❌ Forbidden — no currency context, ambiguous scale
BigDecimal amount = new BigDecimal("100.50");

// ✅ Required — explicit currency, stored as long minor units
Money price = Money.ofMinor(CurrencyCode.INR, 10050L);
```

All monetary columns in the database are `BIGINT` storing **minor units**
(paise for INR, cents for USD).  See `00-time-and-money-rules.md`.

---

## 6. Database rules

- Every table that is not an outbox or timeline has `created_at` / `updated_at`.
- All foreign keys are declared in the schema (Flyway).
- No `ON DELETE CASCADE` without explicit team sign-off.
- Indexes are always created with `IF NOT EXISTS`.
- New column additions must supply a `DEFAULT` or `NOT NULL` with backfill.
- One migration file = one logical concern.  Never combine unrelated changes.

---

## 7. Redis rules

- All keys follow the format `{env}:firstclub:{domain}:{subdomain}:{identifier}`.
- TTL is **mandatory** for every key that is not a permanent configuration entry.
- Multi-key operations must use Lua scripts (atomic) or `MULTI/EXEC` pipelines.
- Never use `KEYS *` in production.  Use `SCAN` with a cursor.

---

## 8. Idempotency (three-layer model)

Every write path that accepts money or state transitions must be idempotent at
three levels:

1. **Filter layer** (`IdempotencyFilter`) — deduplicates at the HTTP boundary
   using a Redis lock keyed on `Idempotency-Key`.
2. **Service layer** — checks for an existing successful record before
   processing (e.g., "has this payment already been captured?").
3. **Database layer** — unique constraint on the business key ensures the DB
   rejects duplicates even if the service check races.

---

## 9. Concurrency declaration

Services that touch shared state must declare their concurrency strategy in a
Javadoc comment:

```java
/**
 * Concurrency: optimistic locking via @Version on PaymentIntent.
 * Concurrent captures trigger ObjectOptimisticLockingFailureException → 409.
 */
```

Acceptable strategies: `optimistic locking`, `pessimistic row lock`,
`Redis mutex`, `single writer per entity`.  "Undefined" is not acceptable.

---

## 10. Ledger / accounting rules

- Ledger rows are **immutable** once written.  No `UPDATE`; corrections are new
  rows with a `REVERSAL` type.
- Every debit entry must have a corresponding credit entry in the same
  transaction.  The invariant checker calls `InvariantViolationException` if
  they don't balance.
- No negative-balance accounts without explicit allowance.

---

## 11. Exception hierarchy

All domain exceptions extend `BaseDomainException`.  This allows the
`GlobalExceptionHandler` to handle every module exception with a single
`@ExceptionHandler(BaseDomainException.class)` that reads the typed
`httpStatus` and `errorCode` from the exception itself.

| Exception | HTTP | Use for |
|---|---|---|
| `InvariantViolationException` | 500 | Accounting or state invariant violated |
| `StaleOperationException` | 409 | Stale-entity operation (dunning, reconcile) |
| `RequestInFlightException` | 409 | Concurrent operation on same entity |
| `IdempotencyConflictException` | 409 | Key replay with different body / in-flight |
| `ConcurrencyConflictException` | 409 | Optimistic lock lost (typed complement to Spring's exception) |

---

## 12. Observability rules

- Every public service method emits a Micrometer `Timer` and `Counter`.
- Metric names use `snake_case` with domain-prefixed names:
  `firstclub.payments.capture.duration`, `firstclub.subscription.renewal.total`.
- MDC keys `requestId`, `correlationId`, `merchantId`, `apiVersion` must be set
  before any log line in a request context.
- No `System.out.println`. No bare `e.printStackTrace()`.

---

## 13. Testing standards

- Unit tests: plain JUnit 5 + Mockito.  Zero Spring context startup.
- Integration tests: `@SpringBootTest` + Testcontainers (real Postgres, real
  Redis).  Use `@DirtiesContext` only when truly necessary.
- Every `Money` operation, `ApiVersion` comparison, and `ClockService` usage
  must have a corresponding unit test.
- No `Thread.sleep()` in tests.  Use `Clock.fixed()` or `AwaitilityException`.

---

## 14. Documentation standards

- New modules ship with a single `README.md` or ADR in `/docs/`.
- Public APIs are documented in `/docs/api/`.
- Every architectural decision that violates a general best practice includes
  a `KNOWN_LIMITS.md` section explaining why.

---

## Non-goals (Phase 1 scope)

- No UI changes.
- No new user-visible business features.
- No performance tuning beyond what is necessary for correctness.
- No microservice extraction.
