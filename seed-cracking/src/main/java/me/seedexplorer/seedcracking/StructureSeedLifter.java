package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.Comparator;
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
 * Recovers the low-48 structure seed from observed random-spread structures.
 *
 * <p>This is the SeedCrackerX lifting idea, re-derived against 26.1+ random-spread
 * placement instead of copied from a Minecraft-version-specific library:</p>
 *
 * <ol>
 *   <li>For every linear structure set whose {@code range = spacing - separation}
 *       is divisible by four, {@code nextInt(range) % 4} (or {@code % 8} when
 *       range is divisible by eight) depends only on the low 19 or 20 bits of
 *       the structure seed when neither bounded draw takes a rejection path.</li>
 *   <li>Enumerate those low partial seeds and reject anything inconsistent with
 *       the observed offsets. Five or six independent structures usually leave
 *       one low partial seed.</li>
 *   <li>Enumerate the remaining high bits, apply cheap fingerprint filters
 *       (End pillars, slime chunks), then replay the exact version-matched
 *       placement for every observation. Only the true structure seed should survive all
 *       independent observations.</li>
 * </ol>
 *
 * <p>The search is parallelised but otherwise dependency-free. A time budget
 * and a total-candidate budget prevent an under-constrained observation set
 * from launching an unbounded brute force.</p>
 *
 * <p>This sieve does not enumerate rare bounded-RNG rejection paths. A completed
 * high-bit window therefore does not prove that all structure seeds were found.
 * Surviving candidates receive exact placement replay, but a genuine candidate
 * can be omitted by the initial sieve.</p>
 */
public final class StructureSeedLifter {
    private StructureSeedLifter() {
    }

    /**
     * Enumerates low partial seeds compatible with all lifting-capable
     * observations. This is useful for diagnostics and for sharding searches.
     */
    public static List<Long> findLowCandidates(List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        List<StructureObservation> lifting = liftingObservations(observations);
        int lowBits = maxLowSeedBits(lifting);
        if (lowBits == 0) {
            return List.of();
        }

        // Larger masks reject more candidates, so check them first.
        lifting.sort(Comparator.comparingInt(
            (StructureObservation observation) -> observation.placement().offsetMaskBits()).reversed());

        long limit = 1L << lowBits;
        Lcg48 random = new Lcg48();
        List<Long> result = new ArrayList<>();
        for (long low = 0; low < limit; low++) {
            if ((low & 4095L) == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Structure sieve cancelled");
            }
            boolean matches = true;
            for (StructureObservation observation : lifting) {
                if (!observation.lowBitsMatch(low, random)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                result.add(low);
            }
        }
        return result;
    }

    /** Estimates the work before running the expensive high-bit phase. */
    public static LiftEstimate estimate(List<StructureObservation> observations, LiftOptions options) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(options, "options");
        List<StructureObservation> lifting = liftingObservations(observations);
        int lowBits = maxLowSeedBits(lifting);
        if (lowBits == 0) {
            return new LiftEstimate(0, 0, 48, 0, 0, options.maxHighCandidates());
        }
        List<Long> lowCandidates = findLowCandidates(observations);
        int highBits = 48 - lowBits;
        long autoHigh = 1L << highBits;
        long highEnd = options.highEnd() < 0 ? autoHigh : Math.min(autoHigh, options.highEnd());
        long highWork = Math.max(0L, highEnd - options.highStart());
        long total = saturatedMultiply(lowCandidates.size(), highWork);
        return new LiftEstimate(lowBits, lowCandidates.size(), highBits, highWork, total,
            options.maxHighCandidates());
    }

    public static LiftResult lift(List<StructureObservation> observations, LiftOptions options) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(options, "options");

        long startNanos = System.nanoTime();
        List<StructureObservation> lifting = liftingObservations(observations);
        List<StructureObservation> exactOnly = new ArrayList<>(observations);
        exactOnly.removeIf(observation -> observation.placement().supportsLowBitLifting());

