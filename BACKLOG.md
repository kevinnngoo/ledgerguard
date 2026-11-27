# Backlog

Ordered by value. Items are scoped so each can be one Claude Code or hands-on session.

## Reconciliation (Phase 5)
- [ ] `V3` migration: `reconciliation_runs`, `external_transaction_records`, `reconciliation_exceptions`.
- [ ] CSV upload endpoint (size and row limits, strict schema, synthetic data only) → staged external records.
- [ ] Deterministic matcher on (reference, amount, currency, date) with a cutoff timestamp so concurrent postings are excluded consistently.
- [ ] Exception types: MISSING_INTERNAL, MISSING_EXTERNAL, AMOUNT_MISMATCH, CURRENCY_MISMATCH, DUPLICATE_EXTERNAL.
- [ ] Exception workflow: OPEN → INVESTIGATING → RESOLVED / DISMISSED (justification required), audited. Never auto-edit the ledger.
- [ ] Reconciliation dashboard: side-by-side internal vs external records.

## Performance (Phase 8)
- [ ] Synthetic data generator (1k / 10k / 100k / 1M transactions) that preserves invariants.
- [ ] EXPLAIN ANALYZE before/after for account history, date-range reports and the pending-outbox query; record actual numbers.
- [ ] k6 scenarios: normal traffic, hot-account withdrawals, duplicate storms, read-heavy history, mixed. Report business rejections separately from technical errors.
- [ ] Prometheus + Grafana services in compose with a provisioned dashboard (throughput, p95/p99, rejections, outbox backlog, Hikari usage).

## Hardening
- [ ] Clearing-account hotspot: shard clearing accounts or batch-post clearing legs; measure the before/after with k6.
- [ ] Idempotency record retention (e.g. purge completed records older than 30 days in batches).
- [ ] Persistent consumer de-dup table (`processed_events`) and an example downstream projection.
- [ ] Manual-approval queue for transactions above a threshold (risk rule result MANUAL_REVIEW, separate APPROVER decision).
- [ ] Rate limiting on mutation endpoints; dependency vulnerability scanning in CI (OWASP or `npm audit`).
- [ ] Code formatting check (Spotless / Prettier) in CI.
- [ ] Playwright E2E for sign-in → transfer → inspect journal → run simulation.
- [ ] Multi-instance test: two backend containers behind one proxy running the concurrency suite.
- [ ] Separate DB roles: application role without UPDATE/DELETE on `journal_entries` (defence in depth on top of triggers).
