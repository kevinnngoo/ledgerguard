package com.ledgerguard.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A balance projection row read with SELECT ... FOR UPDATE. While the enclosing database
 * transaction is open, no other transaction can change this balance.
 */
public record LockedBalance(UUID accountId, BigDecimal balance, boolean allowNegative, Direction normalBalance) {

    /** Signed effect of a leg on this account, in the account's normal-balance terms. */
    public BigDecimal deltaFor(PostingLeg leg) {
        return leg.direction() == normalBalance ? leg.amount() : leg.amount().negate();
    }
}
