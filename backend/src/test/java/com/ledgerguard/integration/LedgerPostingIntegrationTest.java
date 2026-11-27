package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountDtos.StatusChangeRequest;
import com.ledgerguard.account.AccountStatus;
import com.ledgerguard.account.AccountView;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.ledger.Direction;
import com.ledgerguard.ledger.JournalEntry;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.DepositRequest;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionDtos.WithdrawalRequest;
import com.ledgerguard.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerPostingIntegrationTest extends IntegrationTestBase {

    @Test
    void depositDebitsClearingAndCreditsCustomerLiability() {
        AccountView account = newAccount("Deposit test");
        BigDecimal clearingBefore = balance(Account.CLEARING_ACCOUNT_ID);

        OperationResult result = transactionService.deposit(
                new DepositRequest(account.id(), new BigDecimal("500.00"), "USD", "payroll"), key(), "operator");

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.body().status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(result.body().balanced()).isTrue();
        List<JournalEntry> entries = result.body().entries();
        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(Account.CLEARING_ACCOUNT_ID);
            assertThat(e.direction()).isEqualTo(Direction.DEBIT);
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(account.id());
            assertThat(e.direction()).isEqualTo(Direction.CREDIT);
        });
        assertThat(balance(account.id())).isEqualByComparingTo("500.00");
        assertThat(balance(Account.CLEARING_ACCOUNT_ID)).isEqualByComparingTo(clearingBefore.add(usd("500")));
        assertProjectionMatchesLedger(account.id());
        assertProjectionMatchesLedger(Account.CLEARING_ACCOUNT_ID);
    }

    @Test
    void withdrawalReducesBalance() {
        AccountView account = fundedAccount("Withdrawal test", "1000.00");

        OperationResult result = transactionService.withdraw(
                new WithdrawalRequest(account.id(), new BigDecimal("400.00"), null, null), key(), "operator");

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(balance(account.id())).isEqualByComparingTo("600.00");
        assertProjectionMatchesLedger(account.id());
    }

    @Test
    void insufficientFundsIsARecordedBusinessRejectionWithNoLedgerEffect() {
        AccountView account = fundedAccount("Insufficient funds", "100.00");

        OperationResult result = transactionService.withdraw(
                new WithdrawalRequest(account.id(), new BigDecimal("100.01"), null, null), key(), "operator");

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.body().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(result.body().rejectionCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.body().rejectionReason()).contains("100.00").contains("100.01");
        assertThat(result.body().entries()).isEmpty();
        assertThat(balance(account.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void transferMovesValueBetweenCustomerAccountsAndPreservesTotal() {
        AccountView source = fundedAccount("Transfer source", "1000.00");
        AccountView destination = fundedAccount("Transfer destination", "50.00");

        OperationResult result = transactionService.transfer(
                new TransferRequest(source.id(), destination.id(), new BigDecimal("250.00"), null, "rent"), key(), "operator");

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(balance(source.id())).isEqualByComparingTo("750.00");
        assertThat(balance(destination.id())).isEqualByComparingTo("300.00");
        assertThat(balance(source.id()).add(balance(destination.id()))).isEqualByComparingTo("1050.00");
        assertThat(result.body().entries())
                .extracting(JournalEntry::direction)
                .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT);
        assertLedgerHealthy();
    }

    @Test
    void transferToSameAccountIsRejectedAsInvalid() {
        AccountView account = fundedAccount("Self transfer", "10.00");
        assertThatThrownBy(() -> transactionService.transfer(
                new TransferRequest(account.id(), account.id(), BigDecimal.ONE, null, null), key(), "operator"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void frozenAccountsRejectMoneyMovementAndUnfreezingRestoresIt() {
        AccountView account = fundedAccount("Freeze test", "300.00");
        accountService.changeStatus(account.id(), new StatusChangeRequest(AccountStatus.FROZEN, "investigation"), "admin");

        OperationResult rejected = transactionService.withdraw(
                new WithdrawalRequest(account.id(), new BigDecimal("10.00"), null, null), key(), "operator");
        assertThat(rejected.body().rejectionCode()).isEqualTo("ACCOUNT_FROZEN");

        accountService.changeStatus(account.id(), new StatusChangeRequest(AccountStatus.ACTIVE, "cleared"), "admin");
        OperationResult posted = transactionService.withdraw(
                new WithdrawalRequest(account.id(), new BigDecimal("10.00"), null, null), key(), "operator");
        assertThat(posted.body().status()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void accountWithNonzeroBalanceCannotBeClosed() {
        AccountView account = fundedAccount("Close test", "25.00");

        assertThatThrownBy(() -> accountService.changeStatus(account.id(),
                new StatusChangeRequest(AccountStatus.CLOSED, "customer request"), "admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("zero balance");

        transactionService.withdraw(new WithdrawalRequest(account.id(), new BigDecimal("25.00"), null, null), key(), "operator");
        AccountView closed = accountService.changeStatus(account.id(),
                new StatusChangeRequest(AccountStatus.CLOSED, "customer request"), "admin");
        assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);

        OperationResult deposit = transactionService.deposit(
                new DepositRequest(account.id(), BigDecimal.ONE, null, null), key(), "operator");
        assertThat(deposit.body().rejectionCode()).isEqualTo("ACCOUNT_CLOSED");

        assertThatThrownBy(() -> accountService.changeStatus(account.id(),
                new StatusChangeRequest(AccountStatus.ACTIVE, "reopen"), "admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot change account status");
    }

    @Test
    void systemClearingAccountCannotBeUsedDirectlyOrFrozen() {
        AccountView account = fundedAccount("Clearing misuse", "10.00");
        OperationResult result = transactionService.transfer(
                new TransferRequest(account.id(), Account.CLEARING_ACCOUNT_ID, BigDecimal.ONE, null, null), key(), "operator");
        assertThat(result.body().rejectionCode()).isEqualTo("UNSUPPORTED_ACCOUNT");

        assertThatThrownBy(() -> accountService.changeStatus(Account.CLEARING_ACCOUNT_ID,
                new StatusChangeRequest(AccountStatus.FROZEN, "x"), "admin"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void perTransactionLimitIsEnforced() {
        AccountView account = newAccount("Limit test");
        OperationResult result = transactionService.deposit(
                new DepositRequest(account.id(), new BigDecimal("1000000.01"), null, null), key(), "operator");
        assertThat(result.body().rejectionCode()).isEqualTo("MAX_TRANSACTION_AMOUNT");
        assertThat(balance(account.id())).isEqualByComparingTo("0");
    }

    @Test
    void unknownAccountIsNotFoundAndCommitsNothing() {
        long before = jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single();
        assertThatThrownBy(() -> transactionService.deposit(
                new DepositRequest(java.util.UUID.randomUUID(), BigDecimal.TEN, null, null), key(), "operator"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("do not exist");
        long after = jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single();
        assertThat(after).isEqualTo(before);
    }
}
