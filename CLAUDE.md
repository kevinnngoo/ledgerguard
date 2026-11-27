# CLAUDE.md: persistent context for AI-assisted development

## Mission
LedgerGuard is a simulated, production-inspired double-entry ledger that stays correct under concurrency, retries and failures. **Financial correctness beats features, visuals and raw throughput.** Synthetic data only; never present it as a real financial service.

## Stack
- Backend: Java 21, Spring Boot 3.5, `JdbcClient` (explicit SQL, no JPA), Flyway, Spring Security (HTTP Basic), springdoc.
- Database: PostgreSQL 16.
- Frontend: React 19 + TypeScript + Vite + Tailwind v4 (`frontend/`).
- Tests: JUnit 5, AssertJ, MockMvc, Testcontainers (`postgres:16-alpine`), Vitest.
- CI: `.github/workflows/ci.yml` (backend, frontend, database, docker jobs).

## Layout
```
backend/src/main/java/com/ledgerguard/
  account/ ledger/ transaction/ idempotency/ audit/ outbox/ security/ simulation/ reporting/ common/
backend/src/main/resources/db/migration/   Flyway (never edit an applied migration; add V3__...)
backend/src/test/java/com/ledgerguard/integration/   Testcontainers tests (extend IntegrationTestBase)
frontend/src/  api.ts, auth.tsx, format.ts, useLoad.ts, components/, pages/
scripts/verify-db-concurrency.sh   SQL-level proof, runs in CI
docs/adr/  docs/case-studies.md  docs/api-examples.md
```

## Financial invariants (never weaken these)
1. Every journal transaction balances per currency (app check + deferred trigger `trg_journal_balanced`).
2. Entries reference valid accounts with matching currency (composite FK).
3. Amounts > 0. Direction is DEBIT/CREDIT, never a sign.
4. Only USD; sub-cent input is rejected, never rounded.
5. `journal_entries` and `audit_events` are append-only (triggers). Corrections are reversals.
6. Projection (`account_balances`) is updated in the same DB transaction as entries.
7. No unauthorized negative balance (funds check under lock + CHECK constraint).
8. One operation = one DB transaction (claim key → lock → check → write → audit → outbox → store response → commit).
9. Idempotency claim commits atomically with the financial effect.
10. Lock balance rows with `LedgerRepository.lockBalances` (ORDER BY account_id FOR UPDATE) before checking or writing.

## Security rules
- Authorization lives in `SecurityConfig` (server-side). Never rely on the frontend.
- No secrets in the repo: passwords come from env (`.env` is git-ignored; `.env.example` has placeholders).
- Errors are Problem Details via `GlobalExceptionHandler`; never leak SQL or stack traces.
- Simulation and failure injection are off unless `LEDGERGUARD_SIMULATION_ENABLED=true`.

## Commands
```bash
cd backend && mvn verify                      # all Java tests (needs Docker)
cd backend && mvn spring-boot:run             # needs LEDGERGUARD_DB_PASSWORD, LEDGERGUARD_DEMO_PASSWORD
cd frontend && npm run dev | lint | typecheck | test | build
docker compose up --build                     # full stack on :3000 / :8080
PGHOST=... PGUSER=... PGPASSWORD=... ./scripts/verify-db-concurrency.sh
```

## Current milestone
Phases 1–3 implemented (foundation, core ledger, transaction integrity), plus the dashboard, Simulation Lab, audit trail, RBAC, outbox and integrity checks. Next: see ROADMAP.md (reconciliation engine → load tests → observability stack).

## Working agreements
- Do not report planned work as done. Do not fabricate test or benchmark results.
- Add a test for every new invariant or bug fix, against real PostgreSQL.
- Update LEARNING_GUIDE.md, INTERVIEW_GUIDE.md and docs when behaviour changes.
- Never force-push, rewrite history, commit `.env`, or run destructive SQL outside disposable test databases.
