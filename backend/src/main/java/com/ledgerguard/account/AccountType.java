package com.ledgerguard.account;

import com.ledgerguard.ledger.Direction;

/**
 * Standard account classes and their normal balances.
 *
 * <p>LedgerGuard keeps the books from the institution's point of view: customer cash accounts
 * are LIABILITIES (money the institution owes its customers), so they increase with a CREDIT.
 * The simulated external clearing account is an ASSET, so it increases with a DEBIT.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    public Direction normalBalance() {
        return normalBalance;
    }
}
