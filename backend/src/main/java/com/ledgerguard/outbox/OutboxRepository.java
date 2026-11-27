package com.ledgerguard.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.common.CorrelationIdFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Transactional outbox: events are written to a table in the SAME database transaction as the
 * state change they describe. If the transaction commits, the event exists; if it rolls back,
 * the event never existed. A separate publisher delivers them later (at-least-once).
 */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OutboxRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID append(String aggregateType, UUID aggregateId, String eventType, Map<String, ?> data) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", CorrelationIdFilter.current());
        envelope.put("data", data);
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", toJson(envelope))
                .update();
        return eventId;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox payload", e);
        }
    }
}
