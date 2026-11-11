package me.seedexplorer.seedcracking;

/**
 * Exact 26.1-26.3 {@code XoroshiroRandomSource} subset needed by positional
 * random factories.
 *
 * <p>This class is dependency-free. It reproduces the seed upgrade, the
 * Xoroshiro128++ generator, positional forking, named factories, and
 * {@code nextFloat()} as used by {@code VerticalGradientCondition}.</p>
 */
public final class XoroshiroRandom263 {
    private final Xoroshiro128PlusPlus generator;

    public XoroshiroRandom263(long worldSeed) {
        RandomSupport263.Seed128 seed = RandomSupport263.upgradeSeedTo128bit(worldSeed);
        this.generator = new Xoroshiro128PlusPlus(seed.lo(), seed.hi());
    }

    public XoroshiroRandom263(long seedLo, long seedHi) {
        this.generator = new Xoroshiro128PlusPlus(seedLo, seedHi);
    }

    public long nextLong() {
        return generator.nextLong();
    }

    /** Mirrors {@code XoroshiroRandomSource.nextFloat()}. */
    public float nextFloat() {
        return (float) (nextLong() >>> 40) * 5.9604645E-8F;
    }

    /** Mirrors {@code XoroshiroRandomSource.nextDouble()}. */
    public double nextDouble() {
        return (double) (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * Mirrors {@code XoroshiroRandomSource.forkPositional()}: two
     * {@code nextLong()} calls seed a new positional factory.
     */
    public PositionalFactory forkPositional() {
        return new PositionalFactory(nextLong(), nextLong());
    }

    /**
     * Exact 26.1-26.3 {@code XoroshiroPositionalRandomFactory}.
     *
     * <p>{@code fromHashOf} uses the MD5-derived 128-bit seed XORed with the
     * factory seed, then constructs a new {@code XoroshiroRandomSource}.
     * {@code at(x, y, z)} XORs the position hash into the low half and keeps the
     * high half.</p>
     */
    public static final class PositionalFactory {
        private final long seedLo;
        private final long seedHi;

        public PositionalFactory(long seedLo, long seedHi) {
            this.seedLo = seedLo;
            this.seedHi = seedHi;
        }

        public XoroshiroRandom263 at(int x, int y, int z) {
            long positionSeed = MthSeed263.getSeed(x, y, z);
            return new XoroshiroRandom263(positionSeed ^ seedLo, seedHi);
        }

        public XoroshiroRandom263 fromHashOf(String name) {
            RandomSupport263.Seed128 hash = RandomSupport263.seedFromHashOf(name);
            return new XoroshiroRandom263(hash.lo() ^ seedLo, hash.hi() ^ seedHi);
        }

        public long seedLo() {
            return seedLo;
        }

        public long seedHi() {
            return seedHi;
        }
    }
}
