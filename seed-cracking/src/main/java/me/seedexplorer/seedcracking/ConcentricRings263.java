package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact Minecraft 26.1-26.3 stronghold concentric-ring geometry, plus a
 * biome-gate hook.
 *
 * <p>Minecraft 26.1-26.3 {@code ChunkGeneratorStructureState.generateRingPositions}
 * uses a {@code LegacyRandomSource} seeded with {@code concentricRingsSeed},
 * which is the level seed. This class reproduces the angle, radius jitter, ring spread
 * growth, and per-candidate {@code fork()} consumption. The fork is consumed
 * even when no biome gate is supplied, because skipping it would change every
 * later ring position.</p>
 *
 * <p>The geometry alone produces the <em>search centre</em> chunk for each
 * stronghold. The actual stronghold chunk comes from
 * {@code BiomeSource.findBiomeHorizontal(centerX, 0, centerZ, 112,
 * preferredBiomePredicate, fork, randomState)}. Supply a {@link BiomeGate} that
 * performs that search against a version-matched biome oracle; return
 * {@code null} to use vanilla's fallback (the ring centre). Without a gate, the results are ring
 * candidates, not guaranteed stronghold locations.</p>
 */
public final class ConcentricRings263 {
    public static final int DISTANCE = 32;
    public static final int SPREAD = 3;
    public static final int COUNT = 128;
    public static final int SALT = 0;
    /** Biome search radius in blocks, from the vanilla stronghold placement JSON. */
    public static final int BIOME_SEARCH_RADIUS = 112;
    /** Block offset from a chunk coordinate to its centre. */
    public static final int CHUNK_CENTER = 8;

    private static final double TWO_PI = Math.PI * 2.0;

    private ConcentricRings263() {
    }

    /**
     * Hook for the biome-dependent final step.
     *
     * <p>The supplied random is the exact {@code LegacyRandomSource.fork()}
     * state that vanilla passes to {@code findBiomeHorizontal}. The gate should
     * return the chunk containing the found biome position, or {@code null} to
     * fall back to the ring centre.</p>
     */
    @FunctionalInterface
    public interface BiomeGate {
        ChunkCoordinate locate(int originChunkX, int originChunkZ, Lcg48 fork);
    }

    /**
     * One ring search centre.
     *
     * @param biomeForkSeed the user-facing long consumed by vanilla's
     *                      {@code RandomSource.fork()}; recreate the fork with
     *                      {@code new Lcg48(biomeForkSeed)}
     */
    public record RingCandidate(
        int originChunkX,
        int originChunkZ,
        int ringIndex,
        int indexInRing,
        int generatedIndex,
        long biomeForkSeed
    ) {
        public RingCandidate {
            if (ringIndex < 0 || indexInRing < 0 || generatedIndex < 0) {
                throw new IllegalArgumentException("ring metadata must be non-negative");
            }
        }

        public ChunkCoordinate originChunk() {
            return new ChunkCoordinate(originChunkX, originChunkZ);
        }

        /** Applies the biome gate, falling back to the ring centre. */
        public ChunkCoordinate locate(BiomeGate gate) {
            Objects.requireNonNull(gate, "gate");
            ChunkCoordinate found = gate.locate(originChunkX, originChunkZ, new Lcg48(biomeForkSeed));
            return found == null ? originChunk() : found;
        }
    }

    /** Full geometric ring sequence for a world seed, without biome filtering. */
    public static List<RingCandidate> ringCandidates(long worldSeed) {
        Lcg48 random = new Lcg48(worldSeed);
        List<RingCandidate> result = new ArrayList<>(COUNT);

        double angle = random.nextDouble() * TWO_PI;
        int ringIndex = 0;
        int indexInRing = 0;
        int spread = SPREAD;

        for (int generatedIndex = 0; generatedIndex < COUNT; generatedIndex++) {
            double radius = 4.0 * DISTANCE
                + (double) DISTANCE * ringIndex * 6.0
                + (random.nextDouble() - 0.5) * DISTANCE * 2.5;
            int chunkX = (int) Math.round(Math.cos(angle) * radius);
            int chunkZ = (int) Math.round(Math.sin(angle) * radius);

            // Vanilla always calls random.fork() here, even if the biome
            // search later falls back to the centre. Consume it unconditionally.
            long forkSeed = random.nextLong();
            result.add(new RingCandidate(chunkX, chunkZ, ringIndex, indexInRing,
                generatedIndex, forkSeed));

            angle += TWO_PI / spread;
            indexInRing++;
            if (indexInRing == spread) {
                ringIndex++;
                indexInRing = 0;
                spread += (2 * spread) / (ringIndex + 1);
                spread = Math.min(spread, COUNT - generatedIndex);
                angle += random.nextDouble() * TWO_PI;
            }
        }
        return List.copyOf(result);
    }

    /** Ring centres only, in generation order. */
    public static List<ChunkCoordinate> ringOrigins(long worldSeed) {
        List<RingCandidate> candidates = ringCandidates(worldSeed);
        List<ChunkCoordinate> result = new ArrayList<>(candidates.size());
        for (RingCandidate candidate : candidates) {
            result.add(candidate.originChunk());
        }
        return List.copyOf(result);
    }

    /**
     * Applies a biome gate to all 128 ring candidates. Without a
     * version-matched biome
     * oracle this is the identity gate and returns the ring centres.
     */
    public static List<ChunkCoordinate> strongholds(long worldSeed, BiomeGate gate) {
        Objects.requireNonNull(gate, "gate");
        List<RingCandidate> candidates = ringCandidates(worldSeed);
        List<ChunkCoordinate> result = new ArrayList<>(candidates.size());
        for (RingCandidate candidate : candidates) {
            result.add(candidate.locate(gate));
        }
        return List.copyOf(result);
    }

    /** Identity gate: useful when only ring geometry is needed. */
    public static BiomeGate identityGate() {
        return (originChunkX, originChunkZ, fork) -> new ChunkCoordinate(originChunkX, originChunkZ);
    }

    /** Block X of a ring centre, matching vanilla's {@code chunk*16 + 8}. */
    public static int centerBlockX(int chunkX) {
        return chunkX * 16 + CHUNK_CENTER;
    }

    /** Block Z of a ring centre, matching vanilla's {@code chunk*16 + 8}. */
    public static int centerBlockZ(int chunkZ) {
        return chunkZ * 16 + CHUNK_CENTER;
    }
}
