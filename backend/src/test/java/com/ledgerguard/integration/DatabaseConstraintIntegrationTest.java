package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountView;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database enforces ledger invariants independently of application code. These tests bypass
 * the services and write SQL directly, as a buggy code path or an ad-hoc script might.
 */
class DatabaseConstraintIntegrationTest extends IntegrationTestBase {

    @Autowired TransactionTemplate tx;

    @Test
    void unbalancedJournalCannotBeCommitted() {
        AccountView account = newAccount("Unbalanced");
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertTransactionHeader(txId, account.id());
            jdbc.sql("INSERT INTO journal_entries (transaction_id, account_id, direction, amount, currency) VALUES (:t, :a, 'CREDIT', 50, 'USD')")
                    .param("t", txId).param("a", account.id()).update();
        })).rootCause()
                .hasMessageContaining("unbalanced");

        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE id = :id").param("id", txId).query(Long.class).single()).isZero();
    }

    @Test
    void balancedJournalWrittenDirectlyIsAccepted() {
        AccountView account = newAccount("Direct balanced");
        UUID txId = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            insertTransactionHeader(txId, account.id());
            jdbc.sql("INSERT INTO journal_entries (transaction_id, account_id, direction, amount, currency) VALUES (:t, :c, 'DEBIT', 50, 'USD'), (:t, :a, 'CREDIT', 50, 'USD')")
                    .param("t", txId).param("c", Account.CLEARING_ACCOUNT_ID).param("a", account.id()).update();
            // keep projections consistent so the shared ledger stays healthy for other tests
            jdbc.sql("UPDATE account_balances SET balance = balance + 50 WHERE account_id IN (:c, :a)")
                    .param("c", Account.CLEARING_ACCOUNT_ID).param("a", account.id()).update();
        });
        assertLedgerHealthy();
    }

    @Test
    void journalEntriesAreAppendOnly() {
        AccountView account = fundedAccount("Append only", "10.00");
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entries SET amount = 1 WHERE account_id = :a").param("a", account.id()).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries WHERE account_id = :a").param("a", account.id()).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM transactions WHERE destination_account_id = :a").param("a", account.id()).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cannot be deleted");
    }

    @Test
    void balanceProjectionCannotGoUnauthorizedNegative() {
        AccountView account = fundedAccount("Negative", "5.00");
        assertThatThrownBy(() -> jdbc.sql("UPDATE account_balances SET balance = -0.01 WHERE account_id = :a")
                .param("a", account.id()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_balances_no_unauthorized_negative");
    }

    @Test
    void postedTransactionFinancialFieldsAndIllegalTransitionsAreBlocked() {
        AccountView account = newAccount("Immutable");
        OperationResult deposit = transactionService.deposit(
                new com.ledgerguard.transaction.TransactionDtos.DepositRequest(account.id(), usd("20"), null, null), key(), "operator");
        UUID id = deposit.body().id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE transactions SET amount = 999 WHERE id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE transactions SET status = 'REJECTED', rejection_code = 'X' WHERE id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Illegal transaction status transition");
    }

    @Test
    void auditEventsAreAppendOnly() {
        newAccount("Audit append only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET actor = 'mallory'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void flywayAppliedAllMigrations() {
        long applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Long.class).single();
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    private void insertTransactionHeader(UUID txId, UUID accountId) {
        jdbc.sql("""
                        INSERT INTO transactions (id, reference, type, status, amount, currency, destination_account_id, initiated_by, posted_at)
                        VALUES (:id, :ref, 'DEPOSIT', 'POSTED', 50, 'USD', :a, 'test', now())
                        """)
                .param("id", txId).param("ref", "TEST-" + txId.toString().substring(0, 8)).param("a", accountId).update();
    }
}
