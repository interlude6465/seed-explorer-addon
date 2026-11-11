package me.seedexplorer.seedcracking;

import java.util.Objects;

/** Controls for {@link UpperBitRecovery}. */
public final class RecoveryOptions {
    public static final long DEFAULT_MAX_CANDIDATES = 1L << 24;

    private final int maxResults;
    private final int threads;
    private final long timeBudgetMillis;
    private final long maxCandidates;
    private final ProgressListener progressListener;
    private final long progressIntervalMillis;

    private RecoveryOptions(Builder builder) {
        this.maxResults = builder.maxResults;
        this.threads = builder.threads;
        this.timeBudgetMillis = builder.timeBudgetMillis;
        this.maxCandidates = builder.maxCandidates;
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

    public long maxCandidates() {
        return maxCandidates;
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

    public static RecoveryOptions defaults() {
        return builder().build();
    }

    public static final class Builder {
        private int maxResults = 64;
        private int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        private long timeBudgetMillis = 120_000L;
        private long maxCandidates = DEFAULT_MAX_CANDIDATES;
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

        public Builder maxCandidates(long maxCandidates) {
            this.maxCandidates = maxCandidates;
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

        public RecoveryOptions build() {
            if (maxResults < 1) {
                throw new IllegalArgumentException("maxResults must be at least 1");
            }
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be at least 1");
            }
            if (timeBudgetMillis < 0) {
                throw new IllegalArgumentException("timeBudgetMillis must be non-negative");
            }
            if (maxCandidates < 1) {
                throw new IllegalArgumentException("maxCandidates must be positive");
            }
            if (progressIntervalMillis < 0) {
                throw new IllegalArgumentException("progressIntervalMillis must be non-negative");
            }
            Objects.requireNonNull(progressListener, "progressListener");
            return new RecoveryOptions(this);
        }
    }
}
