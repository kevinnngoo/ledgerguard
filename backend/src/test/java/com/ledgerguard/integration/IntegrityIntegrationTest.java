package com.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.ledger.IntegrityService.IntegrityReport;
import com.ledgerguard.ledger.IntegrityService.RebuildResult;
import com.ledgerguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class IntegrityIntegrationTest extends IntegrationTestBase {

    @Test
    void detectsProjectionDriftAndRebuildsFromTheLedger() {
        AccountView account = fundedAccount("Drift", "300.00");
        try {
            // Simulate a projection bug: the balance no longer matches the ledger.
            jdbc.sql("UPDATE account_balances SET balance = 999.00 WHERE account_id = :a").param("a", account.id()).update();

            IntegrityReport report = integrity.check();
            assertThat(report.healthy()).isFalse();
            assertThat(report.projectionMismatches()).anySatisfy(m -> {
                assertThat(m.accountId()).isEqualTo(account.id());
                assertThat(m.projectedBalance()).isEqualByComparingTo("999.00");
                assertThat(m.ledgerBalance()).isEqualByComparingTo("300.00");
            });
            assertThat(accountService.balance(account.id()).consistent()).isFalse();
        } finally {
            RebuildResult rebuilt = integrity.rebuildProjections("admin");
            assertThat(rebuilt.accountsCorrected()).isGreaterThanOrEqualTo(1);
        }
        assertThat(balance(account.id())).isEqualByComparingTo("300.00");
        assertLedgerHealthy();
    }

    @Test
    void healthyLedgerReportsBalancedTotals() {
        fundedAccount("Healthy", "10.00");
        IntegrityReport report = integrity.check();
        assertThat(report.journalEntries()).isPositive();
        assertThat(report.totalDebits()).isEqualByComparingTo(report.totalCredits());
        assertLedgerHealthy();
    }
}
