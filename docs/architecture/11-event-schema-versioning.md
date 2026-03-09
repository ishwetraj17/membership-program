# 11 — Event Schema Versioning and Replay Safety

## Overview

This document describes how FirstClub handles the evolution of event payloads over time
(schema versioning) and how domain events can be safely replayed without causing duplicate
side effects.

---

## Concepts

### `schema_version` vs `event_version`

Both columns live on `domain_events` (and `outbox_events`), but they serve different purposes:

| Column           | Meaning                                                                  | Changes when                                        |
|------------------|--------------------------------------------------------------------------|-----------------------------------------------------|
| `event_version`  | Monotonically increasing **sequence number** for an event instance.      | Set once on write; used for causality ordering.     |
| `schema_version` | Version of the **JSON schema** used to encode the `payload` column.      | Incremented once when the payload shape changes.    |

**`event_version`** answers "was this event recorded before or after that one?"  
**`schema_version`** answers "which version of the payload structure should I use to parse this?"

Old events are never rewritten. Their `schema_version` stays at the value it had when they were
inserted. Migration happens **at read time** inside `PayloadMigrationRegistry`.

---

## Why Replay is Dangerous Without Idempotency

Replaying an event re-dispatches its business payload. Without idempotency guards, the same
transition can be applied twice:

| Scenario                              | Without idempotency                               | With `ReplaySafetyService`                          |
|---------------------------------------|---------------------------------------------------|-----------------------------------------------------|
| Replaying `REFUND_ISSUED`             | Refund sent to gateway twice — customer refunded double | **BLOCKED** policy; replay refused entirely    |
| Replaying `INVOICE_CREATED` twice     | Two invoices created for the same subscription    | **IDEMPOTENT_ONLY**; second replay blocked          |
| Replaying a replay event              | Infinite replay chain builds up                   | Loop detection: `replayed=true` source is blocked   |
| Replaying `SUBSCRIPTION_CREATED`      | Subscription created again in projection           | **ALLOW** policy; safe because handlers are idempotent |

The `ReplayPolicy` enum encodes the safety contract per event type. New event types that
introduce external side effects (payment captures, notification delivery, etc.) **must** be
registered as `BLOCKED` or `IDEMPOTENT_ONLY`.

---

## Schema: V59 Changes to `domain_events`

```sql
ALTER TABLE domain_events
    ADD COLUMN replayed          BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN replayed_at       TIMESTAMP NULL,
    ADD COLUMN original_event_id BIGINT    NULL;

CREATE INDEX idx_domain_events_original_event
    ON domain_events(original_event_id)
    WHERE original_event_id IS NOT NULL;

CREATE INDEX idx_domain_events_replayed_at
    ON domain_events(replayed_at)
    WHERE replayed = TRUE;
```

| Column              | On original events | On replay events                        |
|---------------------|--------------------|-----------------------------------------|
| `replayed`          | `false`            | `true`                                  |
| `replayed_at`       | `null`             | timestamp when the replay was initiated |
| `original_event_id` | `null`             | FK → the event that was replayed        |

The original event is **never modified**. A replay appends a new row to the append-only log.

---

## Class Inventory

### `com.firstclub.events.schema`

| Class                    | Role                                                                         |
|--------------------------|------------------------------------------------------------------------------|
| `EventSchemaVersion`     | Record: `(eventType, currentVersion)` — source of truth for "what is current"|
| `PayloadMigrator`        | Interface: one version-step transform (`fromVersion → toVersion`)            |
| `PayloadMigrationRegistry` | Spring component: collects all `PayloadMigrator` beans, chains them at runtime |
| `DomainEventEnvelope`    | Record wrapper: original event + migrated payload + migration flags          |

### `com.firstclub.events.replay`

| Class                  | Role                                                                           |
|------------------------|--------------------------------------------------------------------------------|
| `ReplayPolicy`         | Enum: `ALLOW`, `IDEMPOTENT_ONLY`, `BLOCKED`                                   |
| `ReplaySafetyService`  | Pre-flight checks: loop detection, policy enforcement, idempotency guard       |
| `EventReplayService`   | Orchestrates load → safety check → migrate → append replay event               |
| `ReplayRangeRequest`   | Request DTO for range replay: `from`, `to`, optional `eventType`, `merchantId` |
| `ReplayResult`         | Response record: `originalEventId`, `replayEventId`, `replayed`, `skipReason`  |

### `com.firstclub.outbox.schema`

| Class                          | Role                                                                        |
|--------------------------------|-----------------------------------------------------------------------------|
| `SchemaAwareOutboxEventHandler` | Abstract base: runs migration before calling `handleMigrated()`            |

---

