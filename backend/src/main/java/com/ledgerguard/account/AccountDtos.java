package com.ledgerguard.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {}

    /** Creates a simulated customer cash account (a LIABILITY on the institution's books). */
    public record CreateAccountRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 3) String currency,
            @Size(max = 64) String ownerReference) {}

    public record StatusChangeRequest(
            @NotNull AccountStatus status,
            @NotBlank @Size(max = 255) String reason) {}

    /**
     * Both numbers come from one consistent database snapshot. {@code consistent} is false only if
     * the projection has diverged from the authoritative ledger.
     */
    public record BalanceView(
            UUID accountId,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal projectedBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ledgerBalance,
            boolean consistent,
            Instant asOf) {}
}
