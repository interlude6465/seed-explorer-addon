package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * End spike ("pillar") fingerprint.
 *
 * <p>Minecraft 26.1-26.3 {@code EndSpikeFeature.getSpikesForLevel} seeds a
 * Legacy random with
 * the world seed, takes {@code nextLong() & 0xFFFF} as the spike-cache key, and
 * builds ten spike heights from a shuffle of indices 0..9:
 * {@code height = 76 + index * 3}. The spike key depends only on the low 48
 * bits of the world seed.</p>
 *
 * <p>The ten observed heights therefore give a 16-bit fingerprint of the
 * structure seed. Enumerating {@code 0..65535} spike seeds and comparing the
 * shuffle produces roughly 50-60 compatible keys on average, which rejects
 * about 99.9% of random structure seeds. It is a filter, not a complete seed
 * recovery method.</p>
 *
 * <p><b>Height convention:</b> the values used here are the spike's
 * {@code height} field (76, 79, ..., 103), not the Y of the highest obsidian
 * block directly below the top. If you collected the highest obsidian block Y,
 * use {@link #fromTopBlockHeights(int...)} which adds one.</p>
 */
public final class EndPillarFingerprint {
    public static final int SPIKE_COUNT = 10;
    public static final int BASE_HEIGHT = 76;
    public static final int HEIGHT_STEP = 3;
    public static final int MAX_SPIKE_SEED = 0xFFFF;

    private final int[] heights;
    private volatile Set<Integer> spikeSeedCandidates;

    private EndPillarFingerprint(int[] heights) {
        this.heights = heights;
    }

    public static EndPillarFingerprint fromHeights(int... heights) {
        if (heights == null || heights.length != SPIKE_COUNT) {
            throw new IllegalArgumentException("Exactly " + SPIKE_COUNT + " spike heights are required");
        }
        int[] copy = heights.clone();
        boolean[] seen = new boolean[SPIKE_COUNT];
        for (int i = 0; i < copy.length; i++) {
            int delta = copy[i] - BASE_HEIGHT;
            if (delta < 0 || delta % HEIGHT_STEP != 0) {
                throw new IllegalArgumentException(
                    "Height " + copy[i] + " is not one of 76, 79, ..., 103");
            }
            int index = delta / HEIGHT_STEP;
            if (index >= SPIKE_COUNT || seen[index]) {
                throw new IllegalArgumentException("Duplicate or out-of-range spike height: " + copy[i]);
            }
            seen[index] = true;
        }
        return new EndPillarFingerprint(copy);
    }

    public static EndPillarFingerprint fromHeights(List<Integer> heights) {
        if (heights == null) {
            throw new IllegalArgumentException("heights must not be null");
        }
        int[] values = new int[heights.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = heights.get(i);
        }
        return fromHeights(values);
    }

    /** Converts highest-obsidian-block Y values to spike height values. */
    public static EndPillarFingerprint fromTopBlockHeights(int... topBlockHeights) {
        if (topBlockHeights == null) {
            throw new IllegalArgumentException("topBlockHeights must not be null");
        }
        int[] values = new int[topBlockHeights.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = topBlockHeights[i] + 1;
        }
        return fromHeights(values);
    }

    public int[] heights() {
        return heights.clone();
    }

    /**
     * Enumerates all 16-bit spike seeds compatible with the observed heights.
     * The result is cached on this instance.
     */
    public Set<Integer> spikeSeedCandidates() {
        Set<Integer> cached = spikeSeedCandidates;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (spikeSeedCandidates == null) {
                Set<Integer> found = new LinkedHashSet<>();
                int[] generated = new int[SPIKE_COUNT];
                for (int seed = 0; seed <= MAX_SPIKE_SEED; seed++) {
                    fillHeightsForSpikeSeed(seed, generated);
                    if (java.util.Arrays.equals(generated, heights)) {
                        found.add(seed);
                    }
                }
                spikeSeedCandidates = Collections.unmodifiableSet(found);
            }
            return spikeSeedCandidates;
        }
    }

    /** Returns the 16-bit spike cache key for a structure seed (low 48 bits). */
    public static int spikeSeedForStructureSeed(long structureSeed) {
        Lcg48 random = new Lcg48(structureSeed);
        return (int) (random.nextLong() & 0xFFFFL);
    }

    /** Returns the 16-bit spike cache key for any world seed. */
    public static int spikeSeedForWorldSeed(long worldSeed) {
        return spikeSeedForStructureSeed(worldSeed & Lcg48.MASK);
    }

    /**
     * Generates the ten spike heights for a 16-bit spike cache key.
     *
     * <p>This is the same Fisher-Yates sequence used by
     * {@code Util.toShuffledList(IntStream.range(0, 10), random)} in 26.1-26.3:
     * for {@code i = 10; i > 1; i--} swap element {@code i - 1} with element
     * {@code random.nextInt(i)}.</p>
     */
    public static int[] heightsForSpikeSeed(int spikeSeed) {
        int[] generated = new int[SPIKE_COUNT];
        fillHeightsForSpikeSeed(spikeSeed, generated);
        return generated;
    }

    private static void fillHeightsForSpikeSeed(int spikeSeed, int[] out) {
        int[] order = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Lcg48 random = new Lcg48(spikeSeed);
        for (int i = SPIKE_COUNT; i > 1; i--) {
            int other = random.nextInt(i);
            int value = order[i - 1];
            order[i - 1] = order[other];
            order[other] = value;
        }
        for (int i = 0; i < SPIKE_COUNT; i++) {
            out[i] = BASE_HEIGHT + order[i] * HEIGHT_STEP;
        }
    }

    /**
     * Fast 16-bit filter for structure-seed candidates.
     *
     * <p>For a search that enumerates high bits, call this before exact
     * structure placement checks. It computes the first {@code nextLong()} of
     * the structure seed, which is cheap compared with a placement replay.</p>
     */
    public boolean matchesStructureSeed(long structureSeed) {
        return spikeSeedCandidates().contains(spikeSeedForStructureSeed(structureSeed));
    }

    public List<Integer> candidateList() {
        return new ArrayList<>(spikeSeedCandidates());
    }

    @Override
    public String toString() {
        return "EndPillarFingerprint[heights=" + java.util.Arrays.toString(heights)
            + ", candidates=" + spikeSeedCandidates().size() + "]";
    }
}
