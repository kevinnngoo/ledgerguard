package com.ledgerguard.account;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.ledger.Direction;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("account_number"),
            rs.getString("name"),
            AccountType.valueOf(rs.getString("account_type")),
            Direction.valueOf(rs.getString("normal_balance")),
            rs.getString("currency"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getBoolean("allow_negative"),
            rs.getBoolean("system_account"),
            rs.getBoolean("simulation"),
            rs.getString("owner_reference"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the account and its zero balance projection. Call inside a transaction. */
    public Account insert(String name, AccountType type, String currency, boolean simulation, String ownerReference) {
        UUID id = UUID.randomUUID();
        long sequence = jdbc.sql("SELECT nextval('account_number_seq')").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO accounts (id, account_number, name, account_type, normal_balance, currency,
                                              status, allow_negative, system_account, simulation, owner_reference)
                        VALUES (:id, :number, :name, :type, :normal, :currency, 'ACTIVE', FALSE, FALSE, :simulation, :owner)
                        """)
                .param("id", id)
                .param("number", (simulation ? "SIM-" : "LG-") + sequence)
                .param("name", name)
                .param("type", type.name())
                .param("normal", type.normalBalance().name())
                .param("currency", currency)
                .param("simulation", simulation)
                .param("owner", ownerReference)
                .update();
        jdbc.sql("INSERT INTO account_balances (account_id, balance, allow_negative) VALUES (:id, 0, FALSE)")
                .param("id", id)
                .update();
        return findById(id).orElseThrow();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public void updateStatus(UUID id, AccountStatus status) {
        jdbc.sql("UPDATE accounts SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", status.name())
                .param("id", id)
                .update();
    }

    public PageResponse<AccountView> page(AccountStatus status, boolean includeSimulation, String search, int page, int size) {
        List<String> conditions = new ArrayList<>();
        if (status != null) {
            conditions.add("a.status = :status");
        }
        if (!includeSimulation) {
            conditions.add("a.simulation = FALSE");
        }
        if (search != null && !search.isBlank()) {
            conditions.add("(a.name ILIKE :search OR a.account_number ILIKE :search)");
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        var count = jdbc.sql("SELECT count(*) FROM accounts a" + where);
        var select = jdbc.sql("""
                SELECT a.*, b.balance
                  FROM accounts a
                  JOIN account_balances b ON b.account_id = a.id
                """ + where + " ORDER BY a.system_account DESC, a.created_at DESC LIMIT :limit OFFSET :offset");
        if (status != null) {
            count = count.param("status", status.name());
            select = select.param("status", status.name());
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            count = count.param("search", pattern);
            select = select.param("search", pattern);
        }
        long total = count.query(Long.class).single();
        List<AccountView> items = select
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, n) -> AccountView.of(MAPPER.mapRow(rs, n), rs.getBigDecimal("balance")))
                .list();
        return PageResponse.of(items, page, size, total);
    }
}
