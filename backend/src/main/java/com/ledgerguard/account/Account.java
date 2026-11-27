package com.ledgerguard.account;

import com.ledgerguard.ledger.Direction;
import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,
        String accountNumber,
        String name,
        AccountType type,
        Direction normalBalance,
        String currency,
        AccountStatus status,
        boolean allowNegative,
        boolean systemAccount,
        boolean simulation,
        String ownerReference,
        Instant createdAt,
        Instant updatedAt) {

    /** The seeded simulated external clearing account (see V2 migration). */
    public static final UUID CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
}
