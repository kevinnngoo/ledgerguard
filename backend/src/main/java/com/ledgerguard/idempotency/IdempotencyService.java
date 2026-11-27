package com.ledgerguard.idempotency;

import com.ledgerguard.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Persistent idempotency for financial mutations.
 *
 * <p>Lifecycle (all inside the caller's database transaction):
 * <ol>
 *   <li>{@link #claim}: INSERT ... ON CONFLICT DO NOTHING on UNIQUE (client_id, idempotency_key).
 *       If another transaction holds the same key uncommitted, PostgreSQL makes this INSERT
 *       WAIT until that transaction finishes. Then:
 *       <ul>
 *         <li>the other committed: we see its row and replay its stored response;</li>
 *         <li>the other rolled back: our INSERT succeeds and we do the work ourselves.</li>
 *       </ul></li>
 *   <li>The caller performs the financial operation.</li>
 *   <li>{@link #complete} stores the response. It commits atomically with the financial effect.</li>
 * </ol>
 * There is no persisted "in progress" state to clean up: a key is either committed together with
 * its result, or it does not exist. A crash mid-request rolls back both.
 */
@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_\\-:.]{8,128}");

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public sealed interface Claim permits Fresh, Replay {}

    /** This request owns the key; {@code recordId} must be completed before commit. */
    public record Fresh(long recordId) implements Claim {}

    /** The same request already committed; return its original response. */
    public record Replay(int status, String body) implements Claim {}

    public static void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 8-128 characters of [A-Za-z0-9_-:.] (a UUID is recommended)");
        }
    }

    public Claim claim(String clientId, String key, String operation, String requestHash) {
        validateKey(key);
        Optional<Long> inserted = jdbc.sql("""
                        INSERT INTO idempotency_records (client_id, idempotency_key, operation, request_hash)
                        VALUES (:client, :key, :operation, :hash)
                        ON CONFLICT (client_id, idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("client", clientId)
                .param("key", key)
                .param("operation", operation)
                .param("hash", requestHash)
                .query(Long.class)
                .optional();
        if (inserted.isPresent()) {
            return new Fresh(inserted.get());
        }

        // Under READ COMMITTED this new statement sees the row the other transaction committed.
        StoredRecord existing = jdbc.sql("""
                        SELECT operation, request_hash, response_status, response_body::text AS response_body
                          FROM idempotency_records
                         WHERE client_id = :client AND idempotency_key = :key
                        """)
                .param("client", clientId)
                .param("key", key)
                .query((rs, n) -> new StoredRecord(rs.getString("operation"), rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body")))
                .single();

        if (!existing.operation().equals(operation) || !existing.requestHash().equals(requestHash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request. Use a new key for a new operation.");
        }
        if (existing.responseStatus() == null) {
            // Not reachable when records are completed in the same transaction as they are claimed.
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "A request with this Idempotency-Key is still in progress");
        }
        return new Replay(existing.responseStatus(), existing.responseBody());
    }

    public void complete(long recordId, int status, String responseBody, UUID transactionId) {
        jdbc.sql("""
                        UPDATE idempotency_records
                           SET response_status = :status, response_body = CAST(:body AS jsonb),
                               transaction_id = :tx, completed_at = now()
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("body", responseBody)
                .param("tx", transactionId)
                .param("id", recordId)
                .update();
    }

    /** SHA-256 over a canonical, field-ordered representation of the request. */
    public static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part == null ? "␀" : part.toString()).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record StoredRecord(String operation, String requestHash, Integer responseStatus, String responseBody) {}
}