        int lowBits = maxLowSeedBits(lifting);
        if (lowBits == 0) {
            return new LiftResult(List.of(), LiftResult.Status.NEEDS_MORE_OBSERVATIONS,
                lifting.size(), exactOnly.size(), 0, 0, 0, 0, elapsedMillis(startNanos),
                "Need at least one linear, non-power-of-two structure set with (spacing - separation) "
                    + "divisible by 4 (shipwreck, ocean ruin, desert pyramid, igloo, jungle temple, "
                    + "swamp hut, or pillager outpost).");
        }

        List<Long> lowCandidates = findLowCandidates(observations);
        if (lowCandidates.isEmpty()) {
            return new LiftResult(List.of(), LiftResult.Status.NO_MATCH,
                lifting.size(), exactOnly.size(), lowBits, 0, 0, 0, elapsedMillis(startNanos),
                "No low partial seed is compatible with the observed offsets. Check that start chunks, "
                    + "not /locate block positions, were used.");
        }

        int highBits = 48 - lowBits;
        long autoHigh = 1L << highBits;
        long highStart = options.highStart();
        long highEnd = options.highEnd() < 0 ? autoHigh : Math.min(autoHigh, options.highEnd());
        if (highStart >= highEnd) {
            return new LiftResult(List.of(), LiftResult.Status.NO_MATCH,
                lifting.size(), exactOnly.size(), lowBits, lowCandidates.size(), 0, 0,
                elapsedMillis(startNanos), "The requested high-bit window is empty.");
        }

        long highWorkPerLow = highEnd - highStart;
        long totalHighCandidates = saturatedMultiply(lowCandidates.size(), highWorkPerLow);
        if (totalHighCandidates > options.maxHighCandidates()) {
            return new LiftResult(List.of(), LiftResult.Status.NEEDS_MORE_OBSERVATIONS,
                lifting.size(), exactOnly.size(), lowBits, lowCandidates.size(), 0, 0,
                elapsedMillis(startNanos),
                "Search would test " + totalHighCandidates + " high candidates (budget "
                    + options.maxHighCandidates() + "). Collect more lifting structures or explicitly "
                    + "raise maxHighCandidates / shard with highStart+highEnd. Estimate: "
                    + new LiftEstimate(lowBits, lowCandidates.size(), highBits, highWorkPerLow,
                    totalHighCandidates, options.maxHighCandidates()));
        }

        // The check order is by selectivity: smaller placement ranges reject more
        // candidates. Range 1 observations (buried treasure, mineshaft) convey no
        // placement information and naturally sort last.
        List<StructureObservation> checkOrder = new ArrayList<>(observations);
        checkOrder.sort(Comparator.comparingInt(observation -> observation.placement().range()));

        EndPillarFingerprint pillarFingerprint = options.pillarFingerprint();
        if (pillarFingerprint != null) {
            // Warm the cached 16-bit candidate set before the parallel phase.
            pillarFingerprint.spikeSeedCandidates();
        }
        List<SlimeChunkObservation> slimeObservations = new ArrayList<>(options.slimeObservations());
        // A positive slime observation rejects ~90% of candidates; a negative one
        // rejects only ~10%. Check positives first.
        slimeObservations.sort(Comparator.comparing(observation -> !observation.slime()));

