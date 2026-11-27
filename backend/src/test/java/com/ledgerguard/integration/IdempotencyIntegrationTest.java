package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.support.Concurrently;
import com.ledgerguard.support.IntegrationTestBase;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionDtos.WithdrawalRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    /** Required scenario: 100 concurrent submissions with one key produce exactly one financial effect. */
    @Test
    void oneHundredConcurrentDuplicatesProduceExactlyOneTransfer() throws Exception {
        AccountView source = fundedAccount("Idem source", "1000.00");
        AccountView destination = newAccount("Idem destination");
        String key = key();
        TransferRequest request = new TransferRequest(source.id(), destination.id(), usd("250"), null, "retry storm");

        List<OperationResult> results = Concurrently.repeat(100, () -> transactionService.transfer(request, key, "operator"));

        Set<UUID> transactionIds = results.stream().map(r -> r.body().id()).collect(Collectors.toSet());
        assertThat(transactionIds).as("every response refers to the same transaction").hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).as("executed once").isEqualTo(1);
        assertThat(results.stream().filter(OperationResult::replayed).count()).as("replayed").isEqualTo(99);
        assertThat(results).allSatisfy(r -> assertThat(r.httpStatus()).isEqualTo(201));

        UUID txId = transactionIds.iterator().next();
        assertThat(ledger.entriesForTransaction(txId)).hasSize(2);
        long transfersBetween = jdbc.sql("""
                        SELECT count(*) FROM transactions WHERE source_account_id = :s AND destination_account_id = :d
                        """).param("s", source.id()).param("d", destination.id()).query(Long.class).single();
        assertThat(transfersBetween).isEqualTo(1);
        assertThat(balance(source.id())).isEqualByComparingTo("750.00");
        assertThat(balance(destination.id())).isEqualByComparingTo("250.00");
        assertLedgerHealthy();
    }

    @Test
    void sequentialRetryReturnsTheOriginalResponseOverHttp() throws Exception {
        AccountView account = fundedAccount("HTTP retry", "500.00");
        String body = """
                {"accountId":"%s","amount":"125.00","description":"atm"}
                """.formatted(account.id());
        String key = key();

        MvcResult first = mvc.perform(post("/api/v1/transactions/withdrawals")
                        .with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn();

        MvcResult retry = mvc.perform(post("/api/v1/transactions/withdrawals")
                        .with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        String firstId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        String retryId = com.jayway.jsonpath.JsonPath.read(retry.getResponse().getContentAsString(), "$.id");
        assertThat(retryId).isEqualTo(firstId);
        assertThat(balance(account.id())).isEqualByComparingTo("375.00");
    }

    @Test
    void reusingAKeyWithADifferentPayloadIsRejected() throws Exception {
        AccountView account = fundedAccount("Key reuse", "500.00");
        String key = key();
        transactionService.withdraw(new WithdrawalRequest(account.id(), usd("10"), null, null), key, "operator");

        mvc.perform(post("/api/v1/transactions/withdrawals")
                        .with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"amount\":\"20.00\"}".formatted(account.id())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balance(account.id())).isEqualByComparingTo("490.00");
    }

    @Test
    void concurrentConflictingPayloadsWithOneKeyYieldOneEffect() throws Exception {
        AccountView account = fundedAccount("Conflicting keys", "1000.00");
        String key = key();

        Callable<Object> first = () -> safely(() -> transactionService.withdraw(
                new WithdrawalRequest(account.id(), usd("100"), null, null), key, "operator"));
        Callable<Object> second = () -> safely(() -> transactionService.withdraw(
                new WithdrawalRequest(account.id(), usd("200"), null, null), key, "operator"));

        List<Object> outcomes = Concurrently.run(List.of(first, second));

        long successes = outcomes.stream().filter(o -> o instanceof OperationResult).count();
        assertThat(successes).isEqualTo(1);
        assertThat(outcomes).anySatisfy(o -> assertThat(o.toString()).contains("different request"));
        assertThat(balance(account.id())).isIn(usd("900"), usd("800"));
    }

    @Test
    void businessRejectionsAreAlsoReplayedNotReEvaluated() {
        AccountView account = fundedAccount("Rejection replay", "50.00");
        String key = key();
        WithdrawalRequest tooMuch = new WithdrawalRequest(account.id(), usd("80"), null, null);

        OperationResult first = transactionService.withdraw(tooMuch, key, "operator");
        assertThat(first.httpStatus()).isEqualTo(422);

        // Even after funds arrive, the retry returns the ORIGINAL outcome for that key.
        transactionService.deposit(new com.ledgerguard.transaction.TransactionDtos.DepositRequest(
                account.id(), usd("100"), null, null), key(), "operator");
        OperationResult retry = transactionService.withdraw(tooMuch, key, "operator");

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.httpStatus()).isEqualTo(422);
        assertThat(retry.body().id()).isEqualTo(first.body().id());
        assertThat(balance(account.id())).isEqualByComparingTo("150.00");
    }

    @Test
    void keysAreScopedPerClient() {
        AccountView account = fundedAccount("Key scope", "100.00");
        String key = key();
        WithdrawalRequest request = new WithdrawalRequest(account.id(), usd("10"), null, null);

        OperationResult byOperator = transactionService.withdraw(request, key, "operator");
        OperationResult byAdmin = transactionService.withdraw(request, key, "admin");

        assertThat(byOperator.replayed()).isFalse();
        assertThat(byAdmin.replayed()).isFalse();
        assertThat(byAdmin.body().id()).isNotEqualTo(byOperator.body().id());
        assertThat(balance(account.id())).isEqualByComparingTo("80.00");
    }

    @Test
    void missingOrMalformedKeyIsRejected() throws Exception {
        AccountView account = fundedAccount("No key", "100.00");
        String body = "{\"accountId\":\"%s\",\"amount\":\"1.00\"}".formatted(account.id());

        mvc.perform(post("/api/v1/transactions/withdrawals").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transactions/withdrawals").with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(balance(account.id())).isEqualByComparingTo("100.00");
    }

    private static Object safely(Callable<?> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
