-- LedgerGuard core ledger schema.
-- The ledger (journal_entries) is the authoritative financial record.
-- account_balances is a projection updated in the same database transaction as posting.

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------
CREATE SEQUENCE account_number_seq START WITH 10000001;

CREATE TABLE accounts (
    id              UUID         PRIMARY KEY,
    account_number  VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    account_type    VARCHAR(20)  NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    normal_balance  VARCHAR(6)   NOT NULL CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    currency        CHAR(3)      NOT NULL CHECK (currency IN ('USD')),
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    allow_negative  BOOLEAN      NOT NULL DEFAULT FALSE,
    system_account  BOOLEAN      NOT NULL DEFAULT FALSE,
    simulation      BOOLEAN      NOT NULL DEFAULT FALSE,
    owner_reference VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Normal balance follows from the account type; it is stored so queries can use it directly.
    CONSTRAINT ck_accounts_normal_balance CHECK (
        (account_type IN ('ASSET', 'EXPENSE') AND normal_balance = 'DEBIT')
        OR (account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND normal_balance = 'CREDIT')
    ),
    -- Lets journal_entries reference (account, currency) so an entry's currency must match its account.
    CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency)
);

-- Balance projection, expressed in the account's normal-balance direction
-- (a positive balance on a LIABILITY account means the institution owes the customer money).
CREATE TABLE account_balances (
    account_id     UUID          PRIMARY KEY REFERENCES accounts (id),
    balance        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    allow_negative BOOLEAN       NOT NULL,
    entry_count    BIGINT        NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Database-level backstop: even if application logic were wrong, a commit that
    -- leaves an account unauthorized-negative is rejected.
    CONSTRAINT ck_balances_no_unauthorized_negative CHECK (allow_negative OR balance >= 0)
);

-- ---------------------------------------------------------------------------
-- Financial transactions (journal transaction headers)
-- ---------------------------------------------------------------------------
CREATE SEQUENCE transaction_reference_seq START WITH 10000;

CREATE TABLE transactions (
    id                      UUID          PRIMARY KEY,
    reference               VARCHAR(20)   NOT NULL UNIQUE,
    type                    VARCHAR(12)   NOT NULL
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    status                  VARCHAR(10)   NOT NULL CHECK (status IN ('POSTED', 'REJECTED', 'REVERSED')),
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                CHAR(3)       NOT NULL CHECK (currency IN ('USD')),
    source_account_id       UUID          REFERENCES accounts (id),
    destination_account_id  UUID          REFERENCES accounts (id),
    description             VARCHAR(255),
    rejection_code          VARCHAR(40),
    rejection_reason        VARCHAR(255),
    -- UNIQUE: a transaction can be reversed at most once, enforced by the database.
    reverses_transaction_id UUID          UNIQUE REFERENCES transactions (id),
    initiated_by            VARCHAR(64)   NOT NULL,
    correlation_id          VARCHAR(64),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    posted_at               TIMESTAMPTZ,
    CONSTRAINT ck_tx_rejection_reason CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ck_tx_posted_at CHECK (status = 'REJECTED' OR posted_at IS NOT NULL),
    CONSTRAINT ck_tx_distinct_accounts CHECK (
        source_account_id IS NULL OR destination_account_id IS NULL
        OR source_account_id <> destination_account_id
    ),
    CONSTRAINT ck_tx_reversal_link CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL))
);

CREATE INDEX ix_transactions_created_at ON transactions (created_at DESC);
CREATE INDEX ix_transactions_source ON transactions (source_account_id, created_at DESC);
CREATE INDEX ix_transactions_destination ON transactions (destination_account_id, created_at DESC);

-- Only one status transition is legal after creation: POSTED -> REVERSED.
-- Financial fields can never change once written.
CREATE FUNCTION guard_transaction_update() RETURNS trigger AS $$
BEGIN
    IF (NEW.id, NEW.reference, NEW.type, NEW.amount, NEW.currency, NEW.source_account_id,
        NEW.destination_account_id, NEW.reverses_transaction_id, NEW.initiated_by, NEW.created_at, NEW.posted_at)
       IS DISTINCT FROM
       (OLD.id, OLD.reference, OLD.type, OLD.amount, OLD.currency, OLD.source_account_id,
        OLD.destination_account_id, OLD.reverses_transaction_id, OLD.initiated_by, OLD.created_at, OLD.posted_at) THEN
        RAISE EXCEPTION 'Financial fields of transaction % are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'POSTED' AND NEW.status = 'REVERSED') THEN
        RAISE EXCEPTION 'Illegal transaction status transition % -> % for %', OLD.status, NEW.status, OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transactions_guard_update
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION guard_transaction_update();

CREATE FUNCTION reject_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% rows cannot be deleted', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transactions_no_delete
    BEFORE DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_delete();

-- ---------------------------------------------------------------------------
-- Journal entries: the authoritative, append-only ledger
-- ---------------------------------------------------------------------------
CREATE TABLE journal_entries (
    id             BIGSERIAL     PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transactions (id),
    account_id     UUID          NOT NULL,
    direction      VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency       CHAR(3)       NOT NULL,
    posted_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    description    VARCHAR(255),
    CONSTRAINT fk_entries_account_currency
        FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX ix_journal_entries_account ON journal_entries (account_id, id DESC);
CREATE INDEX ix_journal_entries_transaction ON journal_entries (transaction_id);

-- Invariant: every journal transaction balances (debits = credits per currency).
-- DEFERRABLE INITIALLY DEFERRED: checked at COMMIT, after all entries of the
-- transaction exist, so a half-written journal can never be committed.
CREATE FUNCTION check_journal_balanced() RETURNS trigger AS $$
DECLARE
    imbalance RECORD;
BEGIN
    SELECT currency,
           SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS net
      INTO imbalance
      FROM journal_entries
     WHERE transaction_id = NEW.transaction_id
     GROUP BY currency
    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Journal transaction % is unbalanced in % by %',
            NEW.transaction_id, imbalance.currency, imbalance.net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- Invariant: posted entries are never modified or deleted. Corrections are new reversing entries.
CREATE FUNCTION reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TRIGGER trg_journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- ---------------------------------------------------------------------------
-- Idempotency
-- ---------------------------------------------------------------------------
-- A record is inserted in the SAME database transaction as the financial effect,
-- so a key is either committed together with its result or not at all.
CREATE TABLE idempotency_records (
    id              BIGSERIAL    PRIMARY KEY,
    client_id       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation       VARCHAR(40)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_status INT,
    response_body   JSONB,
    transaction_id  UUID         REFERENCES transactions (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_client_key UNIQUE (client_id, idempotency_key)
);

-- ---------------------------------------------------------------------------
-- Audit trail (append-only through ordinary application operations)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id             BIGSERIAL    PRIMARY KEY,
    actor          VARCHAR(64)  NOT NULL,
    action         VARCHAR(60)  NOT NULL,
    resource_type  VARCHAR(40)  NOT NULL,
    resource_id    VARCHAR(64),
    correlation_id VARCHAR(64),
    details        JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_events_created_at ON audit_events (created_at DESC);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- ---------------------------------------------------------------------------
-- Transactional outbox
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(40)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(60)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(500)
);

-- Partial index: the publisher only ever scans unpublished events.
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at) WHERE published_at IS NULL;
