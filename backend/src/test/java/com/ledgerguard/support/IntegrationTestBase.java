package com.ledgerguard.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.account.AccountDtos.CreateAccountRequest;
import com.ledgerguard.account.AccountService;
import com.ledgerguard.account.AccountView;
import com.ledgerguard.ledger.IntegrityService;
import com.ledgerguard.ledger.IntegrityService.IntegrityReport;
import com.ledgerguard.ledger.LedgerRepository;
import com.ledgerguard.transaction.TransactionDtos.DepositRequest;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Every integration test runs against a REAL PostgreSQL 16 (Testcontainers), never H2 or mocks:
 * row locks, deferred constraint triggers and ON CONFLICT semantics are PostgreSQL behaviour.
 *
 * <p>One container is shared by all test classes (singleton pattern) so Spring's cached context
 * always points at a live database. Tests isolate themselves by creating their own accounts.
 */
@SpringBootTest(properties = {
        "ledgerguard.security.demo-password=" + IntegrationTestBase.PASSWORD,
        "ledgerguard.simulation.enabled=true",
        "ledgerguard.outbox.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=30"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    public static final String PASSWORD = "test-only-password-1234";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected AccountService accountService;
    @Autowired protected TransactionService transactionService;
    @Autowired protected LedgerRepository ledger;
    @Autowired protected IntegrityService integrity;
    @Autowired protected JdbcClient jdbc;

    protected AccountView newAccount(String name) {
        return accountService.create(new CreateAccountRequest(name, null, null), "operator");
    }

    protected AccountView fundedAccount(String name, String amount) {
        AccountView account = newAccount(name);
        OperationResult result = transactionService.deposit(
                new DepositRequest(account.id(), new BigDecimal(amount), null, "test funding"), key(), "operator");
        assertThat(result.httpStatus()).isEqualTo(201);
        return account;
    }

    protected BigDecimal balance(UUID accountId) {
        return ledger.projectedBalance(accountId);
    }

    protected static String key() {
        return "test-" + UUID.randomUUID();
    }

    protected static BigDecimal usd(String value) {
        return new BigDecimal(value).setScale(2);
    }

    /** Asserts every ledger invariant across the whole database. */
    protected void assertLedgerHealthy() {
        IntegrityReport report = integrity.check();
        assertThat(report.unbalancedTransactions()).as("unbalanced journal transactions").isEmpty();
        assertThat(report.postedWithoutEntries()).as("posted transactions without entries").isEmpty();
        assertThat(report.projectionMismatches()).as("projection vs ledger mismatches").isEmpty();
        assertThat(report.unauthorizedNegativeBalances()).as("unauthorized negative balances").isEmpty();
        assertThat(report.duplicateIdempotentEffects()).as("duplicate idempotent effects").isEmpty();
        assertThat(report.totalDebits()).as("total debits equal total credits").isEqualByComparingTo(report.totalCredits());
        assertThat(report.healthy()).isTrue();
    }

    protected void assertProjectionMatchesLedger(UUID accountId) {
        assertThat(ledger.projectedBalance(accountId)).isEqualByComparingTo(ledger.ledgerDerivedBalance(accountId));
    }
}
