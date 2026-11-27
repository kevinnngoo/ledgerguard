# LedgerGuard learning guide

Every concept below points at real code and tests in this repository. Topics run from beginner to advanced. Each ends with a small exercise to do **by hand**, ideally in TradeOps or a scratch project, so the understanding becomes yours.

Paths are relative to `backend/src/main/java/com/ledgerguard/` unless noted.

---

## 1. Money with BigDecimal (beginner)

- **What:** an exact decimal type. `0.1 + 0.2` is `0.30000000000000004` as a `double`, and exactly `0.3` as `BigDecimal`.
- **Why LedgerGuard needs it:** a ledger that is off by a fraction of a cent is wrong.
- **Where:** `common/Money.java` (`requireValidAmount`, `requireSupportedCurrency`); columns are `NUMERIC(19,2)` in `V1__core_ledger.sql`; JSON amounts are strings via `@JsonFormat(shape = STRING)`.
- **How:** rejects null, ≤ 0, more than 2 significant decimals (never rounds), and more than 17 integer digits. Returns `amount.setScale(2)`, which is exact because no digits are dropped.
- **Alternatives:** `long` cents (fast and exact, less expressive); `double` (wrong).
- **If done wrong:** silent rounding means a customer sends $10.005 and the system invents or loses half a cent; doubles accumulate drift.
- **Tests:** `common/MoneyTest.java` (`src/test/java/...`).
- **Interview questions:** Why not `double`? What does `compareTo` vs `equals` do for `new BigDecimal("1.0")` vs `"1.00"`? (`equals` compares scale too.)
- **Exercise:** write `splitEvenly(BigDecimal total, int ways)` that returns parts summing exactly to the total (e.g. $10.00 / 3 → 3.34, 3.33, 3.33). Test it.

## 2. Double-entry accounting and normal balances (beginner)

- **What:** every transaction has debits and credits that sum to equal amounts. Each account type has a "normal" side that increases it.
- **Why:** value is never created or destroyed. Any balance can be verified from the entries.
- **Where:** `account/AccountType.java` (normal balances), `ledger/LockedBalance.deltaFor` (turns a leg into +/-), and the posting legs in `transaction/TransactionService` (`deposit`, `withdraw`, `transfer`).
- **How:** customer accounts are LIABILITIES (the bank owes the customer), so a CREDIT increases them. The clearing account is an ASSET, so a DEBIT increases it. A deposit is DR clearing / CR customer.
- **If done wrong:** treating "debit = minus" everywhere makes asset and liability accounts move in the wrong direction.
- **Tests:** `ledger/PostingRulesTest.debitAndCreditMeaningDependsOnNormalBalance`, `LedgerPostingIntegrationTest.depositDebitsClearingAndCreditsCustomerLiability`.
- **Interview questions:** Why is a customer deposit a liability for a bank? What are the entries for a $250 transfer? Why have a clearing account at all?
- **Exercise:** on paper, write the journal for: deposit $1,000 to A, transfer $300 A→B, withdraw $100 from B, reverse the transfer. Then compute every balance from your entries.

## 3. REST API design, validation and errors (beginner)

- **Where:** `*/…Controller.java`, request records in `*Dtos.java` (Jakarta Validation annotations), `common/GlobalExceptionHandler.java` (RFC 9457 Problem Details), `common/CorrelationIdFilter.java`.
- **How:** controllers validate and delegate; no business logic lives in them. Expected errors are `ApiException` with a stable `code`. Unexpected ones return a generic message plus the correlation ID, never SQL.
- **If done wrong:** stack traces leak internals; clients can't distinguish "insufficient funds" from "server down".
- **Tests:** `SecurityIntegrationTest.malformedRequestsReturnValidationProblemsWithoutInternals`.
- **Interview questions:** 400 vs 404 vs 409 vs 422? Why version the API (`/api/v1`)? Why put a correlation ID in every response?
- **Exercise:** add `GET /api/v1/accounts/{id}/statement?from=&to=` that returns entries in a date range with an opening and closing balance. Validate that `from < to`.

## 4. Schema design, constraints and Flyway (beginner → intermediate)

