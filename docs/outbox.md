# Transactional Outbox Pattern

Reliable at-least-once domain event delivery without a message broker.

---

## Why the Outbox Pattern?

A common reliability hazard in microservices is the **dual-write problem**:

```
BEGIN TRANSACTION
  UPDATE subscriptions SET status = 'ACTIVE'  -- succeeds
COMMIT

kafka.send("SUBSCRIPTION_ACTIVATED")           -- crashes here → event lost
```

The business change and the event notification are in *two separate systems*
that cannot participate in the same ACID transaction.  If the process crashes
between the commit and the send, the downstream system never learns about the
state change.

The **transactional outbox** eliminates this by adding the event *inside* the
same DB transaction as the business change:

```
BEGIN TRANSACTION
  UPDATE subscriptions SET status = 'ACTIVE'
  INSERT INTO outbox_events (event_type, payload, status) VALUES ('SUBSCRIPTION_ACTIVATED', '...', 'NEW')
COMMIT                                         -- both committed atomically

[later] OutboxPoller reads NEW rows and dispatches handlers
```

If the process crashes before the poller runs, the row is still in the DB.
The poller will pick it up on the next tick.

---

## Architecture

```
                        ┌─────────────────────────────┐
                        │  Business Service            │
                        │  @Transactional              │
                        │                              │
  HTTP / Scheduler ────►│  1. do business logic        │
                        │  2. outboxService.publish()  │──► outbox_events (same TX)
                        └─────────────────────────────┘

                        ┌─────────────────────────────┐
                        │  OutboxPoller                │
  @Scheduled ──────────►│  every 30 sec               │
                        │  1. lockDueEvents(50)        │──► mark PROCESSING (TX1)
                        │  2. for each id:             │
                        │     processSingleEvent(id)   │──► dispatch handler  (TX2 REQUIRES_NEW)
                        └─────────────────────────────┘

                        ┌─────────────────────────────┐
                        │  OutboxEventHandler          │
                        │  (per event type)            │
                        │                              │
  SUCCESS ──────────────│  handle(event)               │──► outbox_events status=DONE
  FAILURE retry ────────│  throws Exception            │──► status=NEW, attempts++, backoff
  FAILURE terminal ─────│  attempts >= MAX_ATTEMPTS    │──► status=FAILED + dead_letter_messages
                        └─────────────────────────────┘
```

---

## Database Schema

### `outbox_events`

| Column           | Type        | Default | Description                                   |
|------------------|-------------|---------|-----------------------------------------------|
| `id`             | BIGSERIAL   | —       | Primary key                                   |
| `event_type`     | VARCHAR(64) | —       | Constant from `DomainEventTypes`              |
| `payload`        | TEXT        | —       | JSON payload                                  |
| `status`         | VARCHAR(16) | `NEW`   | `NEW` / `PROCESSING` / `DONE` / `FAILED`      |
| `attempts`       | INT         | `0`     | Delivery attempts so far                      |
| `next_attempt_at`| TIMESTAMP   | `NOW()` | Earliest time to retry                        |
| `created_at`     | TIMESTAMP   | `NOW()` | Insert timestamp                              |
| `last_error`     | TEXT        | `NULL`  | Last exception message                        |

Status flow:

```
NEW ──► PROCESSING ──► DONE
                   └──► NEW      (retry, attempts++)
                   └──► FAILED   (attempts >= 5, DLQ written)
```

---

## Event Types & Payloads

| Constant                        | Published by              | Payload fields                                              |
|---------------------------------|---------------------------|-------------------------------------------------------------|
| `INVOICE_CREATED`               | `InvoiceService`          | `invoiceId`, `userId`, `subscriptionId`, `totalAmount`, `currency` |
| `PAYMENT_SUCCEEDED`             | `InvoiceService`          | `invoiceId`, `subscriptionId`, `amount`, `currency`         |
| `SUBSCRIPTION_ACTIVATED`        | `InvoiceService`          | `subscriptionId`, `userId`                                  |
| `REFUND_ISSUED`                 | `RefundService`           | `refundId`, `paymentId`, `amount`, `currency`               |

---

## Retry & Back-off Schedule

When a handler throws, the event is rescheduled with exponential back-off:

