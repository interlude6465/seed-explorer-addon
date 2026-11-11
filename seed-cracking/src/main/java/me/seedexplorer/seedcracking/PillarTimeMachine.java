package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;

/**
 * SeedCrackerX-style pillar "time machine" for structure-seed search.
 *
 * <p>The End spike key is the low 16 bits of the first
 * {@code LegacyRandomSource.nextLong()} of the world seed. A {@code nextLong}
 * is two 32-bit LCG outputs, so the spike key occupies bits 16..31 of the raw
 * LCG state after two forward steps. SeedCrackerX exploits this by:</p>
 *
 * <ol>
 *   <li>Building a raw state whose low and high 16-bit halves come from a
 *       32-bit search variable and whose middle 16 bits are the observed pillar
 *       seed.</li>
 *   <li>Reversing two LCG steps.</li>
 *   <li>XORing the Java multiplier to turn the raw state into a user-facing
 *       structure seed.</li>
 * </ol>
 *
 * <p>The result is a low-48 structure seed whose pillar key is exactly the
 * supplied 16-bit value. It is not a full world seed. Testing the candidate
 * against structure starts removes the false positives, exactly as in
 * SeedCrackerX's {@code TimeMachine}.</p>
 *
 * <p>The search variable is 32 bits, so a complete search is 2^32 candidates.
 * Use {@link #shardStart(int, int)} and {@link #searchRange} to split that work
 * across threads or machines. The four-shard layout matches SeedCrackerX's
 * 4 x 2^30 worker split.</p>
 */
public final class PillarTimeMachine {
    /** The 32-bit search space of the SeedCrackerX time machine. */
    public static final long PARTIAL_SEED_SPACE = 1L << 32;
    /** Number of 2^30 shards SeedCrackerX starts by default. */
    public static final int DEFAULT_SHARDS = 4;
    public static final long DEFAULT_SHARD_SIZE = PARTIAL_SEED_SPACE / DEFAULT_SHARDS;

    private static final long PROGRESS_STEP = 1L << 20;
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private PillarTimeMachine() {
    }

    /**
     * Exact SeedCrackerX {@code TimeMachine.timeMachine(partialWorldSeed,
     * pillarSeed)} calculation.
     *
     * @param partialSeed a 32-bit search value; only its low 32 bits are used
     * @param pillarSeed  the observed 16-bit End spike key
     * @return a low-48 structure seed with that pillar key
     */
    public static long candidateStructureSeed(long partialSeed, int pillarSeed) {
        long partial = partialSeed & 0xFFFFFFFFL;
        long state = ((partial & 0xFFFF0000L) << 16)
            | ((long) (pillarSeed & 0xFFFF) << 16)
            | (partial & 0xFFFFL);
        state = Lcg48.previousState(state, 2);
        return (state ^ Lcg48.MULTIPLIER) & Lcg48.MASK;
    }

    /** Returns the 16-bit End spike key of a low-48 structure seed. */
    public static int pillarSeedForStructureSeed(long structureSeed) {
        return EndPillarFingerprint.spikeSeedForStructureSeed(structureSeed);
    }

    /** Checks a structure seed against a known 16-bit pillar key. */
    public static boolean matchesPillarSeed(long structureSeed, int pillarSeed) {
        return pillarSeedForStructureSeed(structureSeed) == (pillarSeed & 0xFFFF);
    }

