# Ledger Model

## Purpose

The ledger provides an **immutable double-entry accounting journal** for all financial events on the platform. Every payment, refund, reversal, revenue recognition, and settlement is recorded as a balanced journal entry. The ledger is the authoritative financial record and the source of truth for all reporting.

---

## Double-Entry Accounting Basics

Every financial event creates a **journal entry** composed of at least two **lines** (legs):
- One or more **DEBIT** lines
- One or more **CREDIT** lines
- **Invariant: sum(DEBIT amounts) == sum(CREDIT amounts)**

This balance constraint is enforced in `LedgerServiceImpl.postEntry()` before any persistence — it throws `LedgerException.unbalanced()` if the constraint is violated.

---

## Chart of Accounts

The Chart of Accounts (COA) is seeded in Flyway migration `V8`. Every ledger line references one of these accounts.

| Account Code | Type | Meaning |
|---|---|---|
| `RECEIVABLE` | ASSET | Amounts owed by customers (invoiced but not yet paid) |
| `CASH` | ASSET | Settled funds received into the system |
| `DISPUTE_RESERVE` | ASSET | Funds held pending dispute outcome |
| `REVENUE_SUBSCRIPTIONS` | INCOME | Earned subscription revenue |
| `SUBSCRIPTION_LIABILITY` | LIABILITY | Deferred revenue (collected but not yet earned) |
| `REFUND_EXPENSE` | EXPENSE | Cost of refunds issued |
| `CHARGEBACK_EXPENSE` | EXPENSE | Cost of lost chargebacks |
| `SETTLEMENT` | LIABILITY | Funds held by the payment gateway awaiting disbursement |

**Normal balance direction by account type:**
- ASSET accounts: increased by DEBIT, decreased by CREDIT
- LIABILITY accounts: increased by CREDIT, decreased by DEBIT
- INCOME accounts: increased by CREDIT, decreased by DEBIT
- EXPENSE accounts: increased by DEBIT, decreased by CREDIT

---

## Entity Structure

### `LedgerAccount`
```
id           UUID
code         VARCHAR UNIQUE   -- e.g. 'CASH', 'RECEIVABLE'
name         VARCHAR
type         ENUM(ASSET, LIABILITY, INCOME, EXPENSE)
description  VARCHAR
created_at   TIMESTAMPTZ
```

### `LedgerEntry` (Journal Header)
```
id               UUID
entry_type       ENUM         -- PAYMENT_CAPTURED, REFUND_ISSUED, REVENUE_RECOGNIZED, SETTLEMENT, DISPUTE_OPENED, etc.
reference_type   ENUM         -- INVOICE, PAYMENT, REFUND, SUBSCRIPTION, SETTLEMENT_BATCH, etc.
reference_id     VARCHAR      -- FK to the triggering entity
currency         VARCHAR(3)   -- 'INR'
memo             VARCHAR
merchant_id      UUID
business_fingerprint  VARCHAR UNIQUE  -- prevents duplicate journal entries
created_at       TIMESTAMPTZ
```

### `LedgerLine` (Individual Leg)
```
id         UUID
entry_id   UUID FK → ledger_entries
account_id UUID FK → ledger_accounts
side       ENUM(DEBIT, CREDIT)
amount     NUMERIC(19,4)
merchant_id UUID
created_at TIMESTAMPTZ
```

---

## Standard Journal Entries

For every event type, the system posts a fixed journal template:

| Business Event | Entry Type | Debit Account | Credit Account | Amount |
|---|---|---|---|---|
| Invoice issued (subscription activated) | `PAYMENT_CAPTURED` (pre-payment leg) | `RECEIVABLE` | `SUBSCRIPTION_LIABILITY` | `invoice.grand_total` |
| Payment successfully captured | `PAYMENT_CAPTURED` | `CASH` | `RECEIVABLE` | `payment.captured_amount` |
| Refund processed | `REFUND_ISSUED` | `REFUND_EXPENSE` | `CASH` | `refund.amount` |
| Revenue recognized (daily) | `REVENUE_RECOGNIZED` | `SUBSCRIPTION_LIABILITY` | `REVENUE_SUBSCRIPTIONS` | `schedule.amount` |
| Settlement received from gateway | `SETTLEMENT` | `CASH` | `SETTLEMENT` | `settlement_batch.gross_amount` |
| Dispute opened by customer | `DISPUTE_OPENED` | `DISPUTE_RESERVE` | `CASH` | `dispute.amount` |
| Dispute won (merchant resolves) | `DISPUTE_WON` | `CASH` | `DISPUTE_RESERVE` | `dispute.amount` |
| Chargeback (dispute lost) | `CHARGEBACK_POSTED` | `CHARGEBACK_EXPENSE` | `DISPUTE_RESERVE` | `dispute.amount` |

