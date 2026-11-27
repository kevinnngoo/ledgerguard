package com.ledgerguard.ledger;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.Maps;
import com.ledgerguard.common.TransactionRunner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Internal ledger reconciliation: verifies every ledger invariant from the authoritative entries.
 *
 * <p>All checks run in ONE REPEATABLE READ snapshot, so a posting that commits mid-check cannot
 * make the report show a misleading discrepancy (the "consistent cutoff" requirement).
 */
@Service
public class IntegrityService {

    private static final int LIMIT = 100;

    private final JdbcClient jdbc;
    private final TransactionRunner runner;
    private final AuditService audit;

    public IntegrityService(JdbcClient jdbc, TransactionRunner runner, AuditService audit) {
        this.jdbc = jdbc;
        this.runner = runner;
        this.audit = audit;
    }

    public record ProjectionMismatch(UUID accountId,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal projectedBalance,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ledgerBalance) {}

    public record IntegrityReport(
            Instant checkedAt,
            boolean healthy,
            long journalTransactions,
            long journalEntries,
            long accountsChecked,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebits,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCredits,
            List<UUID> unbalancedTransactions,
            List<UUID> postedWithoutEntries,
            List<ProjectionMismatch> projectionMismatches,
            List<UUID> unauthorizedNegativeBalances,
            List<UUID> duplicateIdempotentEffects) {}

    public IntegrityReport check() {
        return runner.inSnapshot(this::checkInCurrentTransaction);
    }

    private IntegrityReport checkInCurrentTransaction() {
        List<UUID> unbalanced = jdbc.sql("""
                        SELECT transaction_id
                          FROM journal_entries
                         GROUP BY transaction_id, currency
                        HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                         LIMIT :limit
                        """).param("limit", LIMIT).query(UUID.class).list();

        List<UUID> withoutEntries = jdbc.sql("""
                        SELECT t.id
                          FROM transactions t
                         WHERE t.status IN ('POSTED', 'REVERSED')
                           AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.transaction_id = t.id)
                         LIMIT :limit
                        """).param("limit", LIMIT).query(UUID.class).list();

        List<ProjectionMismatch> mismatches = jdbc.sql("""
                        SELECT b.account_id, b.balance,
                               COALESCE(SUM(CASE WHEN e.direction = a.normal_balance THEN e.amount ELSE -e.amount END), 0) AS ledger
                          FROM account_balances b
                          JOIN accounts a ON a.id = b.account_id
                          LEFT JOIN journal_entries e ON e.account_id = b.account_id
                         GROUP BY b.account_id, b.balance
                        HAVING b.balance <> COALESCE(SUM(CASE WHEN e.direction = a.normal_balance THEN e.amount ELSE -e.amount END), 0)
                         LIMIT :limit
                        """).param("limit", LIMIT)
                .query((rs, n) -> new ProjectionMismatch(rs.getObject("account_id", UUID.class),
                        rs.getBigDecimal("balance"), rs.getBigDecimal("ledger")))
                .list();

        List<UUID> negatives = jdbc.sql("""
                        SELECT account_id FROM account_balances WHERE NOT allow_negative AND balance < 0 LIMIT :limit
                        """).param("limit", LIMIT).query(UUID.class).list();

        List<UUID> duplicates = jdbc.sql("""
                        SELECT transaction_id
                          FROM idempotency_records
                         WHERE transaction_id IS NOT NULL
                         GROUP BY transaction_id
                        HAVING count(*) > 1
                         LIMIT :limit
                        """).param("limit", LIMIT).query(UUID.class).list();

        record Totals(long transactions, long entries, BigDecimal debits, BigDecimal credits) {}
        Totals totals = jdbc.sql("""
                        SELECT count(DISTINCT transaction_id) AS transactions,
                               count(*) AS entries,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0) AS debits,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
                          FROM journal_entries
                        """)
                .query((rs, n) -> new Totals(rs.getLong("transactions"), rs.getLong("entries"),
                        rs.getBigDecimal("debits"), rs.getBigDecimal("credits")))
                .single();
        long accountsChecked = jdbc.sql("SELECT count(*) FROM account_balances").query(Long.class).single();

        boolean healthy = unbalanced.isEmpty() && withoutEntries.isEmpty() && mismatches.isEmpty()
                && negatives.isEmpty() && duplicates.isEmpty() && totals.debits().compareTo(totals.credits()) == 0;
        return new IntegrityReport(Instant.now(), healthy, totals.transactions(), totals.entries(), accountsChecked,
                totals.debits(), totals.credits(), unbalanced, withoutEntries, mismatches, negatives, duplicates);
    }

    public record RebuildResult(int accountsCorrected, List<ProjectionMismatch> corrected) {}

    /**
     * Rebuilds balance projections from the authoritative ledger. Locks every balance row
     * (in account_id order) so no posting can interleave with the rebuild.
     */
    public RebuildResult rebuildProjections(String actor) {
        return runner.inTransaction(() -> {
            jdbc.sql("SELECT account_id FROM account_balances ORDER BY account_id FOR UPDATE").query(UUID.class).list();
            List<ProjectionMismatch> drifted = checkInCurrentTransaction().projectionMismatches();
            for (ProjectionMismatch m : drifted) {
                jdbc.sql("UPDATE account_balances SET balance = :ledger, updated_at = now() WHERE account_id = :id")
                        .param("ledger", m.ledgerBalance())
                        .param("id", m.accountId())
                        .update();
            }
            audit.record(actor, "BALANCE_PROJECTIONS_REBUILT", "Ledger", null,
                    Maps.of("accountsCorrected", drifted.size()));
            return new RebuildResult(drifted.size(), drifted);
        });
    }
}