## Migration Strategy for Payload Evolution

When the payload schema of an event type must change (e.g. adding a required field,
renaming a field, or changing a data type):

1. **Bump `schema_version`** for the event type — update the producer to write `schema_version = N`.
2. **Register a `PayloadMigrator`** bean for `(eventType, fromVersion=N-1, toVersion=N)`.
3. **Test**: verify the migrator transforms a real stored sample (unit test with actual JSON).
4. **Deploy** producers and consumers in the same release — old consumers use the old version,
   new consumers auto-migrate stale payloads.

### Example: adding a `currency` field to `INVOICE_CREATED`

```java
@Component
public class InvoiceCreatedV1ToV2Migrator implements PayloadMigrator {

    private final ObjectMapper mapper;

    @Override public String eventType()   { return "INVOICE_CREATED"; }
    @Override public int    fromVersion() { return 1; }
    @Override public int    toVersion()   { return 2; }

    @Override
    public String migrate(String payload) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(payload);
            if (!node.has("currency")) node.put("currency", "INR");
            return mapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to migrate INVOICE_CREATED payload", e);
        }
    }
}
```

`PayloadMigrationRegistry` discovers this bean automatically at startup.

---

## Replay API Reference

### `POST /api/v2/admin/events/{id}/replay`

Replays a single domain event by primary key.

```bash
curl -X POST https://api.firstclub.com/api/v2/admin/events/42/replay \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response:**
```json
{
  "originalEventId": 42,
  "replayEventId": 1337,
  "eventType": "SUBSCRIPTION_CREATED",
  "replayed": true,
  "wasMigrated": false,
  "skipReason": null
}
```

When blocked:
```json
{
  "originalEventId": 77,
  "replayEventId": null,
  "eventType": "REFUND_ISSUED",
  "replayed": false,
  "wasMigrated": false,
  "skipReason": "Event type 'REFUND_ISSUED' has a BLOCKED replay policy"
}
```

---

### `POST /api/v2/admin/events/replay-range`

Replays all events in a time window, with optional filters.

```bash
curl -X POST https://api.firstclub.com/api/v2/admin/events/replay-range \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "2026-03-01T00:00:00",
    "to":   "2026-03-09T23:59:59",
    "eventType": "SUBSCRIPTION_CREATED",
    "merchantId": 101
  }'
```

Returns one `ReplayResult` per matched event. Blocked or skipped events are included with
`replayed=false` — they do not abort the range.

---

## Replay Policy Reference

| Event Type               | Policy            | Reason                                                  |
|--------------------------|-------------------|---------------------------------------------------------|
| `REFUND_ISSUED`          | `BLOCKED`         | Irreversible financial transaction to payment gateway   |
| `PAYMENT_SUCCEEDED`      | `IDEMPOTENT_ONLY` | Revenue recognition must not run twice                  |
| `INVOICE_CREATED`        | `IDEMPOTENT_ONLY` | Only one invoice per billing cycle                      |
| `PAYMENT_INTENT_CREATED` | `IDEMPOTENT_ONLY` | Intent lifecycle must not fork                          |
| `SUBSCRIPTION_ACTIVATED` | `ALLOW`           | Activation projection is idempotent                     |
| `SUBSCRIPTION_CREATED`   | `ALLOW`           | Read-model rebuild only                                 |
| `SUBSCRIPTION_CANCELLED` | `ALLOW`           | Cancellation projection is idempotent                   |
| `PAYMENT_ATTEMPT_FAILED` | `ALLOW`           | Failure projection has no external side effects         |
| *(unregistered)*         | `ALLOW` (default) | Unknown types are permissive; register explicitly to restrict |

---

## Adding a New Event Type

1. Add to `DomainEventTypes` / `OutboxEventType` enum if outbox-based.
2. Decide the replay policy and add to `ReplaySafetyService.POLICY_MAP`.
3. If the payload schema will evolve, implement `PayloadMigrator` beans now and register in `PayloadMigrationRegistry`.
4. Extend `SchemaAwareOutboxEventHandler` to support future schema changes transparently.
5. Add the event type to `Phase13SchemaVersioningTests` coverage.

---

## SLA Thresholds

| Signal                              | Warning        | Critical            |
|-------------------------------------|----------------|---------------------|
| Migration step missing              | Build error    | Startup fails       |
| Duplicate `PayloadMigrator` key     | Build error    | Startup fails       |
| Replay of a `BLOCKED` event type    | HTTP 200 (skipped) | —               |
| Replay chain depth > 1              | HTTP 200 (skipped) | —               |

Startup failures for duplicate / missing migrators are intentional — they prevent silent data
corruption at the cost of a fast fail.
