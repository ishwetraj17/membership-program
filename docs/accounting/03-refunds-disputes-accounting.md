# Refunds and Disputes Accounting

## Overview

Refunds and disputes are two distinct paths through which money flows back after a successful payment. Both generate ledger entries, but the accounting treatment differs significantly.

---

## Refunds

### What Is a Refund?

A refund is a **voluntary reversal** initiated by the merchant or customer service team. The merchant agrees to return money to the customer.

### Refund Lifecycle

```
PENDING → APPROVED → COMPLETED
       ↘ REJECTED
```

| Status | Meaning |
|---|---|
| `PENDING` | Refund request created; awaiting gateway processing |
| `APPROVED` | Gateway accepted the reversal |
| `COMPLETED` | Funds returned to customer; ledger entry posted |
| `REJECTED` | Gateway declined (e.g., original payment too old) |

### Refund Accounting (on `COMPLETED`)

```
DR  REFUND_EXPENSE          {refund.amount}
CR  CASH                                    {refund.amount}
reference_type = REFUND_V2
reference_id   = {refundId}
```

**What this means:**
- `REFUND_EXPENSE` (EXPENSE): increases — the cost of this refund hits the P&L
- `CASH` (ASSET): decreases — cash leaves the business

### Payment Record Update (on Refund Completion)

```
payment_intents_v2:
  refunded_amount += refund.amount
  net_amount = captured_amount - refunded_amount - disputed_amount
```

### Invariants

- `refunded_amount + new_refund_amount <= captured_amount` — enforced before creation with SELECT FOR UPDATE on the payment row
- No ledger entry is posted until `status = COMPLETED` — pending refunds do not affect the ledger

### Partial Refunds

Partial refunds are supported. Multiple `RefundV2` rows can reference the same `PaymentIntentV2`, as long as the cumulative `refunded_amount` does not exceed `captured_amount`.

**Example:**
```
Payment captured: ₹499
Refund 1 (partial): ₹100  → refunded_amount = ₹100
Refund 2 (partial): ₹200  → refunded_amount = ₹300
Refund 3 attempt: ₹250    → REJECTED: 300 + 250 = 550 > 499
```

---

## Disputes (Chargebacks)

### What Is a Dispute?

A dispute is a **forced reversal** initiated by the customer's bank or card network. The customer contacts their bank claiming they did not authorize the charge or did not receive the goods/service.

Unlike a refund, the **merchant does not initiate** a dispute. The bank creates the chargeback automatically.

### Dispute Lifecycle

```
OPEN → UNDER_REVIEW → WON
                    → LOST
                    → CLOSED
```

| Status | Meaning |
|---|---|
| `OPEN` | Dispute received; funds are reserved |
| `UNDER_REVIEW` | Merchant has submitted evidence; outcome pending |
| `WON` | Chargeback decided in merchant's favor; reserve released |
| `LOST` | Chargeback confirmed; merchant loses the funds |
| `CLOSED` | Dispute closed without formal resolution |

### Dispute Accounting — Step by Step

#### On `OPEN`

```
DR  DISPUTE_RESERVE          {dispute.amount}
CR  CASH                                      {dispute.amount}
entry_type     = DISPUTE_OPENED
reference_type = DISPUTE
reference_id   = {disputeId}
```

**What this means:** Cash is moved to a reserve account. The money is still "in the system" but earmarked for the dispute.  
`DISPUTE_RESERVE` is an ASSET account — we still hold this money (the bank has not yet reversed it).

#### On `WON` (merchant wins)

```
DR  CASH                     {dispute.amount}
CR  DISPUTE_RESERVE                          {dispute.amount}
entry_type = DISPUTE_WON
```

**What this means:** No money was actually lost. Reserve is released back to CASH.

#### On `LOST` (dispute confirmed as chargeback)

