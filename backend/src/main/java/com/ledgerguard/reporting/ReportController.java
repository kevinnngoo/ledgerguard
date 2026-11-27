package com.ledgerguard.reporting;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ledgerguard.outbox.OutboxPublisher;
import com.ledgerguard.transaction.TransactionRecord;
import com.ledgerguard.transaction.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational figures calculated from the database at request time. No metric here is invented. */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {

    private final JdbcClient jdbc;
    private final TransactionRepository transactions;
    private final OutboxPublisher outbox;

    public ReportController(JdbcClient jdbc, TransactionRepository transactions, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    public record DailyVolume(LocalDate day, long posted, long rejected,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal postedValue) {}

    public record Overview(
            long customerAccounts,
            long simulationAccounts,
            long postedTransactions,
            long rejectedTransactions,
            long reversedTransactions,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal postedValue,
            long pendingOutboxEvents,
            List<DailyVolume> last14Days,
            List<TransactionRecord> recentTransactions) {}

    @GetMapping("/overview")
    @Operation(summary = "Dashboard overview computed from current data")
    public Overview overview() {
        record Counts(long customer, long simulation) {}
        Counts accounts = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE NOT system_account AND NOT simulation) AS customer,
                               count(*) FILTER (WHERE simulation) AS simulation
                          FROM accounts
                        """)
                .query((rs, n) -> new Counts(rs.getLong("customer"), rs.getLong("simulation")))
                .single();

        record TxCounts(long posted, long rejected, long reversed, BigDecimal value) {}
        TxCounts tx = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'POSTED') AS posted,
                               count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                               count(*) FILTER (WHERE status = 'REVERSED') AS reversed,
                               COALESCE(SUM(amount) FILTER (WHERE status IN ('POSTED', 'REVERSED') AND type <> 'REVERSAL'), 0) AS value
                          FROM transactions
                        """)
                .query((rs, n) -> new TxCounts(rs.getLong("posted"), rs.getLong("rejected"), rs.getLong("reversed"),
                        rs.getBigDecimal("value")))
                .single();

        List<DailyVolume> daily = jdbc.sql("""
                        SELECT d::date AS day,
                               count(t.id) FILTER (WHERE t.status IN ('POSTED', 'REVERSED')) AS posted,
                               count(t.id) FILTER (WHERE t.status = 'REJECTED') AS rejected,
                               COALESCE(SUM(t.amount) FILTER (WHERE t.status IN ('POSTED', 'REVERSED')), 0) AS value
                          FROM generate_series(current_date - 13, current_date, interval '1 day') AS d
                          LEFT JOIN transactions t ON t.created_at >= d AND t.created_at < d + interval '1 day'
                         GROUP BY d
                         ORDER BY d
                        """)
                .query((rs, n) -> new DailyVolume(rs.getObject("day", LocalDate.class), rs.getLong("posted"),
                        rs.getLong("rejected"), rs.getBigDecimal("value")))
                .list();

        List<TransactionRecord> recent = transactions.page(
                new TransactionRepository.Filter(null, null, null, null, null, null), 0, 10).items();

        return new Overview(accounts.customer(), accounts.simulation(), tx.posted(), tx.rejected(), tx.reversed(),
                tx.value(), outbox.pendingCount(), daily, recent);
    }
}
