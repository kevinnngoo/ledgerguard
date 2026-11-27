package com.ledgerguard.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The Simulation Lab endpoints end-to-end through HTTP, security and the real database. */
class SimulationIntegrationTest extends IntegrationTestBase {

    @Test
    void concurrentWithdrawalScenario() throws Exception {
        mvc.perform(post("/api/v1/simulations/concurrent-withdrawals").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startingBalance\":\"1000.00\",\"withdrawalAmounts\":[\"800.00\",\"600.00\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors").value(0))
                .andExpect(jsonPath("$.ledgerHealthy").value(true));
    }

    @Test
    void duplicateSubmissionScenario() throws Exception {
        mvc.perform(post("/api/v1/simulations/duplicate-submissions").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"250.00\",\"concurrentRequests\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(1))
                .andExpect(jsonPath("$.replayed").value(39))
                .andExpect(jsonPath("$.distinctTransactionIds").value(1))
                .andExpect(jsonPath("$.journalEntriesForTransaction").value(2))
                .andExpect(jsonPath("$.destinationFinalBalance").value("250.00"));
    }

    @Test
    void rollbackScenario() throws Exception {
        mvc.perform(post("/api/v1/simulations/rollback").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"75.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestFailed").value(true))
                .andExpect(jsonPath("$.sourceBalanceAfter").value("150.00"))
                .andExpect(jsonPath("$.destinationBalanceAfter").value("0.00"))
                .andExpect(jsonPath("$.destinationEntriesAfter").value(0))
                .andExpect(jsonPath("$.ledgerHealthy").value(true))
                .andExpect(jsonPath("$.followUpTransferStatus").value("POSTED"));
    }

    @Test
    void eventRecoveryScenario() throws Exception {
        mvc.perform(post("/api/v1/simulations/event-recovery").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"20.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionStatus").value("POSTED"))
                .andExpect(jsonPath("$.timeline[1].events[0].published").value(false))
                .andExpect(jsonPath("$.eventuallyPublished").value(true));
    }

    @Test
    void simulationInputLimitsAreEnforced() throws Exception {
        mvc.perform(post("/api/v1/simulations/duplicate-submissions").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"1.00\",\"concurrentRequests\":5000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SIMULATION"));
    }
}
