package com.ledgerguard.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.common.CorrelationIdFilter;
import com.ledgerguard.common.PageResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail. Rows are written in the same database transaction as the action they
 * describe, so an audited action and its audit record commit (or roll back) together.
 *
 * <p>Limits: a database trigger blocks UPDATE/DELETE through ordinary application access, but a
 * database superuser could still alter rows. This is NOT tamper-proof storage.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record AuditEvent(long id, String actor, String action, String resourceType, String resourceId,
                             String correlationId, String details, Instant createdAt) {}

    public void record(String actor, String action, String resourceType, String resourceId, Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO audit_events (actor, action, resource_type, resource_id, correlation_id, details)
                        VALUES (:actor, :action, :type, :resourceId, :correlation, CAST(:details AS jsonb))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("type", resourceType)
                .param("resourceId", resourceId)
                .param("correlation", CorrelationIdFilter.current())
                .param("details", toJson(details))
                .update();
    }

    public PageResponse<AuditEvent> list(String resourceType, String resourceId, int page, int size) {
        List<String> conditions = new ArrayList<>();
        if (resourceType != null && !resourceType.isBlank()) {
            conditions.add("resource_type = :type");
        }
        if (resourceId != null && !resourceId.isBlank()) {
            conditions.add("resource_id = :resourceId");
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        var count = jdbc.sql("SELECT count(*) FROM audit_events" + where);
        var select = jdbc.sql("SELECT id, actor, action, resource_type, resource_id, correlation_id, details::text AS details, created_at"
                + " FROM audit_events" + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset");
        if (resourceType != null && !resourceType.isBlank()) {
            count = count.param("type", resourceType);
            select = select.param("type", resourceType);
        }
        if (resourceId != null && !resourceId.isBlank()) {
            count = count.param("resourceId", resourceId);
            select = select.param("resourceId", resourceId);
        }
        long total = count.query(Long.class).single();
        List<AuditEvent> items = select.param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new AuditEvent(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("correlation_id"),
                        rs.getString("details"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return PageResponse.of(items, page, size, total);
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize audit details", e);
        }
    }
}
