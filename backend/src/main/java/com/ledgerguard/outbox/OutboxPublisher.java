package com.ledgerguard.outbox;

import com.ledgerguard.common.LedgerGuardProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers pending outbox events.
 *
 * <ul>
 *   <li>FOR UPDATE SKIP LOCKED lets several publisher instances run concurrently: each claims a
 *       disjoint batch instead of blocking on, or double-processing, the same rows.</li>
 *   <li>Failures are recorded with exponential backoff (2^attempts seconds, capped).</li>
 *   <li>Delivery is at-least-once: if publish succeeds but the commit fails, the event is sent
 *       again later. Consumers must de-duplicate by event ID.</li>
 * </ul>
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcClient jdbc;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final LedgerGuardProperties.Outbox config;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(JdbcClient jdbc, EventPublisher publisher, PlatformTransactionManager tm,
                           LedgerGuardProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(tm);
        this.config = properties.outbox();
        this.published = Counter.builder("ledgerguard.outbox.published").register(registry);
        this.failed = Counter.builder("ledgerguard.outbox.publish.failures").register(registry);
        Gauge.builder("ledgerguard.outbox.backlog", this, OutboxPublisher::pendingCount)
                .description("Unpublished outbox events")
                .register(registry);
    }

    public record PublishReport(int published, int failed) {}

    public PublishReport publishPending() {
        return publish(null);
    }

    /** Publishes only one aggregate's eligible events (used by the Simulation Lab for a deterministic demo). */
    public PublishReport publishPendingFor(UUID aggregateId) {
        return publish(aggregateId);
    }

    private PublishReport publish(UUID aggregateId) {
        String filter = aggregateId == null ? "" : " AND aggregate_id = :aggregate";
        return tx.execute(status -> {
            var query = jdbc.sql(
                            "SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts"
                                    + " FROM outbox_events"
                                    + " WHERE published_at IS NULL AND next_attempt_at <= now()" + filter
                                    + " ORDER BY created_at"
                                    + " LIMIT :limit"
                                    + " FOR UPDATE SKIP LOCKED")
                    .param("limit", config.batchSize());
            if (aggregateId != null) {
                query = query.param("aggregate", aggregateId);
            }
            List<OutboxEvent> batch = query
                    .query((rs, n) -> new OutboxEvent(
                            rs.getObject("id", UUID.class),
                            rs.getString("aggregate_type"),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload"),
                            rs.getInt("attempts")))
                    .list();
            int ok = 0;
            int failures = 0;
            for (OutboxEvent event : batch) {
                try {
                    publisher.publish(event);
                    jdbc.sql("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = :id")
                            .param("id", event.id())
                            .update();
                    ok++;
                } catch (RuntimeException e) {
                    long backoff = Math.min(config.maxBackoffSeconds(), 1L << Math.min(event.attempts(), 20));
                    jdbc.sql("""
                                    UPDATE outbox_events
                                       SET attempts = attempts + 1,
                                           next_attempt_at = now() + (:backoff * interval '1 second'),
                                           last_error = :error
                                     WHERE id = :id
                                    """)
                            .param("backoff", backoff)
                            .param("error", truncate(e.getMessage()))
                            .param("id", event.id())
                            .update();
                    failures++;
                    log.warn("Outbox publish failed for event {} (attempt {}), retrying in {}s: {}",
                            event.id(), event.attempts() + 1, backoff, e.getMessage());
                }
            }
            published.increment(ok);
            failed.increment(failures);
            return new PublishReport(ok, failures);
        });
    }

    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }

    public record EventStatus(UUID id, String eventType, int attempts, boolean published, String lastError) {}

    public List<EventStatus> eventsForAggregate(UUID aggregateId) {
        return jdbc.sql("""
                        SELECT id, event_type, attempts, published_at IS NOT NULL AS published, last_error
                          FROM outbox_events WHERE aggregate_id = :id ORDER BY created_at
                        """)
                .param("id", aggregateId)
                .query((rs, n) -> new EventStatus(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getInt("attempts"), rs.getBoolean("published"), rs.getString("last_error")))
                .list();
    }

    /** Makes events eligible immediately (used by the event-recovery simulation after an outage ends). */
    public void retryNow(UUID aggregateId) {
        jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() WHERE aggregate_id = :id AND published_at IS NULL")
                .param("id", aggregateId)
                .update();
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
