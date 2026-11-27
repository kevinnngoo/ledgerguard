# ADR 0004: Pessimistic row locking in a consistent order

**Status:** Accepted

## Options evaluated

| Strategy | How | Verdict |
| --- | --- | --- |
| No locking (read, check, write) | `SELECT balance` then `UPDATE balance = :computed` | **Broken.** `scripts/verify-db-concurrency.sh` measured 25 of 25 concurrent $100 withdrawals approved from $1,000 |
| Optimistic locking | `version` column, retry on conflict | Correct, but under contention on a hot account most attempts fail and retry, which wastes work and adds latency |
| Atomic conditional update | `UPDATE … SET balance = balance - x WHERE balance >= x` | Correct for a single account, but harder to extend to multi-account postings, risk rules and reversals that must all see the same locked state |
| SERIALIZABLE isolation | let PostgreSQL detect anomalies | Correct, but every conflict becomes a retryable 40001 error; harder to reason about and to explain |
| **Pessimistic `SELECT … FOR UPDATE`** | lock balance rows, then check and write | **Chosen.** Contenders queue, and the funds check sees the latest committed balance |

## Decision
- Lock `account_balances` rows with `SELECT … FOR UPDATE`, **ordered by `account_id`**, before any check or write. Consistent ordering prevents the A→B / B→A deadlock.
- Re-read account status after acquiring the lock. Status changes take the same lock.
- Isolation level is READ COMMITTED (each statement sees the latest committed data, which is what a locked re-read needs).
- `TransactionRunner` retries deadlock and serialization failures up to 3 times. This is safe because a failed attempt rolls back completely.
- Never rely on Java `synchronized`: it does nothing for a second application instance.

## Consequences
- Throughput on a single hot account is bounded by lock hold time. Transactions are kept short (no network calls while holding locks).
- Every deposit and withdrawal also locks the shared clearing account. That is a known bottleneck, listed in the backlog.
