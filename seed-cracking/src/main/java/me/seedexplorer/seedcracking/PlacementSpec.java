package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * Exact model of a Minecraft 26.1+ {@code RandomSpreadStructurePlacement}.
 *
 * <p>The four inputs that affect placement are spacing, separation, salt, and
 * spread type. They are read from the vanilla structure-set JSON files in
 * {@link VanillaPlacements}. Frequency reduction and exclusion zones are
 * separate generation gates and are deliberately not modeled here: a chunk
 * matching this placement is a <em>necessary</em> but not always sufficient
 * condition for a structure to exist.</p>
 *
 * <p>The ordinary-draw low-bit sieve applies to linear placements whose
 * {@code range = spacing - separation} is divisible by four and is not a power
 * of two. In that case {@code nextInt(range) % 4} (or {@code % 8} when range is
 * divisible by eight) depends only on the low 19 or 20 bits of the structure
 * seed, provided bounded {@code nextInt} does not reject a draw. Rare rejection
 * paths depend on high seed bits and are not enumerated by this sieve, so it
 * must not be advertised as exhaustive. Full {@link #matches} remains exact.
 * Power-of-two ranges use the high bits of the LCG output instead and do
 * not have that property; triangular placements consume two calls and are also
 * excluded from the low-bit sieve.</p>
 */
public record PlacementSpec(
    String id,
    int spacing,
    int separation,
    int salt,
    SpreadType spreadType
) {
    public PlacementSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(spreadType, "spreadType");
        if (spacing <= 0) {
            throw new IllegalArgumentException("spacing must be positive: " + spacing);
        }
        if (separation < 0 || separation >= spacing) {
            throw new IllegalArgumentException(
                "separation must be in [0, spacing): spacing=" + spacing + ", separation=" + separation);
        }
    }

    /** The number of possible offsets on each axis. */
    public int range() {
        return spacing - separation;
    }

    public boolean powerOfTwoRange() {
        int range = range();
        return (range & (range - 1)) == 0;
    }

    /**
     * Number of offset bits determined by low seed bits on ordinary draws.
     *
     * <p>For a linear, non-power-of-two range divisible by four, the low
     * {@code m = numberOfTrailingZeros(range)} bits of {@code nextInt(range)}
     * depend only on the low {@code 17 + m} bits of the structure seed. This
     * follows from {@code next(31) = state >>> 17} and from one LCG step
     * propagating low seed bits upward.</p>
     */
    public int offsetMaskBits() {
        int range = range();
        if (spreadType != SpreadType.LINEAR || range <= 1 || powerOfTwoRange() || (range & 3) != 0) {
            return 0;
        }
        return Integer.numberOfTrailingZeros(range);
    }

    /** Number of low structure-seed bits constrained by the low-bit sieve. */
    public int lowSeedBits() {
        int maskBits = offsetMaskBits();
        return maskBits == 0 ? 0 : 17 + maskBits;
    }

    /** Mask applied to an observed offset for the low-bit sieve. */
    public long offsetMask() {
        int maskBits = offsetMaskBits();
        return maskBits == 0 ? 0L : (1L << maskBits) - 1L;
    }

    public boolean supportsLowBitLifting() {
        return lowSeedBits() > 0;
    }

    public int regionX(int chunkX) {
        return Math.floorDiv(chunkX, spacing);
    }

    public int regionZ(int chunkZ) {
        return Math.floorDiv(chunkZ, spacing);
    }

    /** Full random-spread potential-chunk computation. The supplied LCG is reseeded. */
    public ChunkCoordinate potentialChunk(long structureSeed, int regionX, int regionZ, Lcg48 random) {
        random.setLargeFeatureWithSalt(structureSeed, regionX, regionZ, salt);
        int offsetX = spreadType.evaluate(random, range());
        int offsetZ = spreadType.evaluate(random, range());
        return new ChunkCoordinate(regionX * spacing + offsetX, regionZ * spacing + offsetZ);
    }

    public ChunkCoordinate potentialChunk(long structureSeed, int regionX, int regionZ) {
        return potentialChunk(structureSeed, regionX, regionZ, new Lcg48());
    }

    /**
     * Fast exact check used by the candidate search. Avoids allocating a
     * ChunkCoordinate and returns as soon as the X axis differs.
     */
    public boolean matches(long structureSeed,
                           int regionX, int regionZ,
                           int chunkX, int chunkZ,
                           Lcg48 random) {
        random.setLargeFeatureWithSalt(structureSeed, regionX, regionZ, salt);
        int offsetX = spreadType.evaluate(random, range());
        if (regionX * spacing + offsetX != chunkX) {
            return false;
        }
        int offsetZ = spreadType.evaluate(random, range());
        return regionZ * spacing + offsetZ == chunkZ;
    }

    /**
     * Checks the ordinary-draw low-bit sieve. The supplied seed may be a partial
     * (low-bits-only) value. Rare nextInt rejection paths can produce false
     * negatives; use {@link #matches} for exact checks of complete candidates.
     */
    public boolean matchesLowBits(long partialStructureSeed,
                                  int regionX, int regionZ,
                                  int observedOffsetX, int observedOffsetZ,
                                  Lcg48 random) {
        if (!supportsLowBitLifting()) {
            throw new IllegalStateException("Placement does not support low-bit lifting: " + id);
        }
        int mask = (int) offsetMask();
        random.setLargeFeatureWithSalt(partialStructureSeed, regionX, regionZ, salt);
        int offsetX = spreadType.evaluate(random, range());
        if ((offsetX & mask) != (observedOffsetX & mask)) {
            return false;
        }
        int offsetZ = spreadType.evaluate(random, range());
        return (offsetZ & mask) == (observedOffsetZ & mask);
    }

    /** Creates an observation from a known structure start chunk. */
    public StructureObservation observe(int startChunkX, int startChunkZ) {
        return StructureObservation.at(this, startChunkX, startChunkZ);
    }

    public StructureObservation observe(String structureId, int startChunkX, int startChunkZ) {
        return StructureObservation.at(this, structureId, startChunkX, startChunkZ);
    }

    public String describeLifting() {
        if (!supportsLowBitLifting()) {
            return "exact-only";
        }
        return "low " + lowSeedBits() + " bits (offset % " + (1 << offsetMaskBits()) + ")";
    }

    @Override
    public String toString() {
        return id + "[spacing=" + spacing + ", separation=" + separation
            + ", salt=" + salt + ", type=" + spreadType + ", " + describeLifting() + "]";
    }
}
