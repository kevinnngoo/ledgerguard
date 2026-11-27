package com.ledgerguard.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.ledger.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "ACTIVE, FROZEN, true",
            "ACTIVE, CLOSED, true",
            "ACTIVE, ACTIVE, false",
            "FROZEN, ACTIVE, true",
            "FROZEN, CLOSED, true",
            "FROZEN, FROZEN, false",
            "CLOSED, ACTIVE, false",
            "CLOSED, FROZEN, false",
            "CLOSED, CLOSED, false"
    })
    void enforcesLifecycle(AccountStatus from, AccountStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void normalBalancesFollowAccountingConvention() {
        assertThat(AccountType.ASSET.normalBalance()).isEqualTo(Direction.DEBIT);
        assertThat(AccountType.EXPENSE.normalBalance()).isEqualTo(Direction.DEBIT);
        assertThat(AccountType.LIABILITY.normalBalance()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.EQUITY.normalBalance()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.REVENUE.normalBalance()).isEqualTo(Direction.CREDIT);
    }
}
