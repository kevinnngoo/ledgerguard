package com.ledgerguard.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingRulesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void balancedJournalIsAccepted() {
        assertThatCode(() -> PostingEngine.requireBalanced(List.of(
                PostingLeg.debit(A, new BigDecimal("250.00")),
                PostingLeg.credit(B, new BigDecimal("250.00"))))).doesNotThrowAnyException();
    }

    @Test
    void multiLegBalancedJournalIsAccepted() {
        assertThatCode(() -> PostingEngine.requireBalanced(List.of(
                PostingLeg.debit(A, new BigDecimal("100.00")),
                PostingLeg.credit(B, new BigDecimal("60.00")),
                PostingLeg.credit(B, new BigDecimal("40.00"))))).doesNotThrowAnyException();
    }

    @Test
    void unbalancedJournalIsRejected() {
        assertThatThrownBy(() -> PostingEngine.requireBalanced(List.of(
                PostingLeg.debit(A, new BigDecimal("250.00")),
                PostingLeg.credit(B, new BigDecimal("249.99")))))
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void singleLegIsRejected() {
        assertThatThrownBy(() -> PostingEngine.requireBalanced(List.of(PostingLeg.debit(A, BigDecimal.ONE))))
                .hasMessageContaining("at least two");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> PostingEngine.requireBalanced(List.of(
                PostingLeg.debit(A, BigDecimal.ZERO),
                PostingLeg.credit(B, BigDecimal.ZERO))))
                .hasMessageContaining("positive");
    }

    @Test
    void debitAndCreditMeaningDependsOnNormalBalance() {
        LockedBalance liability = new LockedBalance(A, new BigDecimal("100.00"), false, Direction.CREDIT);
        LockedBalance asset = new LockedBalance(B, new BigDecimal("100.00"), false, Direction.DEBIT);
        BigDecimal ten = new BigDecimal("10.00");

        // A credit INCREASES a liability (customer balance) but DECREASES an asset.
        assertThat(liability.deltaFor(PostingLeg.credit(A, ten))).isEqualByComparingTo("10.00");
        assertThat(liability.deltaFor(PostingLeg.debit(A, ten))).isEqualByComparingTo("-10.00");
        assertThat(asset.deltaFor(PostingLeg.debit(B, ten))).isEqualByComparingTo("10.00");
        assertThat(asset.deltaFor(PostingLeg.credit(B, ten))).isEqualByComparingTo("-10.00");
    }
}