---

## Worked Example: Subscription Payment Lifecycle

Assume a Gold plan subscription at ₹499.

### Step 1: Invoice Issued

```
DR  RECEIVABLE            ₹499.00
CR  SUBSCRIPTION_LIABILITY         ₹499.00
Memo: Invoice INV-2025-0001 issued
```

**What it means:** The customer owes us ₹499. We have collected it as deferred revenue (liability) because the service has not yet been delivered.

### Step 2: Payment Captured

```
DR  CASH                  ₹499.00
CR  RECEIVABLE                     ₹499.00
Memo: Payment captured for INV-2025-0001
```

**What it means:** Cash has arrived. The receivable is cleared.

### Step 3: Daily Revenue Recognition (over 30 days at ₹16.63/day)

```
(Each day, nightly):
DR  SUBSCRIPTION_LIABILITY  ₹16.63
CR  REVENUE_SUBSCRIPTIONS           ₹16.63
Memo: Revenue recognition for day 1/30
```

**What it means:** Each day of service delivered converts deferred revenue into earned revenue.

### Result After 30 days

```
CASH                    +₹499.00 (one credit cleared)
REVENUE_SUBSCRIPTIONS   +₹498.90 (30 × ₹16.63 = 499.10, rounding may differ by ₹0.10)
SUBSCRIPTION_LIABILITY  0 (fully recognized)
RECEIVABLE              0 (fully paid)
```

---

## Deferred Revenue Model

When a subscription is created:
1. Customer pays ₹499 for a 30-day Gold plan
2. **Day 0:** Full ₹499 sits in `SUBSCRIPTION_LIABILITY`
3. **Days 1–30:** `RevenueRecognitionPostingServiceImpl` posts daily amortization entries
4. **Day 30:** `SUBSCRIPTION_LIABILITY` balance = ₹0; `REVENUE_SUBSCRIPTIONS` balance = ₹499

This pattern satisfies **ASC 606** and **IFRS 15**: revenue flows to P&L only as performance obligations are fulfilled.

---

## Immutability & Corrections

**The ledger is append-only.** There is no UPDATE or DELETE operation on `ledger_entries` or `ledger_lines` anywhere in the codebase.

**To correct an error:** Post a reversal entry — the equal and opposite of the original entry — then post a new correct entry.

```
Original:  DR CASH ₹499 / CR RECEIVABLE ₹499  (incorrect)
Reversal:  DR RECEIVABLE ₹499 / CR CASH ₹499  (undoes original)
Correct:   DR CASH ₹500 / CR RECEIVABLE ₹500  (correct)
```

---

## Invariants

1. **Every entry balanced:** `sum(DEBIT lines) == sum(CREDIT lines)` — enforced before persistence
2. **No settlement overdraw:** SETTLEMENT account balance must not go negative
3. **No revenue over-recognition:** `REVENUE_SUBSCRIPTIONS` credits must not exceed recognizable invoice amounts
4. **No duplicate journal:** `business_fingerprint` UNIQUE constraint on `ledger_entries`
5. **Dispute reserve non-negative:** `DISPUTE_RESERVE` account must not go negative

---

## Reporting Queries

### Trial Balance (as of a date)
```sql
SELECT la.code, la.type,
       SUM(CASE WHEN ll.side='DEBIT' THEN ll.amount ELSE 0 END) AS debits,
       SUM(CASE WHEN ll.side='CREDIT' THEN ll.amount ELSE 0 END) AS credits
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE le.created_at <= :asOf
  AND le.merchant_id = :merchantId
GROUP BY la.code, la.type
ORDER BY la.type, la.code;
```

### Account Balance for a Period
```sql
SELECT
  SUM(CASE WHEN ll.side='DEBIT' THEN ll.amount ELSE -ll.amount END) AS net_balance
FROM ledger_lines ll
JOIN ledger_entries le ON ll.entry_id = le.id
JOIN ledger_accounts la ON ll.account_id = la.id
WHERE la.code = :accountCode
  AND le.created_at BETWEEN :fromDate AND :toDate
  AND le.merchant_id = :merchantId;
```

---

## Known Gaps

| Gap | Impact |
|---|---|
| No multi-currency support | All amounts assumed INR; FX translation not implemented |
| No period lock/close | Entries can be posted to any historical date in principle |
| Trial balance is from system inception | No opening balance migration from a prior system |
| No tax account in COA | GST collected from customers is tracked as invoice lines but not separately in the ledger |

---

## Phase 10 — Ledger Immutability and Reversal-Entry Correction Flow

### Why Financial Ledgers Must Be Immutable

