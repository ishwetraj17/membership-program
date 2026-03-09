# Tax Engine — India GST

Phase 9 of the FirstClub Billing Engine. Implements India's Goods and Services Tax (GST)
on billing invoices with intra-state (CGST + SGST) and inter-state (IGST) logic.

---

## Key Concepts

| Term | Meaning |
|---|---|
| CGST | Central GST — 9%, applies on intra-state supplies |
| SGST | State GST — 9%, applies on intra-state supplies (same state as CGST) |
| IGST | Integrated GST — 18%, applies on inter-state supplies |
| Intra-state | Merchant and customer are in the same state |
| Inter-state | Merchant and customer are in different states |
| Tax Profile | GST registration record for a merchant or customer |
| Tax Exempt | Customer flag that suppresses all GST line generation |

---

## GST Rate Table (FY 2024-25 standard rate)

| Type | Rate | Applicable |
|---|---|---|
| CGST | 9% | Intra-state only |
| SGST | 9% | Intra-state only |
| IGST | 18% | Inter-state only |

---

## Tax Calculation Policy

```
taxable_base = subtotal - discount_total   (post-discount, pre-credit)
```

1. If customer is **tax exempt** → no GST lines are generated.
2. If `merchant.legalStateCode == customer.stateCode` (case-insensitive) → **CGST + SGST**.
3. Otherwise → **IGST**.
4. Rounding: `HALF_UP` to 2 decimal places.
5. Credits are applied **after** tax calculation and do not reduce the tax base.

---

## Database Schema (V23)

```sql
-- Merchant GST registration
CREATE TABLE tax_profiles (
    id                      BIGSERIAL PRIMARY KEY,
    merchant_id             BIGINT NOT NULL UNIQUE REFERENCES merchant_accounts(id),
    gstin                   VARCHAR(32) NOT NULL,        -- 15-char GSTIN
    legal_state_code        VARCHAR(8) NOT NULL,          -- e.g. MH, KA, DL
    registered_business_name VARCHAR(255) NOT NULL,
    tax_mode                VARCHAR(16) NOT NULL,         -- B2B | B2C
    created_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Customer GST registration
CREATE TABLE customer_tax_profiles (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT NOT NULL UNIQUE REFERENCES customers(id),
    gstin        VARCHAR(32) NULL,                        -- optional for B2C customers
    state_code   VARCHAR(8) NOT NULL,
    entity_type  VARCHAR(16) NOT NULL,                    -- INDIVIDUAL | BUSINESS
    tax_exempt   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## Package Structure

```
com.firstclub.billing.tax/
├── controller/
│   ├── MerchantTaxProfileController.java
│   ├── CustomerTaxProfileController.java
│   └── InvoiceTaxController.java
├── dto/
│   ├── TaxProfileCreateOrUpdateRequestDTO.java
│   ├── TaxProfileResponseDTO.java
│   ├── CustomerTaxProfileCreateOrUpdateRequestDTO.java
│   ├── CustomerTaxProfileResponseDTO.java
│   ├── InvoiceTaxBreakdownDTO.java          ← includes nested TaxLineDTO
│   └── RecalculateTaxRequestDTO.java
├── entity/
│   ├── TaxProfile.java                      ← @Entity
│   ├── CustomerTaxProfile.java              ← @Entity
│   ├── TaxMode.java                         ← enum: B2B, B2C
│   └── CustomerEntityType.java             ← enum: INDIVIDUAL, BUSINESS
├── repository/
│   ├── TaxProfileRepository.java
│   └── CustomerTaxProfileRepository.java
└── service/
    ├── TaxProfileService.java               ← interface
    ├── TaxCalculationService.java           ← interface
    └── impl/
        ├── TaxProfileServiceImpl.java
        └── TaxCalculationServiceImpl.java
```

`InvoiceLineType` (existing enum) was extended with `CGST`, `SGST`, `IGST` values.  
`InvoiceTotalServiceImpl` was updated so `CGST | SGST | IGST` lines contribute to `taxTotal`.

---

## REST Endpoints

### Merchant Tax Profile

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v2/merchants/{merchantId}/tax-profile` | Create or update merchant GST profile |
| `PUT`  | `/api/v2/merchants/{merchantId}/tax-profile` | Update merchant GST profile (alias) |
| `GET`  | `/api/v2/merchants/{merchantId}/tax-profile` | Fetch merchant GST profile |

