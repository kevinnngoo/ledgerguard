package com.ledgerguard.ledger;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record JournalEntry(
        long id,
        UUID transactionId,
        UUID accountId,
        Direction direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        Instant postedAt,
        String description) {}
