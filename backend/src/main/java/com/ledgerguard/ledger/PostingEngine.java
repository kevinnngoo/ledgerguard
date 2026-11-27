package com.ledgerguard.ledger;

import com.ledgerguard.simulation.FailureInjector;
import com.ledgerguard.simulation.FailureInjector.FailurePoint;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a balanced journal transaction and updates balance projections.
 *
 * <p>Must be called inside a database transaction that already holds row locks on every
 * affected balance (see {@link LedgerRepository#lockBalances}). Entries and projection updates
 * commit or roll back together; the deferred database trigger re-checks balance at COMMIT.
 */
@Component
public class PostingEngine {

    private final LedgerRepository ledger;
    private final FailureInjector failureInjector;

    public PostingEngine(LedgerRepository ledger, FailureInjector failureInjector) {
        this.ledger = ledger;
        this.failureInjector = failureInjector;
    }

    public void post(UUID transactionId, String currency, List<PostingLeg> legs,
                     Map<UUID, LockedBalance> locked, String description) {
        requireBalanced(legs);

        Map<UUID, BigDecimal> deltas = new LinkedHashMap<>();
        Map<UUID, Integer> entryCounts = new LinkedHashMap<>();
        for (PostingLeg leg : legs) {
            LockedBalance balance = locked.get(leg.accountId());
            if (balance == null) {
                throw new IllegalStateException("Posting leg references an account whose balance is not locked: " + leg.accountId());
            }
            deltas.merge(leg.accountId(), balance.deltaFor(leg), BigDecimal::add);
            entryCounts.merge(leg.accountId(), 1, Integer::sum);
        }

        for (int i = 0; i < legs.size(); i++) {
            ledger.insertEntry(transactionId, legs.get(i), currency, description);
            if (i == 0) {
                // Simulation/test hook: fail after a partial write to prove the rollback guarantee.
                failureInjector.checkpoint(FailurePoint.AFTER_FIRST_ENTRY);
            }
        }

        deltas.forEach((accountId, delta) -> {
            LockedBalance balance = locked.get(accountId);
            BigDecimal resulting = balance.balance().add(delta);
            if (!balance.allowNegative() && resulting.signum() < 0) {
                // Callers reject insufficient funds before posting; reaching here is a bug.
                // The database CHECK constraint is a second, independent backstop.
                throw new IllegalStateException("Posting would create an unauthorized negative balance on " + accountId);
            }
            ledger.applyDelta(accountId, delta, entryCounts.get(accountId));
        });
    }

    static void requireBalanced(List<PostingLeg> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A journal transaction needs at least two entries");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (PostingLeg leg : legs) {
            if (leg.amount() == null || leg.amount().signum() <= 0) {
                throw new IllegalArgumentException("Entry amounts must be positive");
            }
            if (leg.direction() == Direction.DEBIT) {
                debits = debits.add(leg.amount());
            } else {
                credits = credits.add(leg.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Unbalanced journal: debits " + debits + " != credits " + credits);
        }
    }
}
