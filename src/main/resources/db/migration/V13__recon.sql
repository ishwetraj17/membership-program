-- V13: Reconciliation + Settlement tables
-- Nightly settlement records (one per business day once settled)
CREATE TABLE settlements (
    id             BIGSERIAL PRIMARY KEY,
    settlement_date DATE         NOT NULL,
    total_amount   NUMERIC(14,2) NOT NULL,
    currency       VARCHAR(10)   NOT NULL DEFAULT 'INR',
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_settlement_date UNIQUE (settlement_date)
);

-- One reconciliation report per business day
CREATE TABLE recon_reports (
    id              BIGSERIAL PRIMARY KEY,
    report_date     DATE          NOT NULL,
    expected_total  NUMERIC(14,2) NOT NULL,
    actual_total    NUMERIC(14,2) NOT NULL,
    mismatch_count  INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_recon_report_date UNIQUE (report_date)
);

-- Individual mismatch rows linked to a report
CREATE TABLE recon_mismatches (
    id          BIGSERIAL PRIMARY KEY,
    report_id   BIGINT        NOT NULL REFERENCES recon_reports(id),
    type        VARCHAR(40)   NOT NULL,
    invoice_id  BIGINT,
    payment_id  BIGINT,
    details     TEXT,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recon_mismatches_report ON recon_mismatches (report_id);
CREATE INDEX idx_recon_mismatches_type   ON recon_mismatches (type);
