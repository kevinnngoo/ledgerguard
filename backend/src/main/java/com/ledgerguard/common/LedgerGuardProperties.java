package com.ledgerguard.common;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerguard")
public record LedgerGuardProperties(
        String currency,
        Risk risk,
        Security security,
        Simulation simulation,
        Outbox outbox) {

    public record Risk(BigDecimal maxTransactionAmount, BigDecimal maxDailyOutgoing) {}

    public record Security(String demoPassword, List<String> corsAllowedOrigins) {
        public List<String> allowedOrigins() {
            return corsAllowedOrigins == null
                    ? List.of()
                    : corsAllowedOrigins.stream().filter(o -> o != null && !o.isBlank()).toList();
        }
    }

    public record Simulation(boolean enabled, int maxConcurrentRequests) {}

    public record Outbox(boolean enabled, long pollIntervalMs, int batchSize, long maxBackoffSeconds) {}
}
