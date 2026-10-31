-- Applied at start-up (spring.sql.init) when the Postgres store is used. Idempotent.

CREATE TABLE IF NOT EXISTS employee (
    tenant_id          VARCHAR(64)  NOT NULL,
    employee_id        VARCHAR(32)  NOT NULL,
    status             VARCHAR(10)  NOT NULL,
    name               VARCHAR(100) NOT NULL,
    email              VARCHAR(254) NOT NULL,
    phone              VARCHAR(16),
    manager_id         VARCHAR(32),
    office_code        VARCHAR(16)  NOT NULL,
    shift_code         VARCHAR(16)  NOT NULL,
    hr_version         BIGINT       NOT NULL,
    hr_source_time     TIMESTAMPTZ  NOT NULL,
    hr_hash            BIGINT       NOT NULL,
    pickup_point       VARCHAR(200),
    special_assistance TEXT         NOT NULL DEFAULT '[]',
    transport_version  BIGINT       NOT NULL DEFAULT 0,
    bucket             INT          NOT NULL,
    digest             BIGINT       NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (tenant_id, employee_id)
);
CREATE INDEX IF NOT EXISTS employee_bucket ON employee (tenant_id, bucket);

-- Transactional outbox: written in the same transaction as the employee row.
CREATE TABLE IF NOT EXISTS outbox (
    seq               BIGSERIAL    PRIMARY KEY,
    event_id          VARCHAR(36)  NOT NULL UNIQUE,
    tenant_id         VARCHAR(64)  NOT NULL,
    employee_id       VARCHAR(32)  NOT NULL,
    change_type       VARCHAR(20)  NOT NULL,
    changed_fields    TEXT         NOT NULL,
    hr_version        BIGINT       NOT NULL,
    transport_version BIGINT       NOT NULL,
    source            VARCHAR(20)  NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    published_at      TIMESTAMPTZ,
    attempts          INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_tenant_seq ON outbox (tenant_id, seq);

CREATE TABLE IF NOT EXISTS catalog (
    tenant_id VARCHAR(64) PRIMARY KEY,
    offices   TEXT        NOT NULL,
    shifts    TEXT        NOT NULL
);

CREATE TABLE IF NOT EXISTS route (
    tenant_id   VARCHAR(64) NOT NULL,
    route_id    VARCHAR(32) NOT NULL,
    capacity    INT         NOT NULL,
    seats_taken INT         NOT NULL,
    PRIMARY KEY (tenant_id, route_id)
);

CREATE TABLE IF NOT EXISTS booking (
    tenant_id   VARCHAR(64) NOT NULL,
    booking_id  VARCHAR(36) NOT NULL,
    employee_id VARCHAR(32) NOT NULL,
    route_id    VARCHAR(32) NOT NULL,
    pickup_at   TIMESTAMPTZ NOT NULL,
    status      VARCHAR(10) NOT NULL,
    PRIMARY KEY (tenant_id, booking_id)
);
CREATE INDEX IF NOT EXISTS booking_employee ON booking (tenant_id, employee_id);

-- Idempotent consumers remember what they processed, in the same transaction as their effects.
CREATE TABLE IF NOT EXISTS processed_event (
    consumer VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE IF NOT EXISTS reconciliation_run (
    tenant_id  VARCHAR(64) NOT NULL,
    run_id     VARCHAR(36) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    body       TEXT        NOT NULL,
    PRIMARY KEY (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS reconciliation_run_status ON reconciliation_run (status);
