package com.ledgerguard.account;

import com.ledgerguard.account.AccountDtos.BalanceView;
import com.ledgerguard.account.AccountDtos.CreateAccountRequest;
import com.ledgerguard.account.AccountDtos.StatusChangeRequest;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.ledger.JournalEntry;
import com.ledgerguard.ledger.LedgerRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService service;
    private final LedgerRepository ledger;

    public AccountController(AccountService service, LedgerRepository ledger) {
        this.service = service;
        this.ledger = ledger;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a simulated customer cash account (OPERATOR)")
    public AccountView create(@Valid @RequestBody CreateAccountRequest request, Principal principal) {
        return service.create(request, principal.getName());
    }

    @GetMapping
    @Operation(summary = "List accounts with paging and filters")
    public PageResponse<AccountView> list(
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "false") boolean includeSimulation,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, includeSimulation, search, page, size);
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "Projected balance and independently ledger-derived balance from one snapshot")
    public BalanceView balance(@PathVariable UUID id) {
        return service.balance(id);
    }

    @GetMapping("/{id}/entries")
    @Operation(summary = "Journal entries for an account, newest first")
    public PageResponse<JournalEntry> entries(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        service.require(id);
        return ledger.entries(id, PageResponse.clampPage(page), PageResponse.clampSize(size));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Freeze, unfreeze or close an account (ADMIN)")
    public AccountView changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest request,
                                    Principal principal) {
        return service.changeStatus(id, request, principal.getName());
    }
}
