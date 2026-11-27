package com.ledgerguard.transaction;

import com.ledgerguard.common.CorrelationIdFilter;
import com.ledgerguard.common.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    static final RowMapper<TransactionRecord> MAPPER = (rs, n) -> {
        OffsetDateTime posted = rs.getObject("posted_at", OffsetDateTime.class);
        return new TransactionRecord(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                TransactionType.valueOf(rs.getString("type")),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getString("description"),
                rs.getString("rejection_code"),
                rs.getString("rejection_reason"),
                rs.getObject("reverses_transaction_id", UUID.class),
                rs.getString("initiated_by"),
                rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                posted == null ? null : posted.toInstant());
    };

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record NewTransaction(TransactionType type, TransactionStatus status, BigDecimal amount, String currency,
                                 UUID sourceAccountId, UUID destinationAccountId, String description,
                                 String rejectionCode, String rejectionReason, UUID reversesTransactionId,
                                 String initiatedBy) {}

    public TransactionRecord insert(NewTransaction t) {
        UUID id = UUID.randomUUID();
        long sequence = jdbc.sql("SELECT nextval('transaction_reference_seq')").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO transactions (id, reference, type, status, amount, currency, source_account_id,
                                                  destination_account_id, description, rejection_code, rejection_reason,
                                                  reverses_transaction_id, initiated_by, correlation_id, posted_at)
                        VALUES (:id, :reference, :type, :status, :amount, :currency, :source, :destination, :description,
                                :rejectionCode, :rejectionReason, :reverses, :initiatedBy, :correlation,
                                CASE WHEN :status = 'REJECTED' THEN NULL ELSE now() END)
                        """)
                .param("id", id)
                .param("reference", "TX-" + sequence)
                .param("type", t.type().name())
                .param("status", t.status().name())
                .param("amount", t.amount())
                .param("currency", t.currency())
                .param("source", t.sourceAccountId())
                .param("destination", t.destinationAccountId())
                .param("description", t.description())
                .param("rejectionCode", t.rejectionCode())
                .param("rejectionReason", t.rejectionReason())
                .param("reverses", t.reversesTransactionId())
                .param("initiatedBy", t.initiatedBy())
                .param("correlation", CorrelationIdFilter.current())
                .update();
        return findById(id).orElseThrow();
    }

    public Optional<TransactionRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transactions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Locks the transaction row so two concurrent reversals of it are serialized. */
    public Optional<TransactionRecord> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM transactions WHERE id = :id FOR UPDATE").param("id", id).query(MAPPER).optional();
    }

    public Optional<TransactionRecord> findReversalOf(UUID originalId) {
        return jdbc.sql("SELECT * FROM transactions WHERE reverses_transaction_id = :id")
                .param("id", originalId).query(MAPPER).optional();
    }

    public void markReversed(UUID id) {
        jdbc.sql("UPDATE transactions SET status = 'REVERSED' WHERE id = :id AND status = 'POSTED'")
                .param("id", id)
                .update();
    }

    /** Posted outgoing value from an account over the last 24 hours (withdrawals and outgoing transfers). */
    public BigDecimal outgoingInLast24Hours(UUID accountId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0)
                          FROM transactions
                         WHERE source_account_id = :id
                           AND type IN ('WITHDRAWAL', 'TRANSFER')
                           AND status = 'POSTED'
                           AND posted_at >= now() - interval '24 hours'
                        """)
                .param("id", accountId)
                .query(BigDecimal.class)
                .single();
    }

    public record Filter(TransactionStatus status, TransactionType type, UUID accountId, Instant from, Instant to,
                         String reference) {}

    public PageResponse<TransactionRecord> page(Filter f, int page, int size) {
        List<String> conditions = new ArrayList<>();
        if (f.status() != null) {
            conditions.add("status = :status");
        }
        if (f.type() != null) {
            conditions.add("type = :type");
        }
        if (f.accountId() != null) {
            conditions.add("(source_account_id = :account OR destination_account_id = :account)");
        }
        if (f.from() != null) {
            conditions.add("created_at >= :from");
        }
        if (f.to() != null) {
            conditions.add("created_at < :to");
        }
        if (f.reference() != null && !f.reference().isBlank()) {
            conditions.add("reference = :reference");
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        var count = jdbc.sql("SELECT count(*) FROM transactions" + where);
        var select = jdbc.sql("SELECT * FROM transactions" + where + " ORDER BY created_at DESC, reference DESC LIMIT :limit OFFSET :offset");
        if (f.status() != null) {
            count = count.param("status", f.status().name());
            select = select.param("status", f.status().name());
        }
        if (f.type() != null) {
            count = count.param("type", f.type().name());
            select = select.param("type", f.type().name());
        }
        if (f.accountId() != null) {
            count = count.param("account", f.accountId());
            select = select.param("account", f.accountId());
        }
        if (f.from() != null) {
            OffsetDateTime from = f.from().atOffset(ZoneOffset.UTC);
            count = count.param("from", from);
            select = select.param("from", from);
        }
        if (f.to() != null) {
            OffsetDateTime to = f.to().atOffset(ZoneOffset.UTC);
            count = count.param("to", to);
            select = select.param("to", to);
        }
        if (f.reference() != null && !f.reference().isBlank()) {
            count = count.param("reference", f.reference().trim());
            select = select.param("reference", f.reference().trim());
        }
        long total = count.query(Long.class).single();
        List<TransactionRecord> items = select.param("limit", size).param("offset", (long) page * size)
                .query(MAPPER).list();
        return PageResponse.of(items, page, size, total);
    }
}
