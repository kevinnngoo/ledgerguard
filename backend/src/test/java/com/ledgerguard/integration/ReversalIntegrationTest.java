package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.ledger.Direction;
import com.ledgerguard.ledger.JournalEntry;
import com.ledgerguard.support.Concurrently;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.ReversalRequest;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionStatus;
import com.ledgerguard.transaction.TransactionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReversalIntegrationTest extends IntegrationTestBase {

    @Test
    void reversalPostsCompensatingEntriesAndPreservesOriginalHistory() {
        AccountView source = fundedAccount("Rev source", "500.00");
        AccountView destination = newAccount("Rev destination");
        OperationResult original = transfer(source, destination, "200");
        List<JournalEntry> originalEntries = ledger.entriesForTransaction(original.body().id());

        OperationResult reversal = transactionService.reverse(original.body().id(),
                new ReversalRequest("Customer disputed transfer"), key(), "approver");

        assertThat(reversal.httpStatus()).isEqualTo(201);
        assertThat(reversal.body().type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(reversal.body().reversesTransactionId()).isEqualTo(original.body().id());
        assertThat(reversal.body().initiatedBy()).isEqualTo("approver");
        assertThat(reversal.body().description()).isEqualTo("Customer disputed transfer");
        assertThat(reversal.body().entries()).hasSize(2).allSatisfy(e -> {
            JournalEntry mirrored = originalEntries.stream().filter(o -> o.accountId().equals(e.accountId())).findFirst().orElseThrow();
            assertThat(e.direction()).isEqualTo(mirrored.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT);
            assertThat(e.amount()).isEqualByComparingTo(mirrored.amount());
        });
        // Original entries are untouched; the original is marked REVERSED.
        assertThat(ledger.entriesForTransaction(original.body().id())).isEqualTo(originalEntries);
        var detail = transactionService.get(original.body().id());
        assertThat(detail.transaction().status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(detail.reversedByTransactionId()).isEqualTo(reversal.body().id());

        assertThat(balance(source.id())).isEqualByComparingTo("500.00");
        assertThat(balance(destination.id())).isEqualByComparingTo("0.00");
        assertLedgerHealthy();
    }

    @Test
    void aTransactionCanOnlyBeReversedOnce() {
        AccountView source = fundedAccount("Rev once source", "500.00");
        AccountView destination = newAccount("Rev once destination");
        OperationResult original = transfer(source, destination, "100");
        transactionService.reverse(original.body().id(), new ReversalRequest("first"), key(), "approver");

        assertThatThrownBy(() -> transactionService.reverse(original.body().id(), new ReversalRequest("second"), key(), "approver"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been reversed");
    }

    @Test
    void concurrentReversalsWithDifferentKeysProduceExactlyOne() throws Exception {
        AccountView source = fundedAccount("Rev race source", "500.00");
        AccountView destination = newAccount("Rev race destination");
        OperationResult original = transfer(source, destination, "100");
        UUID id = original.body().id();

        List<String> outcomes = Concurrently.repeat(10, () -> {
            try {
                transactionService.reverse(id, new ReversalRequest("race"), key(), "approver");
                return "REVERSED";
            } catch (ApiException e) {
                return e.code();
            }
        });

        assertThat(outcomes.stream().filter("REVERSED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("ALREADY_REVERSED"::equals).count()).isEqualTo(9);
        assertThat(balance(source.id())).isEqualByComparingTo("500.00");
        assertLedgerHealthy();
    }

    @Test
    void initiatorCannotReverseTheirOwnTransaction() {
        AccountView source = fundedAccount("SoD source", "500.00");
        AccountView destination = newAccount("SoD destination");
        OperationResult byAdmin = transactionService.transfer(
                new TransferRequest(source.id(), destination.id(), usd("50"), null, null), key(), "admin");

        assertThatThrownBy(() -> transactionService.reverse(byAdmin.body().id(), new ReversalRequest("self"), key(), "admin"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("SEPARATION_OF_DUTIES"));
    }

    @Test
    void reversalIsRefusedWhenFundsHaveAlreadyLeftTheDestination() {
        AccountView source = fundedAccount("Spent source", "500.00");
        AccountView destination = newAccount("Spent destination");
        AccountView elsewhere = newAccount("Spent elsewhere");
        OperationResult original = transfer(source, destination, "300");
        transfer(destination, elsewhere, "300");

        assertThatThrownBy(() -> transactionService.reverse(original.body().id(), new ReversalRequest("too late"), key(), "approver"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INSUFFICIENT_FUNDS_FOR_REVERSAL"));
        assertThat(transactionService.get(original.body().id()).transaction().status()).isEqualTo(TransactionStatus.POSTED);
        assertLedgerHealthy();
    }

    @Test
    void reversalsAndRejectedTransactionsCannotBeReversed() {
        AccountView source = fundedAccount("Rev rules source", "100.00");
        AccountView destination = newAccount("Rev rules destination");
        OperationResult original = transfer(source, destination, "10");
        OperationResult reversal = transactionService.reverse(original.body().id(), new ReversalRequest("r"), key(), "approver");
        OperationResult rejected = transfer(source, destination, "100000");

        assertThatThrownBy(() -> transactionService.reverse(reversal.body().id(), new ReversalRequest("r2"), key(), "admin"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot itself be reversed");
        assertThatThrownBy(() -> transactionService.reverse(rejected.body().id(), new ReversalRequest("r3"), key(), "approver"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Only posted");
    }

    private OperationResult transfer(AccountView from, AccountView to, String amount) {
        return transactionService.transfer(new TransferRequest(from.id(), to.id(), usd(amount), null, null), key(), "operator");
    }
}
