package com.ledgerguard.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountView(
        UUID id,
        String accountNumber,
        String name,
        AccountType type,
        String normalBalance,
        String currency,
        AccountStatus status,
        boolean systemAccount,
        boolean simulation,
        String ownerReference,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountView of(Account a, BigDecimal balance) {
        return new AccountView(a.id(), a.accountNumber(), a.name(), a.type(), a.normalBalance().name(),
                a.currency(), a.status(), a.systemAccount(), a.simulation(), a.ownerReference(),
                balance, a.createdAt(), a.updatedAt());
    }
}
