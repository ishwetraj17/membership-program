# 00 — Time and Money Rules

> **Why this document exists:** Time and money are the two areas where subtle
> bugs compound silently and are the hardest to detect in logs.  These rules
> are non-negotiable for correctness.

---

## Part 1 — Time

### Rule T-1: Never call `Instant.now()`, `LocalDate.now()`, or `System.currentTimeMillis()` directly

These methods bypass the injectable `ClockService` and make the call-site
untestable with a fixed/frozen clock.

```java
// ❌ Produces non-deterministic test output and can't be frozen
Instant now = Instant.now();

// ✅ Deterministic, injectable, test-friendly
Instant now = clock.now();   // ClockService injected via constructor
```

**Only three call-sites are allowed to touch raw time:**
1. `SystemClockService` — the production implementation.
2. Test setup code that creates a `Clock.fixed(...)` to pass into
   `SystemClockService(Clock clock)` for frozen-time tests.
3. Micrometer/Spring Boot infrastructure (non-domain code).

### Rule T-2: All time stored in the database is UTC

Columns are typed `TIMESTAMP WITHOUT TIME ZONE` in Postgres, which stores no
zone information.  The application always writes UTC values.  Timezone
conversion (if needed) happens at the API response layer, never in business
logic or persistence.

### Rule T-3: Use `LocalDateTime` for persistence, `Instant` for business logic

- `Instant` is zone-free and correct for arithmetic (SLA computation, dunning
  intervals, expiry checks, audit timestamps).
- `LocalDateTime` is used for JPA entity fields because Hibernate maps it
  directly to `TIMESTAMP WITHOUT TIME ZONE` without ambiguity.
- Never use `ZonedDateTime` in entity fields.

### Rule T-4: Duration arithmetic uses `Duration` or `ChronoUnit`, not raw milliseconds

```java
// ❌ Brittle — unclear unit, overflows easily
long diff = end.toEpochMilli() - start.toEpochMilli();
if (diff > 86400000) { ... }

// ✅ Readable, safe
if (Duration.between(start, end).toDays() > 1) { ... }
```

---

## Part 2 — Money

### Rule M-1: All monetary values are stored as `BIGINT` minor units

| Currency | Unit | Example |
|---|---|---|
| INR | Paise (1/100 rupee) | ₹100.50 → `10050` |
| USD | Cents (1/100 dollar) | $9.99 → `999` |
| EUR | Eurocent | €1.00 → `100` |

**Database column type:** `BIGINT NOT NULL`  
**Java type:** `long` (primitive, never `Long` in value-sensitive paths)

### Rule M-2: No `double` or `float` for monetary arithmetic

IEEE 754 floating-point arithmetic is non-associative and produces rounding
errors that vary by JVM and platform.

```java
// ❌ Incorrect — (0.1 + 0.2) = 0.30000000000000004 in IEEE 754
double a = 0.1, b = 0.2;
double sum = a + b;

// ✅ Correct — use Money value object
Money a = Money.ofMinor(CurrencyCode.INR, 10);  // 10 paise
Money b = Money.ofMinor(CurrencyCode.INR, 20);  // 20 paise
Money sum = a.add(b);                            // 30 paise, always
```

### Rule M-3: When converting from major units (user input), use `Money.ofMajor` with HALF_EVEN

All rounding decisions use `RoundingMode.HALF_EVEN` (banker's rounding).  This
minimises cumulative rounding bias in bulk operations.

```java
// User types "100.005" in a form — convert at the API boundary
Money price = Money.ofMajor(CurrencyCode.INR, new BigDecimal("100.005"));
// → 10001 paise (0.005 rounds to 0 or 1 depending on even digit)
```

`Money.ofMajor` is only for **API boundary conversion**.  Once in the system
as minor units, all arithmetic uses `Money.add`, `Money.subtract`, etc.

### Rule M-4: Currency must always be explicit — no implicit INR default

Every `Money` instance carries its `CurrencyCode`.  Cross-currency arithmetic
(e.g., `inrMoney.add(usdMoney)`) throws `CurrencyMismatchException` at the
point of the operation, not silently at display time.

### Rule M-5: `formatMajor()` is for logs and documentation only

```java
log.info("Invoice total: {}", invoice.getAmount().formatMajor()); // ✅ OK in logs
```

Never use `formatMajor()` output for persistence, API responses, or
comparison.  Always use `getAmountMinor()`.

### Rule M-6: API responses expose both minor units and a formatted string

```json
{
  "amountMinor": 10050,
  "currencyCode": "INR",
  "amountFormatted": "₹100.50"
}
```

Clients use `amountMinor` for all arithmetic.  `amountFormatted` is for
display only.

### Rule M-7: Division is explicit with declared scale and rounding

If a use-case requires dividing a monetary amount (e.g., splitting an invoice
across line items), document the rounding rule in a comment at the call site:

```java
// Split total evenly; if not divisible, the remainder goes to the first item.
// Rounding: HALF_EVEN to minimise bias.
long itemMinor = totalMinor / itemCount;
long remainder = totalMinor % itemCount;
```

Never use `BigDecimal.divide(divisor)` without specifying scale and rounding
mode — it throws `ArithmeticException` on non-terminating decimals.

---

## Part 3 — Cross-cutting

### Rule C-1: Monetary comparisons use `Money` methods, not raw longs

```java
// ❌ Bypasses currency check
if (invoice.getAmountMinor() > 0) { ... }

// ✅ Currency-safe
if (invoice.getAmount().isPositive()) { ... }
if (invoice.getAmount().greaterThan(threshold)) { ... }
```

### Rule C-2: Zero amounts are valid and must not be special-cased

`Money.zero(CurrencyCode.INR)` is a valid, well-formed value.  Zero-amount
subscriptions, trials, and discounts are legal business states.  Do not add
`if (amount == 0) return` guards unless the spec explicitly excludes zero.

---

## Known trade-offs

| Trade-off | Rationale |
|---|---|
| `long` instead of `BigDecimal` for hot paths | Avoids heap allocation in rate-limited paths (e.g., rate-limit evaluation); `BigDecimal` is used only at API boundary conversion |
| `TIMESTAMP WITHOUT TIME ZONE` instead of `TIMESTAMPTZ` | Avoids Postgres timezone coercion surprises; application owns all UTC conversion |
| `HALF_EVEN` rounding throughout | Minimises systematic bias in bulk discount/tax calculations |
