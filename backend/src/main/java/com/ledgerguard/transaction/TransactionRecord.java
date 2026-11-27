package com.ledgerguard.transaction;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionRecord(
        UUID id,
        String reference,
        TransactionType type,
        TransactionStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String description,
        String rejectionCode,
        String rejectionReason,
        UUID reversesTransactionId,
        String initiatedBy,
        String correlationId,
        Instant createdAt,
        Instant postedAt) {}
