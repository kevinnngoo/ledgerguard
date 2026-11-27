# ADR 0006: Transactional outbox now, Kafka later (if ever)

**Status:** Accepted

## Decision
- Events (`TransactionPosted`, `TransactionRejected`, `TransactionReversed`, `AccountCreated`, `AccountStatusChanged`) are inserted into `outbox_events` in the same transaction as the state change.
- A scheduled publisher claims batches with `FOR UPDATE SKIP LOCKED`, publishes them, and records attempts, errors and exponential backoff.
- Delivery is at-least-once, and consumers de-duplicate by event ID.
- The broker is currently an in-process `SimulatedBroker` that can be switched off for the Simulation Lab. Kafka is **not** introduced until there is a real consumer that needs it. The `EventPublisher` interface is the seam where it would plug in.

## Why not publish directly after commit?
If the process dies between COMMIT and publish, the event is lost. If you publish before COMMIT and the commit then fails, consumers see an event for a transaction that never happened. The outbox avoids both.

## Consequences
Financial posting never depends on broker availability; this is verified by `OutboxIntegrationTest` and the event-recovery simulation.
