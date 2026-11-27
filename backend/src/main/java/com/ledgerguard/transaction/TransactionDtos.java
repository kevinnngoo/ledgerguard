package com.ledgerguard.transaction;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ledgerguard.ledger.JournalEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransactionDtos {

    private TransactionDtos() {}

    public record DepositRequest(
            @NotNull UUID accountId,
            @NotNull BigDecimal amount,
            @Size(max = 3) String currency,
            @Size(max = 255) String description) {}

    public record WithdrawalRequest(
            @NotNull UUID accountId,
            @NotNull BigDecimal amount,
            @Size(max = 3) String currency,
            @Size(max = 255) String description) {}

    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @NotNull BigDecimal amount,
            @Size(max = 3) String currency,
            @Size(max = 255) String description) {}

    public record ReversalRequest(@NotBlank @Size(max = 255) String reason) {}

    public record TransactionResponse(
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
            Instant postedAt,
            boolean balanced,
            List<JournalEntry> entries) {

        public static TransactionResponse of(TransactionRecord t, List<JournalEntry> entries) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (JournalEntry e : entries) {
                if (e.direction() == com.ledgerguard.ledger.Direction.DEBIT) {
                    debits = debits.add(e.amount());
                } else {
                    credits = credits.add(e.amount());
                }
            }
            boolean balanced = debits.compareTo(credits) == 0;
            return new TransactionResponse(t.id(), t.reference(), t.type(), t.status(), t.amount(), t.currency(),
                    t.sourceAccountId(), t.destinationAccountId(), t.description(), t.rejectionCode(),
                    t.rejectionReason(), t.reversesTransactionId(), t.initiatedBy(), t.correlationId(),
                    t.createdAt(), t.postedAt(), balanced, entries);
        }
    }

    /**
     * Outcome of a financial mutation. {@code replayed} is true when the response was returned
     * from the idempotency store rather than produced by executing the operation again.
     */
    public record OperationResult(int httpStatus, TransactionResponse body, boolean replayed) {}
}
