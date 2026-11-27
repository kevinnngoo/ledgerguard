# Engineering case studies

Every number here was measured; none is estimated. Where a result has not been measured yet, it says so.

**Measurement environment for the SQL-level results:** PostgreSQL 16.15 on Ubuntu 24.04, 2 vCPU, 7 GB RAM (a cloud development container), driven by `scripts/verify-db-concurrency.sh` with 25 concurrent `psql` sessions. These are local development measurements, not production performance claims.

---

## 1. Preventing double spending

**Problem.** An account holds $1,000 and receives many simultaneous $100 withdrawals. A naive implementation reads the balance, checks it, and writes `balance − 100`.

**Controlled baseline (no locking).** 25 concurrent sessions each read the balance (all saw $1,000), slept 50 ms to widen the race window, then wrote back their computed value.

| Strategy | Approved | Paid out | Final stored balance |
| --- | --- | --- | --- |
| Read-check-write, no lock | **25 / 25** | $2,500 from a $1,000 account | $800.00 (lost updates hide the overdraft) |
| `SELECT … FOR UPDATE`, then check and write | **10 / 25** | $1,000 | $0.00 |

The unsafe run is wrong in two ways: it approved $2,500 of withdrawals from $1,000, and the stored balance ($800) does not even reflect what was paid out, because each writer overwrote the others.

**Engineering solution.** `LedgerRepository.lockBalances` locks every affected `account_balances` row in `account_id` order before any check. Contenders queue, and each funds check sees the latest committed balance. A `CHECK (allow_negative OR balance >= 0)` constraint is a second, independent guard.

**Trade-offs.** Concurrent requests on one account are serialized, so throughput on a hot account is bounded by lock hold time. Deposits and withdrawals also all lock the clearing account (see ARCHITECTURE.md).

**Verification.**
- `scripts/verify-db-concurrency.sh` (the numbers above; also runs in CI).
- `ConcurrencyIntegrationTest`: $800 vs $600 on $1,000, repeated 5 times; 50 concurrent $100 withdrawals from $1,000 (exactly 10 posted); 120 random transfers across 4 accounts (total value preserved); 80 opposite-direction transfers (no deadlock).

**Remaining limitations.** Measured on one PostgreSQL instance. No multi-instance application test yet.

---

## 2. Preventing duplicate transactions

**Problem.** A client sends a $250 transfer, times out, and retries. Without protection, a retry storm moves the money many times.

**Result (SQL level).** 100 concurrent sessions with the same idempotency key, each attempting a $250 withdrawal from $1,000: **executed = 1, replayed = 99, final balance $750.00**.

**Engineering solution.** `INSERT … ON CONFLICT DO NOTHING` on `UNIQUE (client_id, idempotency_key)`, in the same transaction as the posting. The second request's insert waits for the first transaction to finish, then replays its stored response. If the first rolled back, the second takes over. Reusing a key with a different payload returns `422 IDEMPOTENCY_KEY_REUSED`.

**Trade-offs.** Waiting duplicates hold a database connection while blocked. The records table grows without bound until a retention job exists.

**Verification.** `IdempotencyIntegrationTest` (100 concurrent duplicates through the full Java service produce one transfer, 2 journal entries, and correct balances) and the duplicate-submission Simulation Lab scenario.

---

## 3. Preserving atomicity

**Problem.** A failure in the middle of posting (after the debit entry, before the credit) must not leave a half-written transfer.

**Engineering solution.** All writes for one operation share one database transaction. A deferred constraint trigger re-checks at COMMIT that every journal transaction balances, which protects against bugs in code paths that never reach the Java check.

**Verification.**
- `RollbackIntegrationTest` injects a failure after the first entry, then asserts no change to balances, entries, the transactions table, outbox events or the idempotency key, and a healthy integrity report. A retry with the same key then succeeds exactly once.
- `DatabaseConstraintIntegrationTest` commits an unbalanced journal directly via SQL and asserts it is rejected at COMMIT. The same was checked manually in `psql`:

  ```
  ERROR:  Journal transaction aaaaaaaa-…-000000000002 is unbalanced in USD by -50.00
  ```

---

## 4. Recovering from event-delivery failures

**Problem.** If posting and event publication are separate steps, a crash or broker outage between them loses events or publishes events for transactions that never committed.

**Engineering solution.** Transactional outbox: the event row commits with the ledger change. A publisher retries with exponential backoff and records each error.

**Verification.** `OutboxIntegrationTest`:
- with the broker down, the deposit still posts and its event stays pending (`attempts = 1`, error recorded);
- after recovery it publishes (`attempts = 2`);
- a duplicate delivery is ignored by the consumer.

The event-recovery Simulation Lab scenario shows the same timeline in the UI.

---

## 5. Optimizing database queries

**Status: not yet measured.** The schema has indexes for the expected access paths:
- `journal_entries (account_id, id DESC)` for account history;
- `transactions (created_at DESC)` and `(source_account_id, created_at DESC)` for listings and daily limits;
- a partial index on unpublished outbox rows.

A before/after `EXPLAIN ANALYZE` comparison on a generated dataset is planned (BACKLOG item "Synthetic data generator + EXPLAIN ANALYZE study"). No performance improvement is claimed until it is measured.