```
DR  CHARGEBACK_EXPENSE       {dispute.amount}
CR  DISPUTE_RESERVE                           {dispute.amount}
entry_type = CHARGEBACK_POSTED
```

**What this means:** The money is forfeited. `CHARGEBACK_EXPENSE` is an EXPENSE account — this is a real cost to the business that hits the P&L.

### Payment Record Update (on dispute open)

```
payment_intents_v2:
  disputed_amount += dispute.amount
  net_amount = captured_amount - refunded_amount - disputed_amount
```

### Dispute Reserve Invariant

The `DISPUTE_RESERVE` account balance must never go negative. A negative balance would mean we are releasing more from the reserve than was ever placed in it — an accounting error.

```
Invariant: DISPUTE_RESERVE balance >= 0 at all times
Checked by: LedgerReportingService.getAccountBalance("DISPUTE_RESERVE") in integrity checker
```

---

## Refund vs Dispute — Comparison

| Dimension | Refund | Dispute |
|---|---|---|
| **Who initiates** | Merchant / support team | Customer's bank |
| **Merchant control** | Yes | Limited (can submit evidence) |
| **Ledger on creation** | No entry yet (`PENDING`) | Yes — reserve posted immediately |
| **Ledger account DR** | `REFUND_EXPENSE` | `DISPUTE_RESERVE` → `CHARGEBACK_EXPENSE` |
| **Outcome** | Completed or rejected | Won (no loss) or Lost (chargeback) |
| **Net effect on CASH** | Always decreases CASH | Decreases CASH immediately; may recover if Won |
| **P&L impact** | `REFUND_EXPENSE` increases | `CHARGEBACK_EXPENSE` increases (if Lost) |
| **Reversible** | No — reversal requires a new manual entry | Partially — Won reclaims from reserve |

---

## Combined Scenario: Refund After Dispute

If a dispute is raised AND a refund was previously issued for the same payment:

1. `refunded_amount` already deducted
2. `disputed_amount` is now set
3. `net_amount = captured_amount - refunded_amount - disputed_amount` could go negative if:
   - `refunded_amount + disputed_amount > captured_amount`

**This is a double-recovery scenario.** A customer gets both a refund AND a chargeback.

**Current protection:** `RefundV2ServiceImpl` validates `amount <= (captured_amount - refunded_amount)` before creating a refund. But it does not account for pending disputes. This is a **known gap**.

---

## Phase 15 — Refund and Dispute Robustness Hardening

### Refund Fingerprint Idempotency

Every refund creation now carries a **request fingerprint** — a 64-character SHA-256 hex string stored in `refunds_v2.request_fingerprint` (UNIQUE partial index, NULL-safe).

**Fingerprint computation:**

```
SHA-256( merchantId + ":" + paymentId + ":" + amount + ":" + reasonCode )
```

Callers may provide their own fingerprint via `RefundCreateRequestDTO.requestFingerprint`. If omitted, the service computes one from the four fields above.

**Replay behaviour:**

| Condition | Result |
|---|---|
| Fingerprint matches an existing row | Existing refund DTO returned — no new DB row, no accounting posted |
| No match | Normal refund creation proceeds |

This makes refund creation **idempotent by default** even across retries, network timeouts, and at-least-once message delivery.

**DB schema change (V46):**

```sql
ALTER TABLE refunds_v2 ADD COLUMN request_fingerprint VARCHAR(255) NULL;
CREATE UNIQUE INDEX idx_refunds_v2_fingerprint
    ON refunds_v2(request_fingerprint)
    WHERE request_fingerprint IS NOT NULL;
```

---

### Dispute Reserve / Resolution One-Time Posting Guards

Two new boolean columns on the `disputes` table record whether each critical accounting step has been executed:

| Column | Set to `true` when | Guards against |
|---|---|---|
| `reserve_posted` | `postDisputeOpen()` completes in `openDispute()` | Posting the DISPUTE_RESERVE entry a second time |
| `resolution_posted` | `postDisputeWon()` or `postDisputeLost()` completes | Posting the WON/LOST accounting entry a second time |

