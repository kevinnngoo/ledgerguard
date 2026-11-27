package com.ledgerguard.account;

import com.ledgerguard.account.AccountDtos.BalanceView;
import com.ledgerguard.account.AccountDtos.CreateAccountRequest;
import com.ledgerguard.account.AccountDtos.StatusChangeRequest;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.Money;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.common.TransactionRunner;
import com.ledgerguard.ledger.LedgerRepository;
import com.ledgerguard.ledger.LockedBalance;
import com.ledgerguard.outbox.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final AuditService audit;
    private final OutboxRepository outbox;
    private final TransactionRunner runner;

    public AccountService(AccountRepository accounts, LedgerRepository ledger, AuditService audit,
                          OutboxRepository outbox, TransactionRunner runner) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.outbox = outbox;
        this.runner = runner;
    }

    public AccountView create(CreateAccountRequest request, String actor) {
        return create(request, actor, false);
    }

    public AccountView create(CreateAccountRequest request, String actor, boolean simulation) {
        String currency = Money.requireSupportedCurrency(request.currency());
        return runner.inTransaction(() -> {
            Account account = accounts.insert(request.name().trim(), AccountType.LIABILITY, currency,
                    simulation, request.ownerReference());
            audit.record(actor, "ACCOUNT_CREATED", "Account", account.id().toString(),
                    Map.of("accountNumber", account.accountNumber(), "simulation", simulation));
            outbox.append("Account", account.id(), "AccountCreated",
                    Map.of("accountId", account.id(), "accountNumber", account.accountNumber()));
            return AccountView.of(account, java.math.BigDecimal.ZERO.setScale(Money.USD_SCALE));
        });
    }

    public AccountView get(UUID id) {
        Account account = require(id);
        return AccountView.of(account, ledger.projectedBalance(id));
    }

    public Account require(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account " + id + " not found"));
    }

    public PageResponse<AccountView> list(AccountStatus status, boolean includeSimulation, String search, int page, int size) {
        return accounts.page(status, includeSimulation, search, PageResponse.clampPage(page), PageResponse.clampSize(size));
    }

    public BalanceView balance(UUID id) {
        return runner.inSnapshot(() -> {
            Account account = require(id);
            var projected = ledger.projectedBalance(id);
            var derived = ledger.ledgerDerivedBalance(id);
            return new BalanceView(id, account.currency(), projected, derived,
                    projected.compareTo(derived) == 0, Instant.now());
        });
    }

    /**
     * Status changes lock the account's balance row, the same lock every posting takes.
     * A freeze therefore cannot interleave with an in-flight posting on that account:
     * the posting either commits first, or sees FROZEN and is rejected.
     */
    public AccountView changeStatus(UUID id, StatusChangeRequest request, String actor) {
        return runner.inTransaction(() -> {
            Map<UUID, LockedBalance> locked = ledger.lockBalances(List.of(id));
            Account account = require(id);
            if (account.systemAccount()) {
                throw ApiException.forbidden("SYSTEM_ACCOUNT", "System account status cannot be changed");
            }
            AccountStatus target = request.status();
            if (!account.status().canTransitionTo(target)) {
                throw ApiException.conflict("INVALID_STATUS_TRANSITION",
                        "Cannot change account status from " + account.status() + " to " + target);
            }
            var balance = locked.get(id).balance();
            if (target == AccountStatus.CLOSED && balance.signum() != 0) {
                throw ApiException.conflict("NONZERO_BALANCE",
                        "An account can only be closed with a zero balance (current balance " + Money.format(balance) + ")");
            }
            accounts.updateStatus(id, target);
            audit.record(actor, "ACCOUNT_STATUS_CHANGED", "Account", id.toString(),
                    Map.of("from", account.status().name(), "to", target.name(), "reason", request.reason()));
            outbox.append("Account", id, "AccountStatusChanged",
                    Map.of("accountId", id, "from", account.status().name(), "to", target.name()));
            return AccountView.of(require(id), balance);
        });
    }
}
