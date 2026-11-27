package com.ledgerguard.ledger;

/**
 * Side of a journal entry. DEBIT and CREDIT are NOT synonyms for increase/decrease:
 * whether an entry increases an account depends on that account's normal balance.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