- **Where:** `backend/src/main/resources/db/migration/V1__core_ledger.sql`, `V2__system_accounts.sql`.
- **How:** CHECK constraints (positive amounts, allowed statuses), UNIQUE (`reverses_transaction_id`, idempotency key), a composite FK `(account_id, currency)` so an entry's currency must match its account, and triggers for append-only tables. Flyway applies the migrations on startup.
- **Why:** the database is the last line of defence; it holds even if Java has a bug or someone runs ad-hoc SQL.
- **Tests:** `DatabaseConstraintIntegrationTest` (bypasses the services and writes SQL directly).
- **Interview questions:** Why not let Hibernate generate the schema? What is a deferred constraint? Why is a composite FK useful here?
- **Exercise:** write `V3__account_limits.sql` adding a per-account daily limit column with a CHECK (> 0). Add a test that inserting a negative limit fails.

## 5. Database transactions and atomicity (intermediate)

- **What:** a group of statements that commits entirely or not at all.
- **Where:** `common/TransactionRunner.inTransaction`; everything in `TransactionService.execute` runs inside one call to it. `ledger/PostingEngine.post` writes entries and projections. The deferred trigger `check_journal_balanced` runs at COMMIT.
- **How:** idempotency claim → locks → checks → transaction row → entries → projections → audit → outbox → stored response → COMMIT. An exception anywhere rolls back all of it.
- **If done wrong:** with each step in its own transaction, a crash after the debit leaves money missing.
- **Tests:** `RollbackIntegrationTest` (`FailureInjector` throws after the first entry; asserts nothing changed, including the idempotency key), `DatabaseConstraintIntegrationTest.unbalancedJournalCannotBeCommitted`.
- **Interview questions:** What does ACID mean, concretely, for a transfer? What happens to an open transaction if the JVM is killed? Why is the retry in `TransactionRunner` safe?
- **Exercise:** in a scratch Spring app, write a method that inserts two rows and throws between them. Observe the result with and without a transaction.

## 6. Isolation levels (intermediate)

- **Where:** `TransactionRunner` uses READ COMMITTED for writes and REPEATABLE READ (read-only) for `inSnapshot`, which `IntegrityService.check` and `AccountService.balance` use.
- **Why:** under READ COMMITTED each statement sees the latest committed data, which is exactly what a re-read after acquiring a lock needs. Integrity checks need every query to see the *same* snapshot, or a posting committing between two queries would show a false mismatch.
- **Interview questions:** Name the anomalies (dirty read, non-repeatable read, phantom, lost update, write skew). Which does READ COMMITTED allow? Why not just use SERIALIZABLE everywhere?
- **Exercise:** with two `psql` sessions, demonstrate a non-repeatable read under READ COMMITTED, then show REPEATABLE READ prevents it.

## 7. Race conditions and row-level locking (intermediate → advanced)

- **What:** two requests read the same balance, both see enough funds, and both withdraw.
- **Where:** `ledger/LedgerRepository.lockBalances` (`SELECT … FOR UPDATE OF b … ORDER BY account_id`), called first in each `TransactionService` operation. Account status is re-read *after* the lock.
- **How:** the second transaction blocks on the row lock until the first commits, then reads the new balance.
- **Measured:** without locks, 25/25 concurrent $100 withdrawals were approved from $1,000; with `FOR UPDATE`, 10/25 (`docs/case-studies.md`, `scripts/verify-db-concurrency.sh`).
- **Alternatives:** optimistic version column, conditional `UPDATE … WHERE balance >= x`, SERIALIZABLE (see ADR 0004).
- **Tests:** `ConcurrencyIntegrationTest` (all four tests).
- **Interview questions:** Why doesn't Java `synchronized` solve this? Pessimistic vs optimistic, and when would you pick each? What happens to throughput on one hot account?
- **Exercise:** run `scripts/verify-db-concurrency.sh`, then change the "safe" function to drop `FOR UPDATE` and watch it fail. Explain why in your own words.

## 8. Deadlocks and lock ordering (advanced)

- **What:** T1 locks A and waits for B while T2 locks B and waits for A. PostgreSQL detects this and kills one with error 40P01.
- **Where:** `ORDER BY b.account_id` in `lockBalances`; retries for `ConcurrencyFailureException` in `TransactionRunner`.
- **Tests:** `ConcurrencyIntegrationTest.oppositeDirectionTransfersDoNotDeadlockOrLoseUpdates` (40 A→B and 40 B→A at once; all must post).
- **Interview questions:** How do you prevent deadlocks rather than just retrying them? When is retrying a failed financial transaction safe?
- **Exercise:** remove the `ORDER BY` in a branch, run that test, and look for `deadlock detected` in the logs.

## 9. Idempotency (advanced)

