package com.ledgerguard.simulation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ledgerguard.account.AccountDtos.CreateAccountRequest;
import com.ledgerguard.account.AccountService;
import com.ledgerguard.account.AccountView;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.LedgerGuardProperties;
import com.ledgerguard.common.Money;
import com.ledgerguard.ledger.IntegrityService;
import com.ledgerguard.ledger.LedgerRepository;
import com.ledgerguard.outbox.OutboxPublisher;
import com.ledgerguard.outbox.OutboxPublisher.EventStatus;
import com.ledgerguard.outbox.SimulatedBroker;
import com.ledgerguard.simulation.FailureInjector.FailurePoint;
import com.ledgerguard.transaction.TransactionDtos.DepositRequest;
import com.ledgerguard.transaction.TransactionDtos.OperationResult;
import com.ledgerguard.transaction.TransactionDtos.TransferRequest;
import com.ledgerguard.transaction.TransactionDtos.WithdrawalRequest;
import com.ledgerguard.transaction.TransactionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Simulation Lab scenarios. Each run creates its own isolated, flagged simulation accounts, so
 * scenarios never touch other records. Results are what actually happened in PostgreSQL:
 * nothing is scripted, and no request is assumed to win a race.
 */
@Service
@ConditionalOnProperty(prefix = "ledgerguard.simulation", name = "enabled", havingValue = "true")
public class SimulationService {

    private final AccountService accounts;
    private final TransactionService transactions;
    private final LedgerRepository ledger;
    private final IntegrityService integrity;
    private final FailureInjector failureInjector;
    private final OutboxPublisher outboxPublisher;
    private final SimulatedBroker broker;
    private final int maxConcurrent;