A general ledger is the authoritative record of all financial events. Once an entry is posted it must never be altered because:

1. **Audit trail** — Regulators and auditors require a complete, tamper-proof history. Mutating a row destroys the historical record.
2. **Reconciliation integrity** — Bank reconciliations and payment-gateway reconciliations work by matching against a snapshot of the ledger at a point in time. If that snapshot can change, the reconciliation is meaningless.
3. **Double-entry consistency** — Every posted entry satisfies `ΣDebits = ΣCredits`. Allowing in-place edits risks breaking this invariant without anyone noticing.
4. **GDPR / compliance** — Financial records are subject to minimum retention requirements; deletion is disallowed.

### Reversal over Mutation

Instead of editing or deleting an incorrect entry the system generates a **reversal entry** — a new journal entry that mirrors all original lines with flipped `DEBIT ↔ CREDIT` directions and identical amounts.

```
Original (PAYMENT_CAPTURED):            Reversal (REVERSAL):
  DR  PG_CLEARING       ₹ 300.00          CR  PG_CLEARING       ₹ 300.00
  CR  SUBSCRIPTION_LIABILITY ₹ 300.00     DR  SUBSCRIPTION_LIABILITY ₹ 300.00
```

After posting the reversal every account's running balance returns to the pre-original position. If the correction itself is incorrect, the **reversal entry** (not the original) can be reversed in turn.

### Two-Layer Immutability Protection

| Layer | Mechanism | What it catches |
|---|---|---|
| JPA / Hibernate | `@Column(updatable = false)` on every column in `LedgerEntry` and `LedgerLine` | Hibernate queries that would emit `UPDATE` SQL — rejected at the ORM level, zero SQL sent to DB |
| PostgreSQL triggers | `trg_ledger_entries_immutable` and `trg_ledger_lines_immutable` — BEFORE UPDATE OR DELETE | Any `UPDATE` or `DELETE` reaching the database from any source (ad-hoc SQL, migrations, bulk tools) |

Both layers are required. The JPA layer prevents the application from accidentally issuing updates; the DB trigger is the last-resort safety net that cannot be bypassed regardless of the caller.

#### Trigger function

```sql
CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        RAISE EXCEPTION
            'Ledger records are immutable: % is not allowed on table "%" (id=%). Correct via reversal entry.',
            TG_OP, TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'raise_exception';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
```

Triggers `trg_ledger_entries_immutable` and `trg_ledger_lines_immutable` invoke this function BEFORE UPDATE OR DELETE on their respective tables.

### New Columns Added in Phase 10 (V56 Migration)

| Column | Table | Purpose |
|---|---|---|
| `reversal_of_entry_id` | `ledger_entries` | FK to the original entry being corrected; `NULL` for non-reversal entries |
| `posted_by_user_id` | `ledger_entries` | ID of the admin who triggered the posting; enables user-level audit |
| `reversal_reason` | `ledger_entries` | Mandatory human-readable reason for the reversal (stored as TEXT) |

### Reversal API

```
POST /api/v1/admin/ledger/entries/{id}/reverse
Authorization: Bearer <admin-token>

{
  "reason": "Payment gateway charged wrong amount — reverting and reposting",
  "postedByUserId": 42
}
```

**Rules enforced:**

| Condition | HTTP status | Error code |
|---|---|---|
| `reason` blank or null | 422 | `REVERSAL_REASON_REQUIRED` |
| Target entry is itself a REVERSAL | 422 | `CANNOT_REVERSE_REVERSAL` |
| A reversal already exists for this entry | 409 | `REVERSAL_ALREADY_EXISTS` |
| Target entry not found | 404 | `LEDGER_ENTRY_NOT_FOUND` |

### Duplicate Reversal Policy

Each entry may be reversed **at most once**. If the reversal itself was incorrect, the correction workflow is:

1. Reverse the **reversal entry** (it is a plain `REVERSAL`-typed entry with its own id).
2. Then post the correct original entry.

This deliberately limits the reversal chain, keeping the audit trail short and comprehensible.

### Helper Classes (all in `com.firstclub.ledger`)

| Class | Responsibility |
|---|---|
| `ImmutableLedgerGuard` | App-layer guard — throws `LEDGER_IMMUTABLE` 409 if any entity already has an id (i.e., is persisted) |
| `LedgerPostingPolicy` | Validates line balance (DR = CR) and all reversal business rules |
| `LedgerEntryFactory` | Builder helpers; `buildReversalLines()` flips DEBIT ↔ CREDIT; `buildReversalEntry()` constructs the reversal skeleton |
| `LedgerReversalServiceImpl` | Orchestrates the 6-step reversal algorithm end-to-end |
