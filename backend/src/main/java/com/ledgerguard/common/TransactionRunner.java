package com.ledgerguard.common;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a database transaction and retries it when PostgreSQL reports a transient
 * concurrency failure (deadlock 40P01, serialization failure 40001, lock timeout).
 *
 * <p>Retrying is safe ONLY because the failed attempt was rolled back in full: its ledger
 * entries, balance updates and idempotency claim all disappear together. A retry is therefore
 * a brand-new attempt, never a second financial effect.
 */
@Component
public class TransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(TransactionRunner.class);
    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate readWrite;
    private final TransactionTemplate snapshot;

    public TransactionRunner(PlatformTransactionManager transactionManager) {
        this.readWrite = new TransactionTemplate(transactionManager);
        this.readWrite.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    public <T> T inTransaction(Supplier<T> work) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return readWrite.execute(status -> work.get());
            } catch (ConcurrencyFailureException e) {
                last = e;
                log.warn("Transient concurrency failure on attempt {}/{}; retrying: {}",
                        attempt, MAX_ATTEMPTS, e.getMostSpecificCause().getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        throw last;
    }

    /** Read-only work that must see one consistent snapshot of the database (e.g. integrity checks). */
    public <T> T inSnapshot(Supplier<T> work) {
        return snapshot.execute(status -> work.get());
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
