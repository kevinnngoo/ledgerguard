package com.ledgerguard.ledger;

import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.PageResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL for the ledger. Locking and conflict semantics are the point of this project,
 * so they are written out rather than hidden behind an ORM.
 */
@Repository
public class LedgerRepository {

    static final RowMapper<JournalEntry> ENTRY_MAPPER = (rs, n) -> new JournalEntry(
            rs.getLong("id"),
            rs.getObject("transaction_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            Direction.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getObject("posted_at", OffsetDateTime.class).toInstant(),
            rs.getString("description"));

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the balance rows of every account a posting touches.
     *
     * <p>ORDER BY account_id makes every transaction acquire row locks in the same global order.
     * Two transfers between the same accounts in opposite directions (A->B and B->A) therefore
     * both lock the lower id first, so they queue instead of deadlocking.
     */
    public Map<UUID, LockedBalance> lockBalances(Collection<UUID> accountIds) {
        List<LockedBalance> rows = jdbc.sql("""
                        SELECT b.account_id, b.balance, b.allow_negative, a.normal_balance
                          FROM account_balances b
                          JOIN accounts a ON a.id = b.account_id
                         WHERE b.account_id IN (:ids)
                         ORDER BY b.account_id
                           FOR UPDATE OF b
                        """)
                .param("ids", accountIds)
                .query((rs, n) -> new LockedBalance(
                        rs.getObject("account_id", UUID.class),
                        rs.getBigDecimal("balance"),
                        rs.getBoolean("allow_negative"),
                        Direction.valueOf(rs.getString("normal_balance"))))
                .list();
        if (rows.size() != new HashSet<>(accountIds).size()) {
            throw ApiException.notFound("One or more accounts do not exist");
        }
        Map<UUID, LockedBalance> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.accountId(), r));
        return byId;
    }

    public void insertEntry(UUID transactionId, PostingLeg leg, String currency, String description) {
        jdbc.sql("""
                        INSERT INTO journal_entries (transaction_id, account_id, direction, amount, currency, description)
                        VALUES (:tx, :account, :direction, :amount, :currency, :description)
                        """)
                .param("tx", transactionId)
                .param("account", leg.accountId())
                .param("direction", leg.direction().name())
                .param("amount", leg.amount())
                .param("currency", currency)
                .param("description", description)
                .update();
    }

    public void applyDelta(UUID accountId, BigDecimal delta, int entries) {
        jdbc.sql("""
                        UPDATE account_balances
                           SET balance = balance + :delta,
                               entry_count = entry_count + :entries,
                               updated_at = now()
                         WHERE account_id = :id
                        """)
                .param("delta", delta)
                .param("entries", entries)
                .param("id", accountId)
                .update();
    }

    public List<JournalEntry> entriesForTransaction(UUID transactionId) {
        return jdbc.sql("SELECT * FROM journal_entries WHERE transaction_id = :tx ORDER BY id")
                .param("tx", transactionId)
                .query(ENTRY_MAPPER)
                .list();
    }

    public PageResponse<JournalEntry> entries(UUID accountId, int page, int size) {
        String where = accountId == null ? "" : " WHERE account_id = :account";
        var count = jdbc.sql("SELECT count(*) FROM journal_entries" + where);
        var select = jdbc.sql("SELECT * FROM journal_entries" + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset");
        if (accountId != null) {
            count = count.param("account", accountId);
            select = select.param("account", accountId);
        }
        long total = count.query(Long.class).single();
        List<JournalEntry> items = select
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(ENTRY_MAPPER)
                .list();
        return PageResponse.of(items, page, size, total);
    }

    public BigDecimal projectedBalance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM account_balances WHERE account_id = :id")
                .param("id", accountId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Account " + accountId + " not found"));
    }

    /** Recomputes a balance from the authoritative ledger, independent of the projection. */
    public BigDecimal ledgerDerivedBalance(UUID accountId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN e.direction = a.normal_balance THEN e.amount ELSE -e.amount END), 0)
                          FROM accounts a
                          LEFT JOIN journal_entries e ON e.account_id = a.id
                         WHERE a.id = :id
                        """)
                .param("id", accountId)
                .query(BigDecimal.class)
                .single();
    }
}
