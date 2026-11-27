package com.ledgerguard.simulation;

import com.ledgerguard.simulation.SimulationService.ConcurrentWithdrawalResult;
import com.ledgerguard.simulation.SimulationService.DuplicateSubmissionResult;
import com.ledgerguard.simulation.SimulationService.EventRecoveryResult;
import com.ledgerguard.simulation.SimulationService.RollbackResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only registered when ledgerguard.simulation.enabled=true, and restricted to OPERATOR. */
@RestController
@RequestMapping("/api/v1/simulations")
@Tag(name = "Simulation Lab")
@ConditionalOnProperty(prefix = "ledgerguard.simulation", name = "enabled", havingValue = "true")
public class SimulationController {

    private final SimulationService simulations;

    public SimulationController(SimulationService simulations) {
        this.simulations = simulations;
    }

    public record ConcurrentWithdrawalRequest(@NotNull BigDecimal startingBalance, @NotNull List<BigDecimal> withdrawalAmounts) {}

    public record DuplicateRequest(@NotNull BigDecimal amount, int concurrentRequests) {}

    public record AmountRequest(@NotNull BigDecimal amount) {}

    @PostMapping("/concurrent-withdrawals")
    @Operation(summary = "Release N withdrawals against one account simultaneously")
    public ConcurrentWithdrawalResult concurrentWithdrawals(@Valid @RequestBody ConcurrentWithdrawalRequest r, Principal p) {
        return simulations.concurrentWithdrawals(r.startingBalance(), r.withdrawalAmounts(), p.getName());
    }

    @PostMapping("/duplicate-submissions")
    @Operation(summary = "Submit one transfer N times concurrently with the same Idempotency-Key")
    public DuplicateSubmissionResult duplicates(@Valid @RequestBody DuplicateRequest r, Principal p) {
        return simulations.duplicateSubmissions(r.amount(), r.concurrentRequests(), p.getName());
    }

    @PostMapping("/rollback")
    @Operation(summary = "Inject a failure mid-posting and verify nothing was committed")
    public RollbackResult rollback(@Valid @RequestBody AmountRequest r, Principal p) {
        return simulations.rollback(r.amount(), p.getName());
    }

    @PostMapping("/event-recovery")
    @Operation(summary = "Simulate a broker outage and verify outbox recovery")
    public EventRecoveryResult eventRecovery(@Valid @RequestBody AmountRequest r, Principal p) {
        return simulations.eventRecovery(r.amount(), p.getName());
    }
}