    /** Keeps only the structure seeds whose pillar key matches. */
    public static List<Long> filterByPillarSeed(Iterable<Long> structureSeeds, int pillarSeed) {
        Objects.requireNonNull(structureSeeds, "structureSeeds");
        List<Long> result = new ArrayList<>();
        for (Long seed : structureSeeds) {
            if (seed == null) {
                throw new IllegalArgumentException("structure seed must not be null");
            }
            long candidate = seed & Lcg48.MASK;
            if (matchesPillarSeed(candidate, pillarSeed)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Start index of a shard, in [0, 2^32). */
    public static long shardStart(int shardIndex, int shardCount) {
        validateShard(shardIndex, shardCount);
        return (PARTIAL_SEED_SPACE / shardCount) * shardIndex
            + Math.min(shardIndex, (int) (PARTIAL_SEED_SPACE % shardCount));
    }

    /** Exclusive end index of a shard, in (0, 2^32]. */
    public static long shardEnd(int shardIndex, int shardCount) {
        validateShard(shardIndex, shardCount);
        return shardStart(shardIndex + 1, shardCount);
    }

    /**
     * Scans a half-open partial-seed range and tests candidates with the
     * supplied predicate.
     *
     * @param filter              optional candidate filter; {@code null} accepts all
     * @param maxResults          stop after this many matches
     * @param timeBudgetMillis    zero means no time limit
     * @param progressListener    may be {@link ProgressListener#NONE}
     */
    public static List<Long> searchRange(int pillarSeed,
                                         long startInclusive,
                                         long endExclusive,
                                         LongPredicate filter,
                                         int maxResults,
                                         long timeBudgetMillis,
                                         ProgressListener progressListener,
                                         long progressIntervalMillis) {
        if (startInclusive < 0 || endExclusive < startInclusive || endExclusive > PARTIAL_SEED_SPACE) {
            throw new IllegalArgumentException("range must satisfy 0 <= start <= end <= 2^32");
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be at least 1");
        }
        if (timeBudgetMillis < 0) {
            throw new IllegalArgumentException("timeBudgetMillis must be non-negative");
        }
        if (progressIntervalMillis < 0) {
            throw new IllegalArgumentException("progressIntervalMillis must be non-negative");
        }
        Objects.requireNonNull(progressListener, "progressListener");

        long startNanos = System.nanoTime();
        long deadline = timeBudgetMillis == 0
            ? Long.MAX_VALUE
            : safeAdd(startNanos, TimeUnit.MILLISECONDS.toNanos(timeBudgetMillis));
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(progressIntervalMillis);
        long lastProgressNanos = startNanos;
        long total = endExclusive - startInclusive;
        long tested = 0L;
        List<Long> results = new ArrayList<>();

        for (long partial = startInclusive; partial < endExclusive; partial++) {
            long candidate = candidateStructureSeed(partial, pillarSeed);
            tested++;
            if (filter == null || filter.test(candidate)) {
                results.add(candidate);
                if (results.size() >= maxResults) {
                    break;
                }
            }

            if ((tested & (PROGRESS_STEP - 1L)) == 0) {
                long now = System.nanoTime();
                if (now >= deadline) {
                    break;
                }
                if (intervalNanos == 0 || now - lastProgressNanos >= intervalNanos) {
                    lastProgressNanos = now;
                    progressListener.onProgress(new CrackingProgress(
                        "pillar-time-machine", tested, total, results.size(),
                        TimeUnit.NANOSECONDS.toMillis(now - startNanos)));
                }
            }
        }

        progressListener.onProgress(new CrackingProgress(
            "pillar-time-machine", tested, total, results.size(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)));
        return results;
    }

    /** Scans the whole 2^32 search space. Prefer sharded calls for real work. */
    public static List<Long> search(int pillarSeed, LongPredicate filter, int maxResults) {
        return searchRange(pillarSeed, 0L, PARTIAL_SEED_SPACE, filter, maxResults,
            0L, ProgressListener.NONE, 250L);
    }

    /**
     * Searches a half-open partial-seed range in parallel.
     *
     * <p>The predicate may be called concurrently and must be thread-safe.
     * Results are sorted before return. A false {@code rangeExhausted} value
     * means the result list may be incomplete because the result or time budget
     * stopped the search.</p>
     */
    public static PillarSearchResult searchRangeParallel(
        int pillarSeed,
        long startInclusive,
        long endExclusive,
        LongPredicate filter,
        int maxResults,
        int threads,
        long timeBudgetMillis
    ) {
        if (startInclusive < 0 || endExclusive < startInclusive
            || endExclusive > PARTIAL_SEED_SPACE) {
            throw new IllegalArgumentException("range must satisfy 0 <= start <= end <= 2^32");
        }
        if (maxResults < 1 || threads < 1 || timeBudgetMillis < 0) {
            throw new IllegalArgumentException("maxResults/threads must be positive and time budget non-negative");
        }
        Objects.requireNonNull(filter, "filter");

        long total = endExclusive - startInclusive;
        long startNanos = System.nanoTime();
        long deadline = timeBudgetMillis == 0 ? Long.MAX_VALUE
            : safeAdd(startNanos, TimeUnit.MILLISECONDS.toNanos(timeBudgetMillis));
        AtomicLong next = new AtomicLong(startInclusive);
        AtomicLong tested = new AtomicLong();
        AtomicInteger resultCount = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "seed-cracking-pillar-search");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>(threads);

        for (int worker = 0; worker < threads; worker++) {
            futures.add(executor.submit(() -> {
                long localTested = 0L;
                try {
                    while (!stopped.get()) {
                        long chunkStart = next.getAndAdd(1L << 12);
                        if (chunkStart >= endExclusive) break;
                        long chunkEnd = Math.min(endExclusive, chunkStart + (1L << 12));
                        for (long partial = chunkStart; partial < chunkEnd && !stopped.get(); partial++) {
                            long candidate = candidateStructureSeed(partial, pillarSeed);
                            localTested++;
                            if (filter.test(candidate)
                                && resultCount.getAndIncrement() < maxResults) {
                                results.add(candidate);
                                if (resultCount.get() >= maxResults) stopped.set(true);
                            }
                            if ((localTested & 0x3FFL) == 0L && System.nanoTime() >= deadline) {
                                timedOut.set(true);
                                stopped.set(true);
                            }
                        }
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                    stopped.set(true);
                } finally {
                    tested.addAndGet(localTested);
                }
            }));
        }

        executor.shutdown();
        try {
            long waitSeconds = timeBudgetMillis == 0 ? 3_600L
                : Math.max(5L, timeBudgetMillis / 1000L + 5L);
            if (!executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                timedOut.set(true);
                stopped.set(true);
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            timedOut.set(true);
            stopped.set(true);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
                // Worker exceptions are rethrown from the shared failure slot.
            }
        }
        Throwable workerFailure = failure.get();
        if (workerFailure != null) {
            throw new IllegalStateException("Pillar candidate filter failed during search", workerFailure);
        }

        List<Long> found = new ArrayList<>(results);
        found.sort(Long::compareTo);
        boolean exhausted = !timedOut.get() && tested.get() >= total;
        return new PillarSearchResult(found, tested.get(), exhausted, timedOut.get());
    }

    private static void validateShard(int shardIndex, int shardCount) {
        if (shardCount < 1 || shardCount > PARTIAL_SEED_SPACE) {
            throw new IllegalArgumentException("shardCount must be in [1, 2^32]: " + shardCount);
        }
        if (shardIndex < 0 || shardIndex > shardCount) {
            throw new IllegalArgumentException("shardIndex must be in [0, shardCount]: " + shardIndex);
        }
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