- **Where:** `idempotency/IdempotencyService` (`claim`, `complete`, `fingerprint`); used by `TransactionService.execute`; header handled in `TransactionController`.
- **How:** `INSERT … ON CONFLICT DO NOTHING RETURNING id` on `UNIQUE (client_id, idempotency_key)`. A concurrent duplicate's INSERT waits on the first transaction, then replays its stored response. Because the claim is in the same transaction as the money movement, a failure removes both.
- **If done wrong:** an in-memory cache loses keys on restart, and a check-then-insert has its own race condition.
- **Tests:** `IdempotencyIntegrationTest` (100 concurrent duplicates produce 1 transfer; key reuse → 422; per-client scope; rejection replay), `RollbackIntegrationTest` (the key is reusable after a failure).
- **Interview questions:** Why scope keys per client? What should happen when the same key arrives with a different body? Why replay a *rejection* instead of re-evaluating it?
- **Exercise:** implement an idempotent "create order" endpoint in TradeOps using the same claim-in-transaction pattern, and write a 50-thread test for it.

## 10. Immutability, reversals and separation of duties (advanced)

- **Where:** `TransactionService.reverse`; triggers `trg_journal_entries_append_only` and `trg_transactions_guard_update`; `UNIQUE (reverses_transaction_id)`.
- **How:** lock the original transaction row, validate (POSTED, not a reversal, not your own), then post the mirrored legs as a new REVERSAL transaction and mark the original REVERSED.
- **Tests:** `ReversalIntegrationTest` (including 10 concurrent reversal attempts → exactly one).
- **Interview questions:** Why never UPDATE a posted entry? What if the funds were already moved out before the reversal?
- **Exercise:** design partial reversals. Which constraint stops working, and what would replace it?

## 11. Testing with Testcontainers and coordinated concurrency (intermediate)

- **Where:** `src/test/java/com/ledgerguard/support/IntegrationTestBase.java` (one shared `postgres:16-alpine` container), `support/Concurrently.java` (start all threads, release them through one latch).
- **Why not H2:** H2 doesn't implement PostgreSQL locking, `ON CONFLICT` waits, deferred triggers or `SKIP LOCKED`, so the tests would prove nothing.
- **Interview questions:** How do you make a concurrency test reproducible? What is the difference between a unit test and an integration test here?
- **Exercise:** write a Testcontainers test in TradeOps that proves a unique constraint blocks duplicate trade IDs.

## 12. Authentication and authorization (intermediate)

- **Where:** `security/SecurityConfig.java` (URL rules, demo users, JSON 401/403), `security/MeController.java`.
- **Notes:** authorization is server-side; the UI hides buttons only for convenience. CSRF is disabled because the API is stateless with header credentials. Passwords are BCrypt-hashed and come from the environment.
- **Tests:** `SecurityIntegrationTest`.
- **Interview questions:** Authentication vs authorization? When is CSRF protection required? How would you move this to OAuth2/OIDC?
- **Exercise:** add an `OPERATOR_LIMITED` role that can post transfers only up to $1,000, enforced in the service layer, with a test.

## 13. Transactional outbox and at-least-once delivery (advanced)

- **Where:** `outbox/OutboxRepository.append` (same transaction as the posting), `outbox/OutboxPublisher` (`FOR UPDATE SKIP LOCKED`, backoff), `outbox/SimulatedBroker` (outage switch, de-duplicating consumer), `outbox/OutboxScheduler`.
- **Tests:** `OutboxIntegrationTest`; `SimulationIntegrationTest.eventRecoveryScenario`.
- **Interview questions:** Why not publish to Kafka inside the transaction? Why is delivery at-least-once and not exactly-once? What does `SKIP LOCKED` buy you?
- **Exercise:** add a persistent `processed_events` table and a consumer that updates a "daily volume" table exactly once per event, even when delivered twice.

## 14. Observability (intermediate)

- **Where:** `CorrelationIdFilter` (MDC + header, stored on transactions, audit and outbox events), Micrometer timers and counters in `TransactionService.execute` (`ledgerguard.transactions`, `ledgerguard.transaction.duration`), the outbox backlog gauge, ECS JSON logs in `application-docker.yml`.
- **Interview questions:** Why avoid transaction IDs as metric tags (cardinality)? How would you trace one request from the API through to event publication?
- **Exercise:** add a counter for idempotency replays per operation, then view it at `/actuator/metrics` as admin.
