package com.ledgerguard.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerguard.account.AccountView;
import com.ledgerguard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Authorization is enforced server-side; the frontend hiding a button is never the control. */
class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void unauthenticatedRequestsGetProblemDetailsWithCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("trace-abc-123"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(get("/api/v1/accounts").with(httpBasic("operator", "wrong-password-000")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanReadButNotMoveMoney() throws Exception {
        AccountView account = fundedAccount("Viewer target", "100.00");
        mvc.perform(get("/api/v1/accounts/" + account.id()).with(httpBasic("viewer", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("100.00"));
        mvc.perform(post("/api/v1/transactions/withdrawals").with(httpBasic("viewer", PASSWORD))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"amount\":\"50.00\"}".formatted(account.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/accounts").with(httpBasic("viewer", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminsChangeAccountStatus() throws Exception {
        AccountView account = newAccount("Status RBAC");
        String body = "{\"status\":\"FROZEN\",\"reason\":\"review\"}";
        mvc.perform(patch("/api/v1/accounts/" + account.id() + "/status").with(httpBasic("operator", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/accounts/" + account.id() + "/status").with(httpBasic("admin", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void onlyApproversReverse() throws Exception {
        mvc.perform(post("/api/v1/transactions/" + java.util.UUID.randomUUID() + "/reversals")
                        .with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditTrailRequiresAuditorOrAdmin() throws Exception {
        mvc.perform(get("/api/v1/audit-events").with(httpBasic("operator", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events").with(httpBasic("auditor", PASSWORD))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit-events").with(httpBasic("admin", PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void simulationsRequireOperator() throws Exception {
        mvc.perform(post("/api/v1/simulations/rollback").with(httpBasic("viewer", PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void projectionRebuildRequiresAdmin() throws Exception {
        mvc.perform(post("/api/v1/ledger/projections/rebuild").with(httpBasic("operator", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedRequestsReturnValidationProblemsWithoutInternals() throws Exception {
        mvc.perform(post("/api/v1/transactions/deposits").with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.accountId").exists());
        mvc.perform(post("/api/v1/transactions/deposits").with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transactions/deposits").with(httpBasic("operator", PASSWORD))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"amount\":\"10.001\"}".formatted(java.util.UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void meReportsRoles() throws Exception {
        mvc.perform(get("/api/v1/me").with(httpBasic("approver", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("approver"))
                .andExpect(jsonPath("$.roles[0]").value("APPROVER"));
    }

    @Test
    void healthIsPublicButMetricsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(httpBasic("admin", PASSWORD))).andExpect(status().isOk());
    }
}
