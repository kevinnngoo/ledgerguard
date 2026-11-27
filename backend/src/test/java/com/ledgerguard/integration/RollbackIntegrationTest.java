package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.simulation.FailureInjector;
import com.ledgerguard.simulation.FailureInjector.FailurePoint;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Atomicity: a failure after a partial write must leave NO trace, and the key must stay usable. */
class RollbackIntegrationTest extends IntegrationTestBase {

    @Autowired FailureInjector failureInjector;

    @AfterEach
    void disarm() {
        failureInjector.disarm();
    }

    @Test
    void failureAfterFirstJournalEntryRollsBackEverything() {
        AccountView source = fundedAccount("Rollback source", "1000.00");
        AccountView destination = newAccount("Rollback destination");
        long sourceEntriesBefore = ledger.entries(source.id(), 0, 1).totalItems();
        long transactionsBefore = jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single();
        long outboxBefore = jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();
        String key = key();

        failureInjector.armForCurrentThread(FailurePoint.AFTER_FIRST_ENTRY);
        assertThatThrownBy(() -> transactionService.transfer(
                new TransferRequest(source.id(), destination.id(), usd("300"), null, null), key, "operator"))
                .isInstanceOf(FailureInjector.InjectedFailureException.class);

        assertThat(balance(source.id())).isEqualByComparingTo("1000.00");
        assertThat(balance(destination.id())).isEqualByComparingTo("0.00");
        assertThat(ledger.entries(source.id(), 0, 1).totalItems()).isEqualTo(sourceEntriesBefore);
        assertThat(ledger.entries(destination.id(), 0, 1).totalItems()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single()).isEqualTo(transactionsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single()).isEqualTo(outboxBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_records WHERE idempotency_key = :k")
                .param("k", key).query(Long.class).single())
                .as("the idempotency claim rolled back with the transaction")
                .isZero();
        assertLedgerHealthy();

        // The client retries with the SAME key after the fault is gone: it executes exactly once.
        OperationResult retry = transactionService.transfer(
                new TransferRequest(source.id(), destination.id(), usd("300"), null, null), key, "operator");
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.body().status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(balance(source.id())).isEqualByComparingTo("700.00");
        assertThat(balance(destination.id())).isEqualByComparingTo("300.00");
    }

    @Test
    void failureInjectionIsPerThreadAndDoesNotAffectOtherRequests() throws Exception {
        AccountView source = fundedAccount("Isolation source", "100.00");
        AccountView destination = newAccount("Isolation destination");
        failureInjector.armForCurrentThread(FailurePoint.AFTER_FIRST_ENTRY);

        Thread other = new Thread(() -> transactionService.transfer(
                new TransferRequest(source.id(), destination.id(), usd("10"), null, null), key(), "operator"));
        other.start();
        other.join();

        assertThat(balance(destination.id())).isEqualByComparingTo("10.00");
    }
}