If `resolveDispute()` is called on a dispute that already has `resolution_posted = true`, the service throws:

```
HTTP 409 CONFLICT
errorCode: DISPUTE_RESOLUTION_ALREADY_POSTED
```

No accounting call is made and no ledger entry is created.

**DB schema change (V46):**

```sql
ALTER TABLE disputes ADD COLUMN reserve_posted    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE disputes ADD COLUMN resolution_posted BOOLEAN NOT NULL DEFAULT FALSE;
```

---

### Evidence Deadline Visibility — `DisputeDueDateCheckerService`

`DisputeDueDateCheckerService.findDueSoon(withinDays)` returns all OPEN and UNDER_REVIEW disputes whose `due_by` falls within the next `withinDays` days, sorted ascending by urgency. An indexed column supports this query:

```sql
CREATE INDEX idx_disputes_due_by ON disputes(due_by) WHERE due_by IS NOT NULL;
```

Admin endpoint: `GET /api/v2/admin/disputes/due-soon?withinDays=7`

---

## Evidence Management

When a dispute is `OPEN` or `UNDER_REVIEW`, merchants can submit evidence:

```
POST /api/v2/disputes/{disputeId}/evidence
{
  "evidenceType": "CUSTOMER_COMMUNICATION",
  "description": "Customer confirmed receipt via email",
  "fileUrl": "..."
}
```

Evidence is stored in `dispute_evidence` table. Evidence submission does not post a ledger entry — it is operational data only.

---

## Settlement Interaction

Disputes affect the settlement calculation:

```
Net settlement amount = gross_captured - refunded - disputed
```

Only `net_amount` on `payment_intents_v2` is used in settlement batch calculations. This ensures refunded and disputed amounts are not double-counted in settlement.

---

## Phase 9 — Capacity Correctness Under Concurrency

### The Write-Skew Problem

Without row locking, two concurrent transactions can both *read* the same remaining capacity, independently pass the capacity guard, and then both *write* — resulting in a combined mutation that exceeds what was captured.

**Example (no locks):**

```
T1: reads payment — captured=100, refunded=0, disputable=100
T2: reads payment — captured=100, refunded=0, disputable=100

T1: decides to refund ₹70 (passes: 70 ≤ 100)
T2: decides to refund ₹80 (passes: 80 ≤ 100)

T1: writes refundedAmount = 70
T2: writes refundedAmount = 80  ← overwrites T1's update!
Final: refundedAmount = 80, but ₹70 was already sent to gateway → net = -50
```

This is **write skew** — both transactions make a locally-correct decision, but the combined effect violates the invariant.

### Why SELECT FOR UPDATE Solves This

`PaymentRepository.findByIdForUpdate()` issues `SELECT … FOR UPDATE` which places a **row-level write lock** on the `payments` row for the duration of the transaction.

```
T1: SELECT … FOR UPDATE on payment p1  ← acquires lock
T2: SELECT … FOR UPDATE on payment p1  ← BLOCKS (waits for T1)

T1: validates capacity, writes refundedAmount=70, COMMITS
T2: unblocks, re-reads fresh row: refundedAmount=70, refundable=30
T2: attempt to refund ₹80 → fails OVER_REFUND ✓
```

The lock serialises all concurrent mutations to the **same payment row**, turning the concurrent write-skew into a sequential, correct series of operations.

**Lock scope in the codebase:**

| Operation | Lock acquisition point | Lock class |
|---|---|---|
| Refund (`createRefund`) | `RefundMutationGuard.acquireAndCheck()` | `PESSIMISTIC_WRITE` on `payments` |
| Dispute open (`openDispute`) | `DisputeServiceImpl.openDispute()` step 1 | `PESSIMISTIC_WRITE` on `payments` |
| Dispute resolve (`resolveDispute`) | `DisputeServiceImpl.resolveDispute()` — Dispute row first, then Payment | `PESSIMISTIC_WRITE` on both `disputes` + `payments` |
| Dispute move-to-review | `DisputeServiceImpl.moveToUnderReview()` | `PESSIMISTIC_WRITE` on `disputes` |