#### Sample Request Body

```json
{
  "gstin": "27AAAAA0000A1Z5",
  "legalStateCode": "MH",
  "registeredBusinessName": "First Club Pvt Ltd",
  "taxMode": "B2B"
}
```

---

### Customer Tax Profile

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v2/merchants/{merchantId}/customers/{customerId}/tax-profile` | Create or update customer profile |
| `PUT`  | `/api/v2/merchants/{merchantId}/customers/{customerId}/tax-profile` | Update customer profile |
| `GET`  | `/api/v2/merchants/{merchantId}/customers/{customerId}/tax-profile` | Fetch customer profile |

#### Sample Request Body (B2B, taxable)

```json
{
  "gstin": "29BBBBB1234B1Z5",
  "stateCode": "KA",
  "entityType": "BUSINESS",
  "taxExempt": false
}
```

#### Sample Request Body (B2C, exempt)

```json
{
  "stateCode": "MH",
  "entityType": "INDIVIDUAL",
  "taxExempt": true
}
```

---

### Invoice Tax

| Method | Path | Description |
|---|---|---|
| `GET`  | `/api/v2/merchants/{merchantId}/invoices/{invoiceId}/tax-breakdown?customerId={id}` | Read-only GST breakdown |
| `POST` | `/api/v2/merchants/{merchantId}/invoices/{invoiceId}/recalculate-tax` | Recompute and persist GST lines |

#### GET /tax-breakdown response

```json
{
  "invoiceId": 42,
  "invoiceNumber": "INV-000042",
  "merchantId": 1,
  "status": "OPEN",
  "currency": "INR",
  "subtotal": "1000.00",
  "discountTotal": "100.00",
  "taxableBase": "900.00",
  "cgst": "81.00",
  "sgst": "81.00",
  "igst": "0.00",
  "taxTotal": "162.00",
  "creditTotal": "0.00",
  "grandTotal": "1062.00",
  "intraState": true,
  "taxExempt": false,
  "calculatedAt": "2024-03-01T12:00:00",
  "taxLines": [
    { "lineId": null, "lineType": "CGST", "description": "CGST @ 9% on ₹900.00", "amount": "81.00" },
    { "lineId": null, "lineType": "SGST", "description": "SGST @ 9% on ₹900.00", "amount": "81.00" }
  ]
}
```

#### POST /recalculate-tax request body

```json
{ "customerId": 200 }
```

Returns: `InvoiceSummaryDTO` with updated totals after GST lines are persisted.

---

## GSTIN Validation

GSTIN must match: `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$`

- 2-digit state code + 10-char PAN format + 1 entity code + `Z` + 1 check digit
- Validated via `@Pattern` annotation on request DTOs

---

## Error Codes

| Code | HTTP Status | Scenario |
|---|---|---|
| `TAX_PROFILE_NOT_FOUND` | 404 | Merchant has no registered tax profile |
| `CUSTOMER_TAX_PROFILE_NOT_FOUND` | 422 | Customer has no registered tax profile |
| `INVOICE_NOT_FOUND` | 404 | Invoice not found for given merchant |
| `INVOICE_NOT_MODIFIABLE` | 422 | Attempted recalculation on PAID/VOID/UNCOLLECTIBLE invoice |

---

## Idempotency

- `POST /tax-profile` is an **upsert** — calling it multiple times on the same merchant/customer is safe.
- `POST /recalculate-tax` **deletes all existing CGST/SGST/IGST lines** before re-creating them. Calling it N times yields the same result.

---

## Test Coverage

| Suite | Type | Tests |
|---|---|---|
| `TaxCalculationServiceTest` | Unit (Mockito) | 8 tests — intra/inter/exempt/discount/rounding/blocked |
| `TaxProfileServiceTest` | Unit (Mockito) | 7 tests — CRUD for both profile types |
| `MerchantTaxProfileControllerTest` | Integration (Testcontainers PG) | 6 tests |
| `CustomerTaxProfileControllerTest` | Integration (Testcontainers PG) | 6 tests |
| `InvoiceTaxControllerTest` | Integration (Testcontainers PG) | 9 tests |
