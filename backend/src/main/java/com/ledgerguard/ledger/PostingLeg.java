package com.ledgerguard.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** One side of a journal transaction before it is written: account, direction, positive amount. */
public record PostingLeg(UUID accountId, Direction direction, BigDecimal amount) {

    public static PostingLeg debit(UUID accountId, BigDecimal amount) {
        return new PostingLeg(accountId, Direction.DEBIT, amount);
    }

    public static PostingLeg credit(UUID accountId, BigDecimal amount) {
        return new PostingLeg(accountId, Direction.CREDIT, amount);
    }
}
