# ADR 0003: Transactional balance projection

**Status:** Accepted

## Context
Recomputing a balance by summing all of an account's entries is O(entries) and gets slower forever. Posting needs the current balance for the funds check on every withdrawal.

## Decision
- `account_balances` holds one row per account with the current balance in normal-balance terms.
- It is updated **in the same database transaction** as the journal entries, so it can never be ahead of or behind a committed posting.
- The ledger stays authoritative. `IntegrityService` recomputes every balance from entries and reports mismatches; `POST /api/v1/ledger/projections/rebuild` (ADMIN) rewrites drifted projections under locks.
- A `CHECK (allow_negative OR balance >= 0)` constraint makes the projection a second, database-level guard against overdrafts.

## Consequences
- O(1) balance reads, plus a natural row to lock (ADR 0004).
- The projection could still drift through manual SQL. That is detectable and repairable, and covered by `IntegrityIntegrationTest`.
