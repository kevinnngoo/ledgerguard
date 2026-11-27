package com.ledgerguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LedgerGuard: a production-inspired, simulated double-entry ledger.
 * It does not process real money or connect to real financial institutions.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgerGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerGuardApplication.class, args);
    }
}
