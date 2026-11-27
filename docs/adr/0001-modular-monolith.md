# ADR 0001: Modular monolith, PostgreSQL as the single source of truth

**Status:** Accepted

## Context
LedgerGuard's core promise is that a transfer is atomic and balances can never diverge from the ledger. With microservices, a transfer touching accounts in two services would need a distributed transaction or a saga with compensations, and either one adds failure modes before the core is even correct.

## Decision
- One deployable Spring Boot application, with packages split by domain (`account`, `ledger`, `transaction`, `idempotency`, `audit`, `outbox`, …).
- One PostgreSQL database. Every financial mutation is one local ACID transaction.
- Integration with other systems goes through the transactional outbox (ADR 0006), never through synchronous calls inside a posting.

## Consequences
- Atomicity, locking and idempotency come from PostgreSQL guarantees rather than coordination protocols.
- Modules share a database, so boundaries are enforced by code review, not by the network.
- Scaling writes means scaling one database. Horizontal sharding by account would be a later, deliberate step.
