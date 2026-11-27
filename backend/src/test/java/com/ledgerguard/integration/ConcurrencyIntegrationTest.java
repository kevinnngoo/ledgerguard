package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.support.Concurrently;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionDtos.WithdrawalRequest;
import com.ledgerguard.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Concurrency is the core claim of LedgerGuard, so these tests release many requests at the same
 * instant (see {@link Concurrently}) against a real PostgreSQL and assert on committed state.
 * No test assumes which request wins.
 */
class ConcurrencyIntegrationTest extends IntegrationTestBase {

    /** The README scenario: $1,000 balance, $800 and $600 withdrawals at the same time. */
    @RepeatedTest(5)
    void twoConcurrentWithdrawalsCannotBothSucceed() throws Exception {
        AccountView account = fundedAccount("800/600 race", "1000.00");

        Callable<OperationResult> withdraw800 = () -> transactionService.withdraw(
                new WithdrawalRequest(account.id(), usd("800"), null, null), key(), "operator");
        Callable<OperationResult> withdraw600 = () -> transactionService.withdraw(
                new WithdrawalRequest(account.id(), usd("600"), null, null), key(), "operator");

        List<OperationResult> results = Concurrently.run(List.of(withdraw800, withdraw600));

        List<OperationResult> posted = results.stream().filter(r -> r.body().status() == TransactionStatus.POSTED).toList();
        List<OperationResult> rejected = results.stream().filter(r -> r.body().status() == TransactionStatus.REJECTED).toList();
        assertThat(posted).hasSize(1);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).body().rejectionCode()).isEqualTo("INSUFFICIENT_FUNDS");

        BigDecimal winner = posted.get(0).body().amount();
        assertThat(balance(account.id())).isEqualByComparingTo(usd("1000").subtract(winner));
        assertThat(balance(account.id()).signum()).isGreaterThanOrEqualTo(0);
        assertProjectionMatchesLedger(account.id());
        assertLedgerHealthy();
    }

    @Test
    void fiftyConcurrentWithdrawalsNeverOverdraw() throws Exception {
        AccountView account = fundedAccount("50 withdrawals", "1000.00");

        List<OperationResult> results = Concurrently.repeat(50, () -> transactionService.withdraw(
                new WithdrawalRequest(account.id(), usd("100"), null, null), key(), "operator"));

        long posted = results.stream().filter(r -> r.body().status() == TransactionStatus.POSTED).count();
        long rejected = results.stream().filter(r -> r.body().status() == TransactionStatus.REJECTED).count();
        assertThat(posted).isEqualTo(10);
        assertThat(rejected).isEqualTo(40);
        assertThat(balance(account.id())).isEqualByComparingTo("0.00");
        assertProjectionMatchesLedger(account.id());
        assertLedgerHealthy();
    }

    @Test
    void concurrentTransfersAcrossOverlappingAccountsPreserveTotalValue() throws Exception {
        List<AccountView> accounts = List.of(
                fundedAccount("Mesh A", "1000.00"), fundedAccount("Mesh B", "1000.00"),
                fundedAccount("Mesh C", "1000.00"), fundedAccount("Mesh D", "1000.00"));
        Random random = new Random(42);
        List<Callable<OperationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int from = random.nextInt(4);
            int to = (from + 1 + random.nextInt(3)) % 4;
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(400)).setScale(2);
            tasks.add(() -> transactionService.transfer(
                    new TransferRequest(accounts.get(from).id(), accounts.get(to).id(), amount, null, null), key(), "operator"));
        }

        List<OperationResult> results = Concurrently.run(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.body().status()).isIn(TransactionStatus.POSTED, TransactionStatus.REJECTED));
        BigDecimal total = accounts.stream().map(a -> balance(a.id())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).as("value is neither created nor destroyed").isEqualByComparingTo("4000.00");
        accounts.forEach(a -> {
            assertThat(balance(a.id()).signum()).isGreaterThanOrEqualTo(0);
            assertProjectionMatchesLedger(a.id());
        });
        assertLedgerHealthy();
    }

    /**
     * A->B and B->A at the same time is the classic deadlock. Locking rows in account_id order
     * means both transactions request the same row first, so they queue instead of deadlocking.
     */
    @Test
    void oppositeDirectionTransfersDoNotDeadlockOrLoseUpdates() throws Exception {
        AccountView a = fundedAccount("Opposite A", "5000.00");
        AccountView b = fundedAccount("Opposite B", "5000.00");
        List<Callable<OperationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> transactionService.transfer(new TransferRequest(a.id(), b.id(), usd("10"), null, null), key(), "operator"));
            tasks.add(() -> transactionService.transfer(new TransferRequest(b.id(), a.id(), usd("10"), null, null), key(), "operator"));
        }

        List<OperationResult> results = Concurrently.run(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.body().status()).isEqualTo(TransactionStatus.POSTED));
        assertThat(balance(a.id())).isEqualByComparingTo("5000.00");
        assertThat(balance(b.id())).isEqualByComparingTo("5000.00");
        assertThat(ledger.entries(a.id(), 0, 1).totalItems()).isEqualTo(81); // 1 funding + 80 transfer legs
        assertLedgerHealthy();
    }
}
