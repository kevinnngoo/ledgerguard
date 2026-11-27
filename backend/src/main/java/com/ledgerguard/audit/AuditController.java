package com.ledgerguard.audit;

import com.ledgerguard.audit.AuditService.AuditEvent;
import com.ledgerguard.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Audit trail, newest first (AUDITOR or ADMIN)")
    public PageResponse<AuditEvent> list(@RequestParam(required = false) String resourceType,
                                         @RequestParam(required = false) String resourceId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return audit.list(resourceType, resourceId, PageResponse.clampPage(page), PageResponse.clampSize(size));
    }
}