**Lock ordering (`resolveDispute`):** Dispute row is locked *before* the Payment row. This ordering is consistent across all callers and prevents deadlocks when two resolution requests race.

### Dispute `resolveDispute` Race Fixed (Phase 9)

Before Phase 9, `resolveDispute` loaded the `Dispute` row without any lock, checked `ACTIVE_STATUSES` on the in-memory snapshot, then later acquired the Payment lock. Two concurrent resolves could both pass the status check before either committed:

```
T1: loadAndValidate(disputeId)  ← no lock, status=OPEN
T2: loadAndValidate(disputeId)  ← no lock, status=OPEN (stale snapshot)

T1: acquires Payment lock, posts accounting, sets status=WON, commits
T2: acquires Payment lock (T1 released), checks isResolutionPosted()
    → still FALSE on T2's stale in-memory Dispute object
T2: posts accounting AGAIN ← double-debit!
```

**Fix:** `loadAndValidateWithLock(merchantId, disputeId)` calls `disputeRepository.findByIdForUpdate(disputeId)` — issuing `SELECT … FOR UPDATE` on the `disputes` row. T2 now blocks until T1 commits. After T1 commits, T2 re-reads the fresh Dispute row (`status=WON`, `resolutionPosted=true`) and correctly fails on the `ACTIVE_STATUSES` check.

### Why DB Constraints Are Still Necessary

SELECT FOR UPDATE is an application-level protection. It is bypassed by:

- Direct SQL writes by DBAs (migrations, repair scripts)
- Application bugs that acquire the lock but forget to re-check capacity
- Connection pool exhaustion causing lock timeouts with silent fallback
- Future code paths that call `paymentRepository.save()` without first calling `findByIdForUpdate()`

The `chk_payment_capacity` CHECK constraint on the `payments` table provides **database-level enforcement** as the last line of defence:

```sql
CONSTRAINT chk_payment_capacity
    CHECK (captured_amount_minor >= refunded_amount_minor + disputed_amount_minor)
```

This constraint fires on every `INSERT` or `UPDATE` of the `payments` table, regardless of whether the application held a lock.

### Minor-Unit Integer Columns

The CHECK constraint operates on the `*_amount_minor` `BIGINT` columns rather than the `NUMERIC(18,4)` columns. Reasons:

1. **Integer arithmetic in SQL CHECK constraints is exact** — no precision surprises from decimal arithmetic in the constraint evaluator.
2. **BIGINT comparison is fast** — no decimal addition or scale normalisation.
3. **Explicit sync by `PaymentCapacityInvariantService`** — the service converts `BigDecimal → long` using `round(amount × 10_000)`, ensuring minor-unit columns always mirror the NUMERIC columns.

`PaymentCapacityInvariantService.syncMinorUnitFields(payment)` must be called **before every `paymentRepository.save(payment)`** that modifies `capturedAmount`, `refundedAmount`, or `disputedAmount`.

### Capacity Services

| Service | Responsibility |
|---|---|
| `RefundCapacityService` | Computes `refundable = captured − refunded − disputed`; throws `OVER_REFUND` (422) if exceeded |
| `DisputeCapacityService` | Computes `disputable = captured − refunded − disputed`; throws `DISPUTE_AMOUNT_EXCEEDS_LIMIT` (422) if exceeded |
| `PaymentCapacityInvariantService` | Asserts `net ≥ 0`; syncs minor-unit fields before every save |
| `RefundMutationGuard` | Combines `findByIdForUpdate` + `RefundCapacityService.check` into a single, re-usable guard component |

