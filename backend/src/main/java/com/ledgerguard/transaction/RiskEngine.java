package com.ledgerguard.transaction;

import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountStatus;
import com.ledgerguard.account.AccountType;
import com.ledgerguard.common.LedgerGuardProperties;
import com.ledgerguard.common.Money;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic, configurable business rules evaluated while the affected balances are locked.
 * A failed rule is a BUSINESS rejection: it is committed as a REJECTED transaction with a reason.
 *
 * <p>This is a simulation of rule-based checks. It is not fraud detection and not regulatory compliance.
 */
@Component
public class RiskEngine {

    public record Rejection(String code, String reason) {}

    private final LedgerGuardProperties.Risk config;
    private final TransactionRepository transactions;

    public RiskEngine(LedgerGuardProperties properties, TransactionRepository transactions) {
        this.config = properties.risk();
        this.transactions = transactions;
    }

    /** Account must be an ACTIVE customer cash account to take part in money movement. */
    public Optional<Rejection> checkAccount(Account account, String role) {
        if (account.systemAccount() || account.type() != AccountType.LIABILITY) {
            return Optional.of(new Rejection("UNSUPPORTED_ACCOUNT",
                    "The " + role + " account " + account.accountNumber() + " is not a customer cash account"));
        }
        if (account.status() == AccountStatus.FROZEN) {
            return Optional.of(new Rejection("ACCOUNT_FROZEN", "The " + role + " account " + account.accountNumber() + " is frozen"));
        }
        if (account.status() == AccountStatus.CLOSED) {
            return Optional.of(new Rejection("ACCOUNT_CLOSED", "The " + role + " account " + account.accountNumber() + " is closed"));
        }
        return Optional.empty();
    }

    public Optional<Rejection> checkLimits(BigDecimal amount, UUID sourceAccountId) {
        if (amount.compareTo(config.maxTransactionAmount()) > 0) {
            return Optional.of(new Rejection("MAX_TRANSACTION_AMOUNT",
                    "Amount " + Money.format(amount) + " exceeds the per-transaction limit of " + Money.format(config.maxTransactionAmount())));
        }
        if (sourceAccountId != null) {
            BigDecimal outgoing = transactions.outgoingInLast24Hours(sourceAccountId);
            if (outgoing.add(amount).compareTo(config.maxDailyOutgoing()) > 0) {
                return Optional.of(new Rejection("DAILY_OUTGOING_LIMIT",
                        "Amount would bring 24-hour outgoing value to " + Money.format(outgoing.add(amount))
                                + ", above the limit of " + Money.format(config.maxDailyOutgoing())));
            }
        }
        return Optional.empty();
    }

    public Optional<Rejection> checkFunds(BigDecimal available, BigDecimal amount) {
        if (available.compareTo(amount) < 0) {
            return Optional.of(new Rejection("INSUFFICIENT_FUNDS",
                    "Available balance " + Money.format(available) + " is less than the requested " + Money.format(amount)));
        }
        return Optional.empty();
    }
}
