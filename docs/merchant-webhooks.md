# Merchant Webhooks

FirstClub sends signed HTTP POST callbacks ("webhook events") to merchant-registered URLs
whenever key business events occur.  This document describes how to register endpoints,
verify request signatures, and interpret delivery status.

---

## Table of Contents

1. [How it works](#how-it-works)
2. [Registering an endpoint](#registering-an-endpoint)
3. [Verifying signatures](#verifying-signatures)
4. [Event types](#event-types)
5. [Payload shape](#payload-shape)
6. [Retry schedule & auto-disable](#retry-schedule--auto-disable)
7. [Delivery log](#delivery-log)
8. [API reference](#api-reference)

---

## How it works

```
Business event          Your HTTPS endpoint
─────────────────       ──────────────────────
Invoice paid        →   POST https://your-server.example.com/hooks
Subscription activated
Payment failed
Refund completed
Dispute opened
```

1. A qualifying business event occurs (e.g. a subscription activates).
2. FirstClub looks up **all active endpoints** registered by the merchant that subscribe
   to that event type (or the wildcard `"*"`).
3. For each matching endpoint, a **delivery record** is created (status = `PENDING`) and
   signed with that endpoint's HMAC-SHA256 secret.
4. A background scheduler attempts delivery every 60 seconds.
5. On success (HTTP 2xx) the delivery is marked `DELIVERED`.
6. On failure the delivery is retried with exponential back-off (see below).

---

## Registering an endpoint

### `POST /api/v2/merchants/{merchantId}/webhook-endpoints`

```json
{
  "url": "https://your-server.example.com/firstclub-webhooks",
  "secret": "optional-custom-secret-or-omit-to-auto-generate",
  "active": true,
  "subscribedEventsJson": "[\"invoice.paid\", \"payment.failed\"]"
}
```

| Field                  | Type    | Required | Description |
|------------------------|---------|----------|-------------|
| `url`                  | string  | ✓        | HTTPS (or HTTP) URL. Must start with `http://` or `https://`. |
| `secret`               | string  | –        | HMAC signing secret. If omitted, a 64-char hex secret is auto-generated. **Store this value — it is never returned again.** |
| `active`               | boolean | –        | Defaults to `true`. |
| `subscribedEventsJson` | string  | ✓        | JSON array of event type strings. Use `["*"]` to subscribe to all events. Must not be empty. |

**Response `201 Created`:**

```json
{
  "id": 42,
  "merchantId": 7,
  "url": "https://your-server.example.com/firstclub-webhooks",
  "active": true,
  "subscribedEventsJson": "[\"invoice.paid\", \"payment.failed\"]",
  "createdAt": "2025-01-15T10:30:00",
  "updatedAt": "2025-01-15T10:30:00"
}
```

> **Security note:** The `secret` field is intentionally absent from all API responses.
> If you did not provide a secret, retrieve it from the `201` response body — **it is
> not accessible again**.  If you lose the secret, update the endpoint with a new one
> via `PUT`.

---

## Verifying signatures

Every webhook request includes an `X-FirstClub-Signature` header:

```
X-FirstClub-Signature: sha256=<64 lowercase hex chars>
```

The signature is `HMAC-SHA256(rawRequestBody, secret)` encoded as hex.

### Verification example (Java)

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

boolean isValid(String rawBody, String secret, String signatureHeader) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes()));
    return expected.equals(signatureHeader);
}
```

### Verification example (Python)

```python
import hmac, hashlib

def is_valid(raw_body: bytes, secret: str, signature_header: str) -> bool:
    expected = "sha256=" + hmac.new(
        secret.encode(), raw_body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature_header)
```

> **Always use a constant-time comparison** (e.g. `MessageDigest.isEqual` in Java or
> `hmac.compare_digest` in Python) to avoid timing attacks.

---

## Event types

| Event type              | Triggered when |
|-------------------------|----------------|
| `invoice.paid`          | An invoice is marked as paid. |
| `invoice.overdue`       | An invoice passes its due date without payment. |
| `subscription.activated`| A subscription moves to `ACTIVE` status. |
| `subscription.cancelled`| A subscription is cancelled by the merchant or member. |
| `subscription.expired`  | A subscription reaches its end date. |
| `payment.failed`        | A payment attempt fails (card declined, network error, etc.). |
| `payment.succeeded`     | A payment is successfully captured. |
| `refund.completed`      | A refund is fully processed. |
| `dispute.opened`        | A chargeback or dispute is raised against a transaction. |
| `dispute.resolved`      | A dispute is resolved (won, lost, or reversed). |
| `*`                     | Wildcard — receive every event type. |

---

## Payload shape

Every event delivery POSTs a JSON body to your URL.  The exact structure depends on the
event type, but all events share a common envelope:

```json
{
  "eventType": "invoice.paid",
  "merchantId": 7,
  "occurredAt": "2025-01-15T10:30:00Z",
  "data": {
  }
}
```

The `data` object contains event-specific fields.  See the individual integration guides
for each event type for the full schema.

**Request headers** sent with every delivery:

| Header                    | Value |
|---------------------------|-------|
| `Content-Type`            | `application/json` |
| `X-FirstClub-Signature`   | `sha256=<hex>` |
| `X-FirstClub-Event`       | e.g. `invoice.paid` |
| `X-FirstClub-Delivery-Id` | UUID of the delivery record (useful for idempotency) |

---

## Retry schedule & auto-disable

If your endpoint returns a non-2xx response (or the connection times out), FirstClub
retries the delivery on the following schedule:

| Attempt | Delay after previous attempt |
|---------|------------------------------|
| 1 (initial) | Immediate |
| 2       | 1 minute |
| 3       | 5 minutes |
| 4       | 15 minutes |
| 5       | 1 hour |
| 6 (final)| 2 hours |

After **6 failed attempts** the delivery is marked `GAVE_UP`.

### Auto-disable

If an endpoint accumulates **5 or more** `GAVE_UP` deliveries, FirstClub automatically
sets `active = false` on the endpoint to stop routing new events to it.  You will need
to investigate the issue, fix your handler, and re-enable the endpoint via `PUT`.

---

## Delivery log

Every delivery attempt is recorded and available via the API.  Use the delivery log to:

- Diagnose integration issues (inspect the `lastError` and `lastResponseCode` fields).
- Build a support dashboard showing whether events reached your systems.
- Implement idempotency using the `id` (delivery ID).

### Statuses

| Status      | Meaning |
|-------------|---------|
| `PENDING`   | Queued; has not been attempted yet, or is waiting for the next retry. |
| `DELIVERED` | Your endpoint returned HTTP 2xx. |
| `FAILED`    | Last attempt failed; will be retried. |
| `GAVE_UP`   | All retry attempts exhausted; no further delivery will be attempted. |

---

## API reference

### Endpoint management

| Method   | Path                                                                    | Description |
|----------|-------------------------------------------------------------------------|-------------|
| `POST`   | `/api/v2/merchants/{merchantId}/webhook-endpoints`                      | Register a new endpoint. |
| `GET`    | `/api/v2/merchants/{merchantId}/webhook-endpoints`                      | List all endpoints (including inactive). |
| `PUT`    | `/api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}`         | Update URL, events, secret, or active status. |
| `DELETE` | `/api/v2/merchants/{merchantId}/webhook-endpoints/{endpointId}`         | Deactivate an endpoint (soft-delete). |

### Delivery log

| Method | Path                                                                     | Description |
|--------|--------------------------------------------------------------------------|-------------|
| `GET`  | `/api/v2/merchants/{merchantId}/webhook-deliveries`                      | List all delivery records for this merchant (newest first). |
| `GET`  | `/api/v2/merchants/{merchantId}/webhook-deliveries/{deliveryId}`         | Get a single delivery record with full detail. |

### Error responses

All errors follow the standard FirstClub error envelope:

```json
{
  "errorCode": "INVALID_WEBHOOK_URL",
  "message": "URL must start with http:// or https://",
  "status": 422
}
```

Common error codes for webhooks:

| Error code                      | HTTP status | Cause |
|---------------------------------|-------------|-------|
| `INVALID_WEBHOOK_URL`           | 422         | URL is blank or does not start with `http://` / `https://`. |
| `INVALID_SUBSCRIBED_EVENTS`     | 422         | `subscribedEventsJson` is not a valid JSON array or is empty. |
| `WEBHOOK_ENDPOINT_NOT_FOUND`    | 404         | Endpoint does not exist or does not belong to this merchant. |
| `WEBHOOK_DELIVERY_NOT_FOUND`    | 404         | Delivery does not exist or does not belong to this merchant. |
