package com.ledgerguard.transaction;

/**
 * Transaction state machine.
 * <pre>
 *   (request) --validation/risk/funds fail--> REJECTED   (committed, no journal entries)
 *   (request) --posted atomically----------> POSTED     (committed with balanced entries)
 *   POSTED    --compensating reversal-------> REVERSED   (original entries untouched)
 * </pre>
 * Technical failures (e.g. database errors) roll back completely and leave NO transaction row;
 * the client retries with the same Idempotency-Key. There is deliberately no RECEIVED/VALIDATING
 * state: posting is synchronous, so a transaction is never visible before its outcome is committed.
 * The POSTED -> REVERSED rule is also enforced by a database trigger.
 */
public enum TransactionStatus {
    POSTED,
    REJECTED,
    REVERSED
}
