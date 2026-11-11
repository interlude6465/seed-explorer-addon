package me.seedexplorer.seedcracking;

/** The two vanilla random-spread offset modes. */
public enum SpreadType {
    /** One {@code nextInt(range)} call per axis. */
    LINEAR {
        @Override
        public int evaluate(Lcg48 random, int range) {
            return random.nextInt(range);
        }
    },

    /** Two calls per axis, averaged. Used by mansions, monuments, and End cities. */
    TRIANGULAR {
        @Override
        public int evaluate(Lcg48 random, int range) {
            return (random.nextInt(range) + random.nextInt(range)) / 2;
        }
    };

    public abstract int evaluate(Lcg48 random, int range);
}
