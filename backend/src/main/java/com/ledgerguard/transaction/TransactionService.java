package com.ledgerguard.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountRepository;
import com.ledgerguard.account.AccountStatus;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.Maps;
import com.ledgerguard.common.Money;
import com.ledgerguard.common.PageResponse;
import com.ledgerguard.common.TransactionRunner;
import com.ledgerguard.idempotency.IdempotencyService;
import com.ledgerguard.idempotency.IdempotencyService.Claim;
import com.ledgerguard.idempotency.IdempotencyService.Fresh;
import com.ledgerguard.idempotency.IdempotencyService.Replay;
import com.ledgerguard.ledger.JournalEntry;
import com.ledgerguard.ledger.LedgerRepository;
import com.ledgerguard.ledger.LockedBalance;
import com.ledgerguard.ledger.PostingEngine;
import com.ledgerguard.ledger.PostingLeg;
import com.ledgerguard.outbox.OutboxRepository;
import com.ledgerguard.transaction.RiskEngine.Rejection;
import com.ledgerguard.transaction.TransactionDtos.DepositRequest;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.ReversalRequest;
import com.ledgerguard.transaction.TransactionDtos.TransactionResponse;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionDtos.WithdrawalRequest;
import com.ledgerguard.transaction.TransactionRepository.NewTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Processes deposits, withdrawals, transfers and reversals.
 *
 * <p>Every mutation runs as ONE database transaction:
 * <ol>
 *   <li>claim the idempotency key (or replay a committed result);</li>
 *   <li>lock every affected balance row in a consistent order;</li>
 *   <li>re-read account status and evaluate risk and funds rules under the lock;</li>
 *   <li>write the transaction header, balanced journal entries and balance projections;</li>
 *   <li>write the audit record and outbox event;</li>
 *   <li>store the response against the idempotency key;</li>
 *   <li>commit. Any failure before this point rolls back every step above.</li>
 * </ol>
 */
