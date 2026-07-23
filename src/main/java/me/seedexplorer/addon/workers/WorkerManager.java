/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.workers;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded worker pool for seed generation tasks. */
public final class WorkerManager {
    private static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int QUEUE_CAPACITY = 4096;
    private static final WorkerManager INSTANCE = new WorkerManager();

    private final ThreadPoolExecutor executor;

    private WorkerManager() {
        AtomicInteger threadIds = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "Seed Explorer Worker " + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        executor = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            factory,
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
    }

    public static WorkerManager get() {
        return INSTANCE;
    }

    /**
     * Queues background work if there is capacity.
     *
     * @return true when accepted, false when the queue is full
     */
    public boolean submit(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public int queuedTasks() {
        return executor.getQueue().size();
    }

    public int activeTasks() {
        return executor.getActiveCount();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