        int threads = Math.max(1, options.threads());
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "seed-cracking-lifter");
            thread.setDaemon(true);
            return thread;
        });

        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger resultCount = new java.util.concurrent.atomic.AtomicInteger();
        AtomicLong tested = new AtomicLong();
        AtomicLong placementChecks = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong lastProgressNanos = new AtomicLong(startNanos);
        long deadlineNanos = options.timeBudgetMillis() == 0
            ? Long.MAX_VALUE
            : safeAdd(startNanos, TimeUnit.MILLISECONDS.toNanos(options.timeBudgetMillis()));
        long progressIntervalNanos = TimeUnit.MILLISECONDS.toNanos(options.progressIntervalMillis());

        List<Future<?>> futures = new ArrayList<>(threads);
        for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
            final int offset = threadIndex;
            futures.add(executor.submit(() -> {
                Lcg48 random = new Lcg48();
                long localTested = 0L;
                try {
                    for (long high = highStart + offset;
                         high < highEnd && !stopped.get();
                         high += threads) {
                        for (long low : lowCandidates) {
                            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                                break;
                            }
                            // Check before pillar/slime early exits as well as placement tests.
                            if ((localTested & 0xFFF) == 0 && System.nanoTime() >= deadlineNanos) {
                                stopped.set(true);
                                break;
                            }
                            long candidate = (high << lowBits) | low;
                            localTested++;

                            if (pillarFingerprint != null
                                && !pillarFingerprint.matchesStructureSeed(candidate)) {
                                continue;
                            }

                            boolean slimeMatches = true;
                            for (SlimeChunkObservation observation : slimeObservations) {
                                if (!observation.matches(candidate)) {
                                    slimeMatches = false;
                                    break;
                                }
                            }
                            if (!slimeMatches) {
                                continue;
                            }

                            boolean allMatch = true;
                            for (StructureObservation observation : checkOrder) {
                                placementChecks.incrementAndGet();
                                if (!observation.placementMatches(candidate, random)) {
                                    allMatch = false;
                                    break;
                                }
                            }
                            if (allMatch) {
                                int count=resultCount.incrementAndGet();
                                if(count<=options.maxResults()) results.add(candidate);
                                if (count >= options.maxResults()) {
                                    stopped.set(true);
                                    break;
                                }
                            }

                            if ((localTested & 0xFFF) == 0) {
                                long now = System.nanoTime();
                                if (now >= deadlineNanos) {
                                    stopped.set(true);
                                    break;
                                }
                                tested.addAndGet(4096L);
                                localTested -= 4096L;
                                maybePublishProgress(options, tested.get(), totalHighCandidates,
                                    results.size(), startNanos, lastProgressNanos, progressIntervalNanos);
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
            // The tasks honour the loop condition and deadline, so this is a
            // bounded wait rather than an open-ended join.
            long waitSeconds = options.timeBudgetMillis() == 0
                ? 3_600L
                : Math.max(5L, options.timeBudgetMillis() / 1000L + 5L);
            if (!executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                stopped.set(true);
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
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
            throw new IllegalStateException("A structure-placement check failed during seed lifting",
                workerFailure);
        }

        List<Long> seeds = new ArrayList<>(results);
        seeds.sort(Long::compareTo);
        long elapsed = elapsedMillis(startNanos);
        boolean budgetStopped = stopped.get() && seeds.size() < options.maxResults();

        maybePublishProgress(options, tested.get(), totalHighCandidates, seeds.size(),
            startNanos, lastProgressNanos, 0L);

        LiftResult.Status status;
        String message;
        if (!seeds.isEmpty()) {
            status = LiftResult.Status.FOUND;
            message = "Found " + seeds.size() + " structure-seed candidate(s) after testing "
                + tested.get() + " high candidate(s) and " + placementChecks.get()
                + " placement checks.";
            if (budgetStopped) {
                message += " The search stopped on its budget; raise maxResults or widen the budget "
                    + "if you expected more candidates.";
            }
        } else if (budgetStopped) {
            status = LiftResult.Status.BUDGET_EXHAUSTED;
            message = "No candidate found before the time or result budget expired. Tested "
                + tested.get() + " high candidate(s).";
        } else {
            status = LiftResult.Status.NO_MATCH;
            message = "Exhausted the requested high-bit window without a match. Tested "
                + tested.get() + " high candidate(s). Verify the observations or add another "
                + "independent structure.";
        }

        return new LiftResult(seeds, status, lifting.size(), exactOnly.size(), lowBits,
            lowCandidates.size(), tested.get(), placementChecks.get(), elapsed, message);
    }

    private static List<StructureObservation> liftingObservations(List<StructureObservation> observations) {
        List<StructureObservation> lifting = new ArrayList<>();
        for (StructureObservation observation : observations) {
            if (observation.placement().supportsLowBitLifting()) {
                lifting.add(observation);
            }
        }
        return lifting;
    }

    private static int maxLowSeedBits(List<StructureObservation> lifting) {
        int max = 0;
        for (StructureObservation observation : lifting) {
            max = Math.max(max, observation.placement().lowSeedBits());
        }
        return max;
    }

    private static void maybePublishProgress(LiftOptions options, long tested, long total,
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
            "structure-seed", tested, total, matches, elapsedMillis(startNanos)));
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