    public SimulationService(AccountService accounts, TransactionService transactions, LedgerRepository ledger,
                             IntegrityService integrity, FailureInjector failureInjector,
                             OutboxPublisher outboxPublisher, SimulatedBroker broker, LedgerGuardProperties properties) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.integrity = integrity;
        this.failureInjector = failureInjector;
        this.outboxPublisher = outboxPublisher;
        this.broker = broker;
        this.maxConcurrent = properties.simulation().maxConcurrentRequests();
    }

    public record Attempt(int index,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                          String outcome,
                          String reference,
                          String reason,
                          boolean replayed,
                          long latencyMs) {}

    public record ConcurrentWithdrawalResult(
            UUID accountId,
            String accountNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal startingBalance,
            List<Attempt> attempts,
            int posted,
            int rejected,
            int errors,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal finalBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ledgerDerivedBalance,
            boolean ledgerHealthy) {}

    /** N withdrawals released at the same instant against one account. */
    public ConcurrentWithdrawalResult concurrentWithdrawals(BigDecimal startingBalance, List<BigDecimal> amounts, String actor) {
        if (amounts == null || amounts.size() < 2 || amounts.size() > maxConcurrent) {
            throw ApiException.badRequest("INVALID_SIMULATION", "Provide between 2 and " + maxConcurrent + " withdrawal amounts");
        }
        BigDecimal start = Money.requireValidAmount(startingBalance);
        List<BigDecimal> normalized = amounts.stream().map(Money::requireValidAmount).toList();
        AccountView account = fundedAccount("Concurrent withdrawal", start, actor);

        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            int index = i;
            BigDecimal amount = normalized.get(i);
            tasks.add(() -> {
                long t0 = System.nanoTime();
                try {
                    OperationResult r = transactions.withdraw(
                            new WithdrawalRequest(account.id(), amount, null, "Simulation withdrawal #" + (index + 1)),
                            "sim-" + UUID.randomUUID(), actor);
                    return new Attempt(index + 1, amount, r.body().status().name(), r.body().reference(),
                            r.body().rejectionReason(), r.replayed(), elapsedMs(t0));
                } catch (RuntimeException e) {
                    return new Attempt(index + 1, amount, "ERROR", null, e.getMessage(), false, elapsedMs(t0));
                }
            });
        }
        List<Attempt> attempts = runConcurrently(tasks);

        int posted = (int) attempts.stream().filter(a -> a.outcome().equals("POSTED")).count();
        int rejected = (int) attempts.stream().filter(a -> a.outcome().equals("REJECTED")).count();
        int errors = attempts.size() - posted - rejected;
        return new ConcurrentWithdrawalResult(account.id(), account.accountNumber(), start, attempts, posted, rejected,
                errors, ledger.projectedBalance(account.id()), ledger.ledgerDerivedBalance(account.id()),
                integrity.check().healthy());
    }

    public record DuplicateSubmissionResult(
            int requests,
            int executed,
            int replayed,
            int errors,
            int distinctTransactionIds,
            int journalEntriesForTransaction,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceFinalBalance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal destinationFinalBalance,
            String idempotencyKey,
            List<Attempt> sample) {}

    /** The same transfer submitted N times concurrently with ONE idempotency key. */
    public DuplicateSubmissionResult duplicateSubmissions(BigDecimal amount, int requests, String actor) {
        if (requests < 2 || requests > maxConcurrent) {
            throw ApiException.badRequest("INVALID_SIMULATION", "requests must be between 2 and " + maxConcurrent);
        }
        BigDecimal value = Money.requireValidAmount(amount);
        AccountView source = fundedAccount("Duplicate source", value.multiply(BigDecimal.TEN), actor);
        AccountView destination = accounts.create(new CreateAccountRequest("SIM Duplicate destination", null, null), actor, true);
        String key = "sim-dup-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(source.id(), destination.id(), value, null, "Simulation duplicate transfer");

        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            int index = i;
            tasks.add(() -> {
                long t0 = System.nanoTime();
                try {
                    OperationResult r = transactions.transfer(request, key, actor);
                    return new Attempt(index + 1, value, r.body().status().name(), r.body().reference(),
                            r.body().id().toString(), r.replayed(), elapsedMs(t0));
                } catch (RuntimeException e) {
                    return new Attempt(index + 1, value, "ERROR", null, e.getMessage(), false, elapsedMs(t0));
                }
            });
        }
        List<Attempt> attempts = runConcurrently(tasks);

        Set<String> transactionIds = new HashSet<>();
        attempts.stream().filter(a -> !a.outcome().equals("ERROR")).forEach(a -> transactionIds.add(a.reason()));
        int executed = (int) attempts.stream().filter(a -> !a.outcome().equals("ERROR") && !a.replayed()).count();
        int replayed = (int) attempts.stream().filter(Attempt::replayed).count();
        int errors = attempts.size() - executed - replayed;
        int entries = transactionIds.size() == 1
                ? transactions.entries(UUID.fromString(transactionIds.iterator().next())).size()
                : -1;
        List<Attempt> sample = attempts.stream()
                .map(a -> new Attempt(a.index(), a.amount(), a.outcome(), a.reference(), null, a.replayed(), a.latencyMs()))
                .limit(20)
                .toList();
        return new DuplicateSubmissionResult(requests, executed, replayed, errors, transactionIds.size(), entries, value,
                ledger.projectedBalance(source.id()), ledger.projectedBalance(destination.id()), key, sample);
    }

    public record RollbackResult(
            String injectedFailure,
            boolean requestFailed,
            String errorMessage,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceBalanceBefore,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceBalanceAfter,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal destinationBalanceBefore,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal destinationBalanceAfter,
            long sourceEntriesBefore,
            long sourceEntriesAfter,
            long destinationEntriesAfter,
            boolean ledgerHealthy,
            String followUpTransferStatus) {}

    /** Fails a transfer after its first journal entry is written, then proves nothing was committed. */
    public RollbackResult rollback(BigDecimal amount, String actor) {
        BigDecimal value = Money.requireValidAmount(amount);
        AccountView source = fundedAccount("Rollback source", value.multiply(BigDecimal.TWO), actor);
        AccountView destination = accounts.create(new CreateAccountRequest("SIM Rollback destination", null, null), actor, true);
        BigDecimal srcBefore = ledger.projectedBalance(source.id());
        BigDecimal dstBefore = ledger.projectedBalance(destination.id());
        long srcEntriesBefore = ledger.entries(source.id(), 0, 1).totalItems();

        boolean failed = false;
        String message = null;
        failureInjector.armForCurrentThread(FailurePoint.AFTER_FIRST_ENTRY);
        try {
            transactions.transfer(new TransferRequest(source.id(), destination.id(), value, null, "Simulation rollback"),
                    "sim-rb-" + UUID.randomUUID(), actor);
        } catch (FailureInjector.InjectedFailureException e) {
            failed = true;
            message = e.getMessage();
        } finally {
            failureInjector.disarm();
        }

        BigDecimal srcAfter = ledger.projectedBalance(source.id());
        BigDecimal dstAfter = ledger.projectedBalance(destination.id());
        long srcEntriesAfter = ledger.entries(source.id(), 0, 1).totalItems();
        long dstEntriesAfter = ledger.entries(destination.id(), 0, 1).totalItems();
        boolean healthy = integrity.check().healthy();

        // The system stays operational: the same transfer succeeds once the fault is gone.
        OperationResult followUp = transactions.transfer(
                new TransferRequest(source.id(), destination.id(), value, null, "Simulation rollback follow-up"),
                "sim-rb-" + UUID.randomUUID(), actor);
        return new RollbackResult(FailurePoint.AFTER_FIRST_ENTRY.name(), failed, message, srcBefore, srcAfter, dstBefore,
                dstAfter, srcEntriesBefore, srcEntriesAfter, dstEntriesAfter, healthy, followUp.body().status().name());
    }

    public record EventRecoveryStep(String step, List<EventStatus> events) {}

    public record EventRecoveryResult(String transactionReference, String transactionStatus,
                                      List<EventRecoveryStep> timeline, boolean eventuallyPublished,
                                      long duplicateDeliveriesIgnored) {}

    /** Broker outage: the financial commit succeeds, the event waits in the outbox, then is delivered. */
    public EventRecoveryResult eventRecovery(BigDecimal amount, String actor) {
        BigDecimal value = Money.requireValidAmount(amount);
        AccountView account = accounts.create(new CreateAccountRequest("SIM Event recovery", null, null), actor, true);
        List<EventRecoveryStep> timeline = new ArrayList<>();
        broker.setAvailable(false);
        OperationResult deposit;
        try {
            deposit = transactions.deposit(new DepositRequest(account.id(), value, null, "Simulation event recovery"),
                    "sim-ev-" + UUID.randomUUID(), actor);
            UUID txId = deposit.body().id();
            timeline.add(new EventRecoveryStep("Broker DOWN; deposit committed", outboxPublisher.eventsForAggregate(txId)));
            outboxPublisher.publishPendingFor(txId);
            timeline.add(new EventRecoveryStep("Publish attempt while broker DOWN", outboxPublisher.eventsForAggregate(txId)));
        } finally {
            broker.setAvailable(true);
        }
        UUID txId = deposit.body().id();
        outboxPublisher.retryNow(txId);
        boolean published = false;
        for (int i = 0; i < 20 && !published; i++) {
            outboxPublisher.publishPendingFor(txId);
            published = outboxPublisher.eventsForAggregate(txId).stream().allMatch(EventStatus::published);
            if (!published) {
                sleep(100);
            }
        }
        timeline.add(new EventRecoveryStep("Broker UP; publisher retried", outboxPublisher.eventsForAggregate(txId)));
        return new EventRecoveryResult(deposit.body().reference(), deposit.body().status().name(), timeline, published,
                broker.duplicatesIgnored());
    }

    // ------------------------------------------------------------------------------------------

    private AccountView fundedAccount(String label, BigDecimal balance, String actor) {
        AccountView account = accounts.create(new CreateAccountRequest("SIM " + label, null, null), actor, true);
        transactions.deposit(new DepositRequest(account.id(), balance, null, "Simulation funding"),
                "sim-fund-" + UUID.randomUUID(), actor);
        return account;
    }

    /** Starts every task, then releases them through one gate so they hit the database together. */
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Simulation did not complete", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
