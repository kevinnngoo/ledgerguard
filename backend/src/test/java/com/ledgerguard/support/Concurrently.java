package com.ledgerguard.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Coordinated concurrent execution: every task is started on its own thread and parked on a
 * latch, then all are released at once so they genuinely contend for the same database rows.
 */
public final class Concurrently {

    private Concurrently() {}

    public static <T> List<T> run(List<Callable<T>> tasks) throws Exception {
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
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Threads did not start in time");
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    public static <T> List<T> repeat(int times, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            tasks.add(task);
        }
        return run(tasks);
    }
}
