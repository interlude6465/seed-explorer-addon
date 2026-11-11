package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Controls for {@link StructureSeedLifter}.
 *
 * <p>{@code highStart}/{@code highEnd} let a caller shard the high-bit search
 * across machines or restrict a diagnostic run. The end value is exclusive.
 * Leaving {@code highEnd} negative searches the entire remaining space.</p>
 */
public final class LiftOptions {
    public static final long DEFAULT_MAX_HIGH_CANDIDATES = 1L << 32;

    private final int maxResults;
    private final int threads;
    private final long timeBudgetMillis;
    private final long highStart;
    private final long highEnd;
    private final long maxHighCandidates;
    private final EndPillarFingerprint pillarFingerprint;
    private final List<SlimeChunkObservation> slimeObservations;
    private final ProgressListener progressListener;
    private final long progressIntervalMillis;

    private LiftOptions(Builder builder) {
        this.maxResults = builder.maxResults;
        this.threads = builder.threads;
        this.timeBudgetMillis = builder.timeBudgetMillis;
        this.highStart = builder.highStart;
        this.highEnd = builder.highEnd;
        this.maxHighCandidates = builder.maxHighCandidates;
        this.pillarFingerprint = builder.pillarFingerprint;
        this.slimeObservations = List.copyOf(builder.slimeObservations);
        this.progressListener = builder.progressListener == null
            ? ProgressListener.NONE : builder.progressListener;
        this.progressIntervalMillis = builder.progressIntervalMillis;
    }

    public int maxResults() {
        return maxResults;
    }

    public int threads() {
        return threads;
    }

    public long timeBudgetMillis() {
        return timeBudgetMillis;
    }

    public long highStart() {
        return highStart;
    }

    public long highEnd() {
        return highEnd;
    }

    public long maxHighCandidates() {
        return maxHighCandidates;
    }

    public EndPillarFingerprint pillarFingerprint() {
        return pillarFingerprint;
    }

    public List<SlimeChunkObservation> slimeObservations() {
        return slimeObservations;
    }

    public ProgressListener progressListener() {
        return progressListener;
    }

    public long progressIntervalMillis() {
        return progressIntervalMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static LiftOptions defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int maxResults = 32;
        private int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        private long timeBudgetMillis = 120_000L;
        private long highStart = 0L;
        private long highEnd = -1L;
        private long maxHighCandidates = DEFAULT_MAX_HIGH_CANDIDATES;
        private EndPillarFingerprint pillarFingerprint;
        private List<SlimeChunkObservation> slimeObservations = List.of();
        private ProgressListener progressListener = ProgressListener.NONE;
        private long progressIntervalMillis = 250L;

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        public Builder timeBudgetMillis(long timeBudgetMillis) {
            this.timeBudgetMillis = timeBudgetMillis;
            return this;
        }

        public Builder highStart(long highStart) {
            this.highStart = highStart;
            return this;
        }

        /** Exclusive upper bound. Negative means the full remaining space. */
        public Builder highEnd(long highEnd) {
            this.highEnd = highEnd;
            return this;
        }

        public Builder highWindow(long startInclusive, long endExclusive) {
            this.highStart = startInclusive;
            this.highEnd = endExclusive;
            return this;
        }

        public Builder maxHighCandidates(long maxHighCandidates) {
            this.maxHighCandidates = maxHighCandidates;
            return this;
        }

        public Builder pillarFingerprint(EndPillarFingerprint pillarFingerprint) {
            this.pillarFingerprint = pillarFingerprint;
            return this;
        }

        public Builder slimeObservations(List<SlimeChunkObservation> slimeObservations) {
            this.slimeObservations = slimeObservations == null
                ? List.of() : slimeObservations;
            return this;
        }

        public Builder progressListener(ProgressListener progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        public Builder progressIntervalMillis(long progressIntervalMillis) {
            this.progressIntervalMillis = progressIntervalMillis;
            return this;
        }

        public LiftOptions build() {
            if (maxResults < 1) {
                throw new IllegalArgumentException("maxResults must be at least 1");
            }
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be at least 1");
            }
            if (timeBudgetMillis < 0) {
                throw new IllegalArgumentException("timeBudgetMillis must be non-negative");
            }
            if (highStart < 0) {
                throw new IllegalArgumentException("highStart must be non-negative");
            }
            if (highEnd >= 0 && highEnd <= highStart) {
                throw new IllegalArgumentException("highEnd must be greater than highStart");
            }
            if (maxHighCandidates < 1) {
                throw new IllegalArgumentException("maxHighCandidates must be positive");
            }
            if (progressIntervalMillis < 0) {
                throw new IllegalArgumentException("progressIntervalMillis must be non-negative");
            }
            Objects.requireNonNull(slimeObservations, "slimeObservations");
            return new LiftOptions(this);
        }
    }
}
