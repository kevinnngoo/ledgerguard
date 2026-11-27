# Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 1. Foundation | Repo structure, Spring Boot, React, PostgreSQL, Flyway, Docker Compose, CI, docs | Done (all CI jobs passing) |
| 2. Core ledger | Accounts, BigDecimal rules, double-entry journal, projections, deposits/withdrawals/transfers, validation | Done |
| 3. Transaction integrity | Row locking, retries, idempotency, reversals, concurrency tests, failure injection, integrity checks | Done |
| 4. Operational dashboard | Overview, accounts, transactions, ledger explorer, Simulation Lab | Done |
| 5. Reconciliation & auditing | Audit trail ✔ · CSV ingestion of external records · matching engine · exceptions workflow · reconciliation UI | Partly done (audit trail) |
| 6. Security | RBAC ✔ · separation of duties on reversals ✔ · manual approval queue · rate limiting · dependency scanning | Partly done |
| 7. Reliability & events | Transactional outbox ✔ · backoff ✔ · consumer de-dup ✔ (in-memory) · persistent consumer · job claiming for reconciliation | Partly done |
| 8. Observability & performance | Micrometer metrics ✔ · ECS logs ✔ · Prometheus + Grafana compose · synthetic data generator · EXPLAIN ANALYZE study · k6 load tests | Started |
| 9. Extensions | Kafka, multi-instance test, multi-currency, settlement, trade reconciliation, cloud deploy | Not started; evaluate only after 5–8 |

Next highest-value step: **Phase 5 reconciliation engine**. It is the main remaining feature that maps directly to fund-accounting and operations work at the target employers.
