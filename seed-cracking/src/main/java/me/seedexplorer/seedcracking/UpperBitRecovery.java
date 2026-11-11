package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Recovers the upper 16 bits of a world seed from one or more full-seed filters.
 *
 * <p>Structure placement, End pillars, slime chunks, and every other
 * {@code LegacyRandomSource} feature depend only on the low 48 bits. Once that
 * structure seed is known, the remaining 65,536 possibilities are
 * {@code (upper << 48) | structureSeed}. A feature that reads the complete
 * 64-bit seed - biomes, terrain, strongholds, bedrock, structure starts, or
 * loot - is therefore required to distinguish them.</p>
 *
 * <p>This class is the 16-bit extension stage from SeedCrackerX, isolated from
 * Minecraft so callers can plug in any oracle. Filters are evaluated in the
 * order supplied. Put the most selective filter first.</p>
 *
 * <p><b>Budget safety:</b> one structure seed always means 65,536 candidates.
 * The default {@link RecoveryOptions#maxCandidates()} allows 256 structure
 * seeds. A larger list is rejected before work starts rather than silently
 * searching only part of it.</p>
 */
public final class UpperBitRecovery {
    public static final int UPPER_BITS = 16;
    public static final int UPPER_VALUES = 1 << UPPER_BITS;
    public static final int UPPER_MASK = UPPER_VALUES - 1;

    private static final long WORK_CHUNK = 1L << 13;

    private UpperBitRecovery() {
    }

    /** Recovers the upper bits of one already-lifted structure seed. */
    public static RecoveryResult recover(long structureSeed,
                                         List<WorldSeedFilter> filters,
                                         RecoveryOptions options) {
        return recover(List.of(structureSeed), filters, options);
    }

    public static RecoveryResult recover(List<Long> structureSeeds,
                                         List<WorldSeedFilter> filters,
                                         RecoveryOptions options) {
        Objects.requireNonNull(structureSeeds, "structureSeeds");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(options, "options");
        for (WorldSeedFilter filter : filters) {
            Objects.requireNonNull(filter, "filter");
        }

        long startNanos = System.nanoTime();
        List<Long> seeds = distinctStructureSeeds(structureSeeds);
        if (seeds.isEmpty()) {
            return new RecoveryResult(List.of(), RecoveryResult.Status.NO_STRUCTURE_SEEDS,
                0, 0, 0, elapsedMillis(startNanos),
                "No structure seed was supplied. Lift the low 48 bits first or pass a known structure seed.");
        }
        if (filters.isEmpty()) {
            return new RecoveryResult(List.of(), RecoveryResult.Status.NEEDS_FILTERS,
                0, 0, 0, elapsedMillis(startNanos),
                "The upper 16 bits cannot be recovered from structure placement alone. Supply at least "
                    + "one full-seed filter such as a biome, terrain, stronghold, bedrock, structure-start, "
                    + "or loot oracle.");
        }

        long totalCandidates = saturatedMultiply(seeds.size(), UPPER_VALUES);
        if (totalCandidates > options.maxCandidates()) {
            return new RecoveryResult(List.of(), RecoveryResult.Status.BUDGET_EXHAUSTED,
                0, 0, 0, elapsedMillis(startNanos),
                "Upper-bit recovery would test " + totalCandidates + " candidates for " + seeds.size()
                    + " structure seed(s), above the maxCandidates budget " + options.maxCandidates()
                    + ". Add a more selective filter, reduce the structure-seed list, or raise the budget.");
        }

        long[] seedArray = new long[seeds.size()];
        for (int i = 0; i < seedArray.length; i++) {
            seedArray[i] = seeds.get(i);
        }
        WorldSeedFilter[] filterArray = filters.toArray(WorldSeedFilter[]::new);

        int threads = Math.max(1, options.threads());
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "seed-cracking-upper-bits");
            thread.setDaemon(true);
            return thread;
        });

        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();
        AtomicLong tested = new AtomicLong();
        AtomicLong evaluations = new AtomicLong();
        AtomicLong resultCount = new AtomicLong();
        AtomicLong nextChunk = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean budgetStopped = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong lastProgressNanos = new AtomicLong(startNanos);

        long deadlineNanos = options.timeBudgetMillis() == 0
            ? Long.MAX_VALUE
            : safeAdd(startNanos, TimeUnit.MILLISECONDS.toNanos(options.timeBudgetMillis()));
        long progressIntervalNanos = TimeUnit.MILLISECONDS.toNanos(options.progressIntervalMillis());

        List<Future<?>> futures = new ArrayList<>(threads);
        for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
            futures.add(executor.submit(() -> {
                long localTested = 0L;
                long localEvaluations = 0L;
                try {
                    while (!stopped.get()) {
                        long chunkStart = nextChunk.getAndAdd(WORK_CHUNK);
                        if (chunkStart >= totalCandidates) {
                            break;
                        }
                        long chunkEnd = Math.min(totalCandidates, chunkStart + WORK_CHUNK);
                        for (long index = chunkStart; index < chunkEnd; index++) {
                            if (stopped.get() || Thread.currentThread().isInterrupted()) break;
                            // A biome oracle can be expensive; bound every candidate, not
                            // merely every 16,384 candidates as in the batch-only engine.
                            if (System.nanoTime() >= deadlineNanos) {
                                budgetStopped.set(true);
                                stopped.set(true);
                                break;
                            }

                            int structureIndex = (int) (index >>> UPPER_BITS);
                            int upperBits = (int) (index & UPPER_MASK);
                            long worldSeed = combine(seedArray[structureIndex], upperBits);
                            localTested++;

                            boolean matches = true;
                            for (WorldSeedFilter filter : filterArray) {
                                localEvaluations++;
                                if (!filter.matches(worldSeed)) {
                                    matches = false;
                                    break;
                                }
                            }

                            if (matches) {
                                long count = resultCount.incrementAndGet();
                                if (count <= options.maxResults()) {
                                    results.add(worldSeed);
                                }
                                if (count >= options.maxResults()) {
                                    stopped.set(true);
                                    break;
                                }
                            }
                        }

                        long totalTested = tested.addAndGet(localTested);
                        localTested = 0L;
                        long totalEvaluations = evaluations.addAndGet(localEvaluations);
                        localEvaluations = 0L;
                        publishProgress(options, totalTested, totalCandidates,
                            resultCount.get(), startNanos, lastProgressNanos, progressIntervalNanos);
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                    stopped.set(true);
                } finally {
                    tested.addAndGet(localTested);
                    evaluations.addAndGet(localEvaluations);
                }
            }));
        }

        executor.shutdown();
        try {
            long waitSeconds = options.timeBudgetMillis() == 0
                ? 3_600L
                : Math.max(5L, options.timeBudgetMillis() / 1000L + 5L);
            if (!executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                budgetStopped.set(true);
                stopped.set(true);
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            budgetStopped.set(true);
            stopped.set(true);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
                // The first worker failure is rethrown below.
            }
        }

        Throwable workerFailure = failure.get();
        if (workerFailure != null) {
            throw new IllegalStateException("A world-seed filter failed during upper-bit recovery", workerFailure);
        }

        List<Long> worldSeeds = new ArrayList<>(results);
        worldSeeds.sort(Long::compareTo);
        long testedCount = tested.get();
        int seedsTested = (int) Math.min(seedArray.length,
            (testedCount + UPPER_VALUES - 1L) / UPPER_VALUES);
        long elapsed = elapsedMillis(startNanos);

        publishProgress(options, testedCount, totalCandidates, worldSeeds.size(),
            startNanos, lastProgressNanos, 0L);

        if (!worldSeeds.isEmpty()) {
            String message = "Found " + worldSeeds.size() + " full world seed(s) after testing "
                + testedCount + " of " + totalCandidates + " candidate(s) with "
                + evaluations.get() + " filter evaluation(s).";
            if (budgetStopped.get()) {
                message += " The search stopped on its budget; the result list may be incomplete.";
            }
            return new RecoveryResult(worldSeeds, RecoveryResult.Status.FOUND, seedsTested,
                testedCount, evaluations.get(), elapsed, message);
        }
        if (budgetStopped.get()) {
            return new RecoveryResult(List.of(), RecoveryResult.Status.BUDGET_EXHAUSTED, seedsTested,
                testedCount, evaluations.get(), elapsed,
                "No world seed was found before the time or result budget expired. Tested "
                    + testedCount + " candidate(s).");
        }
        return new RecoveryResult(List.of(), RecoveryResult.Status.NO_MATCH, seedsTested,
            testedCount, evaluations.get(), elapsed,
            "Exhausted all " + totalCandidates + " upper-half candidate(s) without a match. Verify the "
                + "filters or add another independent full-seed observation.");
    }

    /** Combines a low-48 structure seed with a 16-bit upper part. */
    public static long combine(long structureSeed, int upperBits) {
        return Lcg48.worldSeed(structureSeed, upperBits);
    }

    /** Low 48 bits of a world seed. */
    public static long structureSeed(long worldSeed) {
        return Lcg48.structureSeed(worldSeed);
    }

    /** Upper 16 bits of a world seed, as an unsigned value. */
    public static int upperBits(long worldSeed) {
        return (int) ((worldSeed >>> 48) & UPPER_MASK);
    }

    /** Masks, de-duplicates, and preserves order. */
    public static List<Long> distinctStructureSeeds(List<Long> structureSeeds) {
        Objects.requireNonNull(structureSeeds, "structureSeeds");
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long seed : structureSeeds) {
            if (seed == null) {
                throw new IllegalArgumentException("structure seed must not be null");
            }
            unique.add(seed & Lcg48.MASK);
        }
        return List.copyOf(unique);
    }

    private static void publishProgress(RecoveryOptions options, long tested, long total,
                                        long matches, long startNanos,
                                        AtomicLong lastProgressNanos, long intervalNanos) {
        if (options.progressListener() == ProgressListener.NONE) {
            return;
        }
        long now = System.nanoTime();
        if (intervalNanos > 0 && now - lastProgressNanos.get() < intervalNanos) {
            return;
        }
        long previous = lastProgressNanos.getAndSet(now);
        if (intervalNanos > 0 && now - previous < intervalNanos) {
            return;
        }
        options.progressListener().onProgress(new CrackingProgress(
            "upper-bits", tested, total, matches, elapsedMillis(startNanos)));
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0 || right == 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
