# ADR 0005: Idempotency keys claimed in the same database transaction

**Status:** Accepted

## Decision
- Every financial mutation requires an `Idempotency-Key` header (8–128 chars; a UUID is recommended).
- The key is scoped to the authenticated user. It is stored with a SHA-256 fingerprint of the canonical request and the final response.
- **Claim, work and response are written in ONE database transaction**, guarded by `UNIQUE (client_id, idempotency_key)`:
  - same key, same payload, already committed → replay the stored status and body, with `Idempotent-Replayed: true`;
  - same key, different payload → `422 IDEMPOTENCY_KEY_REUSED`;
  - concurrent duplicates → PostgreSQL blocks the second INSERT until the first finishes, then the second replays (or takes over if the first rolled back);
  - any failure → rollback removes the claim, so the client can safely retry.
- Business rejections are stored and replayed as well. A retry never re-evaluates a past decision.

## Alternatives considered
- **Redis or another in-memory cache**: lost on restart and not atomic with the ledger write. A crash between "write ledger" and "write key" causes a duplicate.
- **A separate "in-progress" state with a timeout**: needs a recovery job, plus a decision about what to tell clients during the window.

## Consequences
- Verified by `IdempotencyIntegrationTest` (100 concurrent duplicates produce exactly one transfer) and by the SQL-level script.
- Records are kept forever. A retention job (for example, 30 days) is in the backlog.