| Attempt | Delay before retry |
|---------|--------------------|
| 1       | 5 minutes          |
| 2       | 15 minutes         |
| 3       | 30 minutes         |
| 4       | 60 minutes         |
| 5       | **Terminal** — status → `FAILED`, row written to `dead_letter_messages` with `source = 'OUTBOX'` |

Configured in `OutboxService.MAX_ATTEMPTS = 5` and `BACKOFF_MINUTES`.

---

## Dead-Letter Queue

Permanently failed events are copied to `dead_letter_messages`
(already used by the webhook processor):

```sql
source  = 'OUTBOX'
payload = '<event_type>|<json_payload>'
error   = <last exception message>
```

Monitor this table to detect systematic handler failures.

---

## Handler Idempotency

All handlers implement **at-least-once** idempotency: the same event may be
delivered more than once (after a retry or a crash-recovery).  Each handler
verifies the current DB state before acting:

| Handler                       | Idempotency check                                   |
|-------------------------------|-----------------------------------------------------|
| `InvoiceCreatedHandler`       | Verifies invoice exists in the DB                   |
| `PaymentSucceededHandler`     | Verifies invoice status == `PAID`                   |
| `SubscriptionActivatedHandler`| Verifies subscription status == `ACTIVE`            |
| `RefundIssuedHandler`         | Verifies refund exists in the DB                    |

If the expected state is not found (transient inconsistency), the handler
throws, triggering a retry after back-off.

---

## Configuration

| Setting                   | Value    | Notes                         |
|---------------------------|----------|-------------------------------|
| Poller fixed rate         | 30 s     | `OutboxPoller.@Scheduled`     |
| Poller initial delay      | 10 s     | Lets context warm up          |
| Batch size                | 50 events| `OutboxPoller.BATCH_SIZE`     |
| Max delivery attempts     | 5        | `OutboxService.MAX_ATTEMPTS`  |

---

## Publishing Events

Call `OutboxService.publish()` **inside** a `@Transactional` method:

```java
@Transactional
public InvoiceDTO createInvoice(...) {
    Invoice invoice = invoiceRepository.save(...);

    // Same DB transaction — atomically consistent
    outboxService.publish(DomainEventTypes.INVOICE_CREATED, Map.of(
        "invoiceId", invoice.getId(),
        "userId",    invoice.getUserId()
    ));

    return toDto(invoice);
}
```

`OutboxService.publish()` uses `propagation = REQUIRED`, so it always joins
the caller's existing transaction.

---

## Adding a New Event Handler

1. Add a constant to `DomainEventTypes`:
   ```java
   public static final String MY_EVENT = "MY_EVENT";
   ```
2. Implement `OutboxEventHandler`:
   ```java
   @Component
   @RequiredArgsConstructor
   public class MyEventHandler implements OutboxEventHandler {
       @Override public String getEventType() { return DomainEventTypes.MY_EVENT; }
       @Override public void handle(OutboxEvent event) throws Exception {
           // parse payload, check DB state, do side effect
       }
   }
   ```
3. Publish the event from the appropriate service using `outboxService.publish()`.

`OutboxEventHandlerRegistry` auto-discovers all `OutboxEventHandler` beans on
startup.  Duplicate registrations for the same event type cause an
`IllegalStateException` at startup.

---

## Design Notes

- **`PROCESSING` state**: events are marked PROCESSING before dispatch to
  prevent a second pod (or scheduler tick) from re-locking the same row (via
  `FOR UPDATE SKIP LOCKED`).  If a pod crashes while a row is PROCESSING, it
  will remain stuck in that state.  A future "orphan recovery" job can reset
  rows in PROCESSING state older than a configurable timeout back to NEW.
- **Ledger bypass**: the dunning path (`RenewalService`, `DunningService`)
  calls `InvoiceService.onPaymentSucceeded()` directly, which now publishes
  `PAYMENT_SUCCEEDED` and `SUBSCRIPTION_ACTIVATED` outbox events.  Ledger
  entries are still written directly within `WebhookProcessingService`; the
  outbox handlers are additive and do not duplicate ledger work.
- **No message broker required**: the outbox delivers events in-process.
  To bridge to Kafka or SNS, replace handler implementations — the publisher
  contract does not change.
