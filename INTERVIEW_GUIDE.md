# LedgerGuard interview guide

Use this to explain the project in your own words. Before any interview: run the tests yourself, open each file referenced, and be ready to sketch the transfer sequence on a whiteboard.

## The 60-second pitch
"LedgerGuard is a double-entry ledger service I built to learn how financial systems stay correct under concurrency and failure. It's Spring Boot and PostgreSQL. Every transfer is one database transaction: it claims an idempotency key, locks the affected balance rows in a fixed order, checks funds under the lock, writes balanced journal entries, and commits the audit record and an outbox event with it. I proved the guarantees with Testcontainers tests against real Postgres. For example, 100 concurrent duplicate requests produce exactly one transfer, and a $800 and a $600 withdrawal racing on a $1,000 account never both succeed. Without the lock, my measurements showed 25 out of 25 $100 withdrawals approved from $1,000. It uses synthetic data only, and I built it with heavy AI assistance, so I made a point of being able to explain and test every part."

## How a transfer is processed
1. `TransactionController` receives `POST /transfers` with an `Idempotency-Key`. Spring Security has already checked the OPERATOR role.
2. `Money.requireValidAmount` validates the amount (positive, at most 2 decimals, never rounded) before touching the database.
3. `TransactionRunner` opens one READ COMMITTED transaction.
4. `IdempotencyService.claim` inserts the key. If it already exists with the same request fingerprint, the stored response is returned.
5. `LedgerRepository.lockBalances` locks both balance rows (`FOR UPDATE`, ordered by account id).
6. Account status is re-read, then risk rules (limits) and the funds check run against the locked balance.
7. Either a REJECTED transaction row with a reason is written (HTTP 422), or a POSTED row plus two journal entries plus projection updates.
8. The audit event and outbox event are inserted, and the response is stored on the idempotency record.
9. COMMIT. The deferred trigger verifies the journal balances. Locks are released.

## Likely questions and accurate answers

**Why BigDecimal?** Binary floating point can't represent most decimal fractions exactly. Amounts are `NUMERIC(19,2)` in Postgres and are sent as strings in JSON so JavaScript doesn't lose precision. Sub-cent input is rejected rather than rounded.

**Explain debits and credits here.** Customer accounts are liabilities on the institution's books, so a credit increases them. The clearing account is an asset, so a debit increases it. A deposit is DR clearing, CR customer; a transfer is DR source, CR destination. Total debits always equal total credits, and the integrity endpoint checks that.

**How do you stop two withdrawals from overdrawing?** Pessimistic row locks. Both requests do `SELECT … FOR UPDATE` on the balance row; the second waits until the first commits, then sees the reduced balance. A CHECK constraint (`allow_negative OR balance >= 0`) is a second guard in the database.

**Why not `synchronized`?** It only works inside one JVM. With two instances behind a load balancer, it protects nothing. The lock has to live in the shared database.

**Why pessimistic instead of optimistic locking?** On a contended account, optimistic locking makes most attempts fail and retry. Pessimistic locking queues them. It's also simpler to reason about when one operation touches several accounts and needs a consistent view for risk checks. The cost is lower throughput on one hot account.

**How do you handle deadlocks?** I prevent them by locking rows in a consistent order (by account id), so A→B and B→A both request the same row first. As a backstop, `TransactionRunner` retries deadlock and serialization errors up to 3 times. That's safe because the failed attempt rolled back completely, including the idempotency claim. There's a test with 40 transfers in each direction at once.

**How does idempotency work under concurrency?** The key is inserted with `ON CONFLICT DO NOTHING` under a unique constraint, in the same transaction as the money movement. A concurrent duplicate's insert blocks on the uncommitted row. When the first commits, the duplicate sees the record and returns the stored response; if the first rolled back, the duplicate does the work. Keys are per user, and a different payload under the same key is rejected with 422.

**What happens if the server crashes halfway through a transfer?** The transaction never committed, so PostgreSQL rolls back everything: entries, balances, the key and the outbox row. The client retries with the same key and it executes once. I simulate this with a failure injected after the first journal entry.

**How do corrections work?** Never by editing. Entries are append-only (a trigger enforces it). A reversal is a new transaction with every entry flipped, linked to the original, and a unique constraint allows only one. The person who initiated a transaction can't reverse it (separation of duties).

**Why the transactional outbox?** If you publish to a broker after committing, a crash in between loses the event; if you publish before, a failed commit leaves consumers seeing something that never happened. Writing the event in the same transaction and publishing it later gives at-least-once delivery, so consumers de-duplicate by event id. I don't claim exactly-once.

**How do you know the balances are right?** The ledger is authoritative. Balance projections are updated in the same transaction, and `/ledger/integrity` recomputes every balance from the entries in one REPEATABLE READ snapshot. It also checks that every journal balances, that posted transactions have entries, and that there are no negatives and no duplicate idempotent effects. Every integration test ends by asserting the whole ledger is healthy.

**How is it tested?** Unit tests for money rules, state transitions and posting rules. Integration tests run against real PostgreSQL in Testcontainers (no H2, because H2 doesn't behave like Postgres for locks and conflicts). Concurrency tests start every thread and release them through one latch. There's also a SQL-only script that reproduces the race and the fix without Java. CI runs all of it.

**How would it scale?** First, shorten lock hold times and remove the single clearing-account hotspot (sharded clearing accounts). Then read replicas for reporting, partitioning `journal_entries` by time, and eventually sharding accounts across databases. Cross-shard transfers would then need a saga or two-phase approach, which is why I started with a monolith.

**What would you do differently or next?** Reconciliation against external records, k6 load tests with real numbers, a query-plan study on a large synthetic dataset, a persistent consumer de-dup table, and real identity (OIDC) instead of demo users.

## Hard follow-ups to practise
- Draw the lock waits for three concurrent transfers A→B, B→C, C→A. Can they deadlock with ordered locking?
- Why does the funds check have to happen *after* acquiring the lock?
- What happens if two requests use the same key but different users?
- The REPEATABLE READ integrity check runs while postings continue. Why doesn't it report false mismatches?
- What's the difference between a business rejection (422, recorded) and a technical failure (rolled back, nothing recorded)?
- Why does the outbox publisher use `SKIP LOCKED`? What would happen with plain `FOR UPDATE` and two publishers?

## Honest limitations to volunteer
USD only. Demo authentication. In-memory broker and consumer de-dup. No load-test or EXPLAIN ANALYZE numbers yet. Reconciliation not built yet. The clearing account is a throughput bottleneck. Built with AI assistance, and verified with tests I can run and explain.
