# ADR 0007: Explicit SQL (JdbcClient) instead of JPA; Testcontainers instead of H2

**Status:** Accepted

## JdbcClient over Spring Data JPA
The original plan listed Spring Data JPA. The core of this project is `FOR UPDATE`, `ON CONFLICT DO NOTHING`, `SKIP LOCKED`, deferred triggers and exact statement ordering inside a transaction. With JPA these are hidden behind flush timing, the persistence context and dirty checking, and a lock that is easy to get subtly wrong becomes hard to see in review. Spring's `JdbcClient` keeps every statement visible. The trade-off is more mapping code, and no lazy-loaded relationships (which are not needed here).

## Testcontainers over H2
H2 does not implement PostgreSQL row-lock semantics, `ON CONFLICT` waiting behaviour, deferred constraint triggers, `SKIP LOCKED` or `FILTER` clauses. Tests against H2 would pass while proving nothing about the real system. Every integration test therefore runs against `postgres:16-alpine` via Testcontainers, with one shared container for all test classes.

## Java version
Java 21 LTS. Java 25 was considered; 21 was chosen because the whole toolchain (Spring Boot 3.5, Maven plugins, CI images) is long-proven on it.