@Service
public class TransactionService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final PostingEngine posting;
    private final TransactionRepository transactions;
    private final IdempotencyService idempotency;
    private final RiskEngine risk;
    private final AuditService audit;
    private final OutboxRepository outbox;
    private final TransactionRunner runner;
    private final ObjectMapper json;
    private final MeterRegistry registry;

    public TransactionService(AccountRepository accounts, LedgerRepository ledger, PostingEngine posting,
                              TransactionRepository transactions, IdempotencyService idempotency, RiskEngine risk,
                              AuditService audit, OutboxRepository outbox, TransactionRunner runner,
                              ObjectMapper json, MeterRegistry registry) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.posting = posting;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.risk = risk;
        this.audit = audit;
        this.outbox = outbox;
        this.runner = runner;
        this.json = json;
        this.registry = registry;
    }

    private record Outcome(int status, TransactionRecord tx) {}

    // ------------------------------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------------------------------

    /** Deposit: DEBIT clearing (asset up), CREDIT customer (liability up). */
    public OperationResult deposit(DepositRequest r, String idempotencyKey, String actor) {
        BigDecimal amount = Money.requireValidAmount(r.amount());
        String currency = Money.requireSupportedCurrency(r.currency());
        String hash = IdempotencyService.fingerprint("DEPOSIT", r.accountId(), amount.toPlainString(), currency, r.description());
        return execute(TransactionType.DEPOSIT, actor, idempotencyKey, hash, () -> {
            Map<UUID, LockedBalance> locked = ledger.lockBalances(List.of(r.accountId(), Account.CLEARING_ACCOUNT_ID));
            Account destination = requireAccount(r.accountId());
            NewTransaction base = new NewTransaction(TransactionType.DEPOSIT, TransactionStatus.POSTED, amount, currency,
                    null, destination.id(), r.description(), null, null, null, actor);
            Optional<Rejection> rejection = risk.checkAccount(destination, "destination")
                    .or(() -> risk.checkLimits(amount, null));
            if (rejection.isPresent()) {
                return reject(base, rejection.get());
            }
            return post(base, List.of(
                    PostingLeg.debit(Account.CLEARING_ACCOUNT_ID, amount),
                    PostingLeg.credit(destination.id(), amount)), locked);
        });
    }

    /** Withdrawal: DEBIT customer (liability down), CREDIT clearing (asset down). */
    public OperationResult withdraw(WithdrawalRequest r, String idempotencyKey, String actor) {
        BigDecimal amount = Money.requireValidAmount(r.amount());
        String currency = Money.requireSupportedCurrency(r.currency());
        String hash = IdempotencyService.fingerprint("WITHDRAWAL", r.accountId(), amount.toPlainString(), currency, r.description());
        return execute(TransactionType.WITHDRAWAL, actor, idempotencyKey, hash, () -> {
            Map<UUID, LockedBalance> locked = ledger.lockBalances(List.of(r.accountId(), Account.CLEARING_ACCOUNT_ID));
            Account source = requireAccount(r.accountId());
            BigDecimal available = locked.get(source.id()).balance();
            NewTransaction base = new NewTransaction(TransactionType.WITHDRAWAL, TransactionStatus.POSTED, amount, currency,
                    source.id(), null, r.description(), null, null, null, actor);
            Optional<Rejection> rejection = risk.checkAccount(source, "source")
                    .or(() -> risk.checkLimits(amount, source.id()))
                    .or(() -> risk.checkFunds(available, amount));
            if (rejection.isPresent()) {
                return reject(base, rejection.get());
            }
            return post(base, List.of(
                    PostingLeg.debit(source.id(), amount),
                    PostingLeg.credit(Account.CLEARING_ACCOUNT_ID, amount)), locked);
        });
    }

    /** Internal transfer: DEBIT source customer (liability down), CREDIT destination customer (liability up). */
    public OperationResult transfer(TransferRequest r, String idempotencyKey, String actor) {
        BigDecimal amount = Money.requireValidAmount(r.amount());
        String currency = Money.requireSupportedCurrency(r.currency());
        if (r.sourceAccountId().equals(r.destinationAccountId())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination accounts must differ");
        }
        String hash = IdempotencyService.fingerprint("TRANSFER", r.sourceAccountId(), r.destinationAccountId(),
                amount.toPlainString(), currency, r.description());
        return execute(TransactionType.TRANSFER, actor, idempotencyKey, hash, () -> {
            Map<UUID, LockedBalance> locked = ledger.lockBalances(List.of(r.sourceAccountId(), r.destinationAccountId()));
            Account source = requireAccount(r.sourceAccountId());
            Account destination = requireAccount(r.destinationAccountId());
            BigDecimal available = locked.get(source.id()).balance();
            NewTransaction base = new NewTransaction(TransactionType.TRANSFER, TransactionStatus.POSTED, amount, currency,
                    source.id(), destination.id(), r.description(), null, null, null, actor);
            Optional<Rejection> rejection = risk.checkAccount(source, "source")
                    .or(() -> risk.checkAccount(destination, "destination"))
                    .or(() -> risk.checkLimits(amount, source.id()))
                    .or(() -> risk.checkFunds(available, amount));
            if (rejection.isPresent()) {
                return reject(base, rejection.get());
            }
            return post(base, List.of(
                    PostingLeg.debit(source.id(), amount),
                    PostingLeg.credit(destination.id(), amount)), locked);
        });
    }

    /**
     * Full reversal: a NEW journal transaction whose entries mirror the original with directions
     * flipped. The original entries are never modified. The original's row lock plus the
     * UNIQUE(reverses_transaction_id) constraint make a duplicate reversal impossible.
     */
    public OperationResult reverse(UUID originalId, ReversalRequest r, String idempotencyKey, String actor) {
        String hash = IdempotencyService.fingerprint("REVERSAL", originalId, r.reason());
        return execute(TransactionType.REVERSAL, actor, idempotencyKey, hash, () -> {
            TransactionRecord original = transactions.findByIdForUpdate(originalId)
                    .orElseThrow(() -> ApiException.notFound("Transaction " + originalId + " not found"));
            if (original.type() == TransactionType.REVERSAL) {
                throw ApiException.conflict("CANNOT_REVERSE_REVERSAL", "A reversal cannot itself be reversed");
            }
            if (original.status() == TransactionStatus.REVERSED) {
                throw ApiException.conflict("ALREADY_REVERSED", "Transaction " + original.reference() + " has already been reversed");
            }
            if (original.status() != TransactionStatus.POSTED) {
                throw ApiException.conflict("NOT_POSTED", "Only posted transactions can be reversed");
            }
            if (original.initiatedBy().equals(actor)) {
                throw ApiException.forbidden("SEPARATION_OF_DUTIES",
                        "The user who initiated a transaction cannot reverse it");
            }

            List<PostingLeg> legs = ledger.entriesForTransaction(originalId).stream()
                    .map(e -> new PostingLeg(e.accountId(), e.direction().opposite(), e.amount()))
                    .toList();
            Set<UUID> accountIds = new LinkedHashSet<>();
            legs.forEach(l -> accountIds.add(l.accountId()));
            Map<UUID, LockedBalance> locked = ledger.lockBalances(accountIds);

            for (UUID id : accountIds) {
                Account account = requireAccount(id);
                if (account.status() == AccountStatus.CLOSED) {
                    throw ApiException.conflict("ACCOUNT_CLOSED", "Account " + account.accountNumber() + " is closed");
                }
            }
            Map<UUID, BigDecimal> deltas = new LinkedHashMap<>();
            legs.forEach(l -> deltas.merge(l.accountId(), locked.get(l.accountId()).deltaFor(l), BigDecimal::add));
            deltas.forEach((id, delta) -> {
                LockedBalance b = locked.get(id);
                if (!b.allowNegative() && b.balance().add(delta).signum() < 0) {
                    throw ApiException.unprocessable("INSUFFICIENT_FUNDS_FOR_REVERSAL",
                            "Reversing would leave an account with a negative balance; the funds have already been moved");
                }
            });

            NewTransaction reversal = new NewTransaction(TransactionType.REVERSAL, TransactionStatus.POSTED,
                    original.amount(), original.currency(), original.destinationAccountId(), original.sourceAccountId(),
                    r.reason(), null, null, original.id(), actor);
            Outcome outcome = post(reversal, legs, locked);
            transactions.markReversed(original.id());
            audit.record(actor, "TRANSACTION_REVERSED", "Transaction", original.id().toString(),
                    Maps.of("reversalId", outcome.tx().id(), "reversalReference", outcome.tx().reference(), "reason", r.reason()));
            outbox.append("Transaction", original.id(), "TransactionReversed",
                    Maps.of("transactionId", original.id(), "reversalId", outcome.tx().id()));
            return outcome;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------

    public record TransactionDetail(TransactionResponse transaction, UUID reversedByTransactionId,
                                    String reversedByReference) {}

    public TransactionDetail get(UUID id) {
        TransactionRecord t = transactions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Transaction " + id + " not found"));
        Optional<TransactionRecord> reversal = transactions.findReversalOf(id);
        return new TransactionDetail(TransactionResponse.of(t, ledger.entriesForTransaction(id)),
                reversal.map(TransactionRecord::id).orElse(null),
                reversal.map(TransactionRecord::reference).orElse(null));
    }

    public PageResponse<TransactionRecord> list(TransactionRepository.Filter filter, int page, int size) {
        return transactions.page(filter, PageResponse.clampPage(page), PageResponse.clampSize(size));
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    private OperationResult execute(TransactionType type, String actor, String key, String hash, Supplier<Outcome> work) {
        IdempotencyService.validateKey(key);
        Timer.Sample sample = Timer.start(registry);
        String outcomeTag = "error";
        try {
            OperationResult result = runner.inTransaction(() -> {
                Claim claim = idempotency.claim(actor, key, type.name(), hash);
                if (claim instanceof Replay replay) {
                    return new OperationResult(replay.status(), readResponse(replay.body()), true);
                }
                Fresh fresh = (Fresh) claim;
                Outcome outcome = work.get();
                TransactionResponse response = TransactionResponse.of(outcome.tx(),
                        ledger.entriesForTransaction(outcome.tx().id()));
                idempotency.complete(fresh.recordId(), outcome.status(), writeResponse(response), outcome.tx().id());
                return new OperationResult(outcome.status(), response, false);
            });
            if (result.replayed()) {
                outcomeTag = "replayed";
            } else {
                outcomeTag = result.body().status() == TransactionStatus.REJECTED ? "rejected" : "posted";
            }
            return result;
        } finally {
            sample.stop(Timer.builder("ledgerguard.transaction.duration")
                    .tag("type", type.name())
                    .tag("outcome", outcomeTag)
                    .publishPercentileHistogram()
                    .register(registry));
            Counter.builder("ledgerguard.transactions")
                    .tag("type", type.name())
                    .tag("outcome", outcomeTag)
                    .register(registry)
                    .increment();
        }
    }

    private Outcome post(NewTransaction base, List<PostingLeg> legs, Map<UUID, LockedBalance> locked) {
        TransactionRecord tx = transactions.insert(base);
        posting.post(tx.id(), tx.currency(), legs, locked, tx.description());
        audit.record(base.initiatedBy(), "TRANSACTION_POSTED", "Transaction", tx.id().toString(),
                Maps.of("reference", tx.reference(), "type", tx.type().name(), "amount", Money.format(tx.amount())));
        outbox.append("Transaction", tx.id(), "TransactionPosted",
                Maps.of("transactionId", tx.id(), "reference", tx.reference(), "type", tx.type().name(),
                        "amount", Money.format(tx.amount()), "currency", tx.currency()));
        return new Outcome(201, tx);
    }

    private Outcome reject(NewTransaction base, Rejection rejection) {
        NewTransaction rejected = new NewTransaction(base.type(), TransactionStatus.REJECTED, base.amount(), base.currency(),
                base.sourceAccountId(), base.destinationAccountId(), base.description(), rejection.code(),
                rejection.reason(), base.reversesTransactionId(), base.initiatedBy());
        TransactionRecord tx = transactions.insert(rejected);
        audit.record(base.initiatedBy(), "TRANSACTION_REJECTED", "Transaction", tx.id().toString(),
                Maps.of("reference", tx.reference(), "code", rejection.code(), "reason", rejection.reason()));
        outbox.append("Transaction", tx.id(), "TransactionRejected",
                Maps.of("transactionId", tx.id(), "reference", tx.reference(), "code", rejection.code()));
        return new Outcome(422, tx);
    }

    private Account requireAccount(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account " + id + " not found"));
    }

    private String writeResponse(TransactionResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize transaction response", e);
        }
    }

    private TransactionResponse readResponse(String body) {
        try {
            return json.readValue(body, TransactionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    /** Entries of a stored response, exposed for tests and simulations. */
    public List<JournalEntry> entries(UUID transactionId) {
        return ledger.entriesForTransaction(transactionId);
    }
}
