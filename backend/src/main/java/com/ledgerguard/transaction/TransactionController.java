package com.ledgerguard.transaction;

import com.ledgerguard.common.PageResponse;
import com.ledgerguard.transaction.TransactionDtos.DepositRequest;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.ReversalRequest;
import com.ledgerguard.transaction.TransactionDtos.TransactionResponse;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionDtos.WithdrawalRequest;
import com.ledgerguard.transaction.TransactionService.TransactionDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financial mutations require an {@code Idempotency-Key} header. Responses:
 * <ul>
 *   <li>201: posted.</li>
 *   <li>422: business rejection (recorded as a REJECTED transaction, e.g. INSUFFICIENT_FUNDS).</li>
 *   <li>Retrying with the same key and payload returns the ORIGINAL status and body with
 *       {@code Idempotent-Replayed: true}.</li>
 *   <li>Reusing a key with a different payload returns 422 IDEMPOTENCY_KEY_REUSED.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/deposits")
    @Operation(summary = "Simulated deposit from the external clearing account (OPERATOR)")
    public ResponseEntity<TransactionResponse> deposit(
            @Parameter(description = "Unique per logical operation; reuse it when retrying") @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody DepositRequest request, Principal principal) {
        return respond(service.deposit(request, key, principal.getName()));
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Simulated withdrawal to the external clearing account (OPERATOR)")
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody WithdrawalRequest request, Principal principal) {
        return respond(service.withdraw(request, key, principal.getName()));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Internal transfer between two customer accounts (OPERATOR)")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody TransferRequest request, Principal principal) {
        return respond(service.transfer(request, key, principal.getName()));
    }

    @PostMapping("/{id}/reversals")
    @Operation(summary = "Fully reverse a posted transaction with compensating entries (APPROVER; not the original initiator)")
    public ResponseEntity<TransactionResponse> reverse(
            @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_HEADER) String key,
            @Valid @RequestBody ReversalRequest request, Principal principal) {
        return respond(service.reverse(id, request, key, principal.getName()));
    }

    @GetMapping
    @Operation(summary = "Search transactions (newest first)")
    public PageResponse<TransactionRecord> list(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(new TransactionRepository.Filter(status, type, accountId, from, to, reference), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Transaction with its journal entries and reversal link")
    public TransactionDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    private static ResponseEntity<TransactionResponse> respond(OperationResult result) {
        return ResponseEntity.status(result.httpStatus())
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }
}
