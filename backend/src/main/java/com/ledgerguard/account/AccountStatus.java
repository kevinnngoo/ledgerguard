package com.ledgerguard.account;

import java.util.Set;

/**
 * Account lifecycle:
 * <pre>
 *   ACTIVE <-> FROZEN
 *   ACTIVE  -> CLOSED   (only with a zero balance)
 *   FROZEN  -> CLOSED   (only with a zero balance)
 *   CLOSED is terminal.
 * </pre>
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED;

    public boolean canTransitionTo(AccountStatus target) {
        return switch (this) {
            case ACTIVE -> Set.of(FROZEN, CLOSED).contains(target);
            case FROZEN -> Set.of(ACTIVE, CLOSED).contains(target);
            case CLOSED -> false;
        };
    }
}
