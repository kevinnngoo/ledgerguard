package com.ledgerguard.ledger;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.ledger.IntegrityService.IntegrityReport;
import com.ledgerguard.ledger.IntegrityService.RebuildResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerRepository ledger;
    private final IntegrityService integrity;

    public LedgerController(LedgerRepository ledger, IntegrityService integrity) {
        this.ledger = ledger;
        this.integrity = integrity;
    }

    @GetMapping("/entries")
    @Operation(summary = "Ledger explorer: journal entries, newest first, optionally for one account")
    public PageResponse<JournalEntry> entries(@RequestParam(required = false) UUID accountId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return ledger.entries(accountId, PageResponse.clampPage(page), PageResponse.clampSize(size));
    }

    @GetMapping("/integrity")
    @Operation(summary = "Verify every ledger invariant from one consistent snapshot")
    public IntegrityReport integrity() {
        return integrity.check();
    }

    @PostMapping("/projections/rebuild")
    @Operation(summary = "Rebuild balance projections from the authoritative ledger (ADMIN)")
    public RebuildResult rebuild(Principal principal) {
        return integrity.rebuildProjections(principal.getName());
    }
}
