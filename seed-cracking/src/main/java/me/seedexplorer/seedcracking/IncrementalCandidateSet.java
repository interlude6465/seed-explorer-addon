package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A mutable candidate set that can be narrowed as new observations arrive.
 *
 * <p>Structure seeds are always reduced with exact placement checks. Full world
 * seeds can be reduced with either placement checks (only the low 48 bits are
 * read) or full-seed filters. A set created for structure seeds rejects
 * {@link #retain(WorldSeedFilter)} because the upper 16 bits are unknown; call
 * {@link UpperBitRecovery} first.</p>
 *
 * <p>The elimination history is intentionally simple and in-memory. It is a
 * diagnostic aid, not a durable database.</p>
 */
public final class IncrementalCandidateSet {
    public enum SeedKind {
        STRUCTURE_SEED,
        WORLD_SEED
    }

    public record Elimination(
        String source,
        int before,
        int after,
        int removed,
        int revision
    ) {
    }

    private final SeedKind kind;
    private final LinkedHashSet<Long> candidates;
    private final List<Elimination> history = new ArrayList<>();
    private int revision;

    public IncrementalCandidateSet(SeedKind kind, Collection<Long> initial) {
        this.kind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(initial, "initial");
        this.candidates = new LinkedHashSet<>();
        for (Long seed : initial) {
            this.candidates.add(normalize(seed, kind));
        }
    }

    public static IncrementalCandidateSet fromStructureSeeds(Collection<Long> seeds) {
        return new IncrementalCandidateSet(SeedKind.STRUCTURE_SEED, seeds);
    }

    public static IncrementalCandidateSet fromWorldSeeds(Collection<Long> seeds) {
        return new IncrementalCandidateSet(SeedKind.WORLD_SEED, seeds);
    }

    public synchronized SeedKind kind() {
        return kind;
    }

    public synchronized int size() {
        return candidates.size();
    }

    public synchronized boolean isEmpty() {
        return candidates.isEmpty();
    }

    public synchronized int revision() {
        return revision;
    }

    /** Snapshot in insertion order. */
    public synchronized List<Long> candidates() {
        return List.copyOf(candidates);
    }

    public synchronized List<Elimination> history() {
        return List.copyOf(history);
    }

    /**
     * Keeps only candidates whose low-48 placement matches the observation.
     *
     * @return number of candidates removed
     */
    public synchronized int retain(StructureObservation observation) {
        Objects.requireNonNull(observation, "observation");
        int before = candidates.size();
        Iterator<Long> iterator = candidates.iterator();
        while (iterator.hasNext()) {
            long seed = iterator.next();
            if (!observation.placementMatches(seed & Lcg48.MASK)) {
                iterator.remove();
            }
        }
        return record(observation.describe(), before);
    }

    /**
     * Keeps only full world seeds accepted by the filter.
     *
     * @return number of candidates removed
     */
    public synchronized int retain(WorldSeedFilter filter) {
        Objects.requireNonNull(filter, "filter");
        if (kind != SeedKind.WORLD_SEED) {
            throw new IllegalStateException(
                "A structure-seed set has unknown upper bits; recover them with UpperBitRecovery first");
        }
        int before = candidates.size();
        Iterator<Long> iterator = candidates.iterator();
        while (iterator.hasNext()) {
            if (!filter.matches(iterator.next())) {
                iterator.remove();
            }
        }
        return record(filter.description(), before);
    }

    public synchronized int retainAllStructures(Collection<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        int removed = 0;
        for (StructureObservation observation : observations) {
            removed += retain(observation);
        }
        return removed;
    }

    public synchronized int retainAllWorld(Collection<WorldSeedFilter> filters) {
        Objects.requireNonNull(filters, "filters");
        if (kind != SeedKind.WORLD_SEED) {
            throw new IllegalStateException(
                "A structure-seed set has unknown upper bits; recover them with UpperBitRecovery first");
        }
        int removed = 0;
        for (WorldSeedFilter filter : filters) {
            removed += retain(filter);
        }
        return removed;
    }

    private int record(String source, int before) {
        int after = candidates.size();
        int removed = before - after;
        revision++;
        history.add(new Elimination(source, before, after, removed, revision));
        return removed;
    }

    private static long normalize(Long seed, SeedKind kind) {
        if (seed == null) {
            throw new IllegalArgumentException("candidate seed must not be null");
        }
        return kind == SeedKind.STRUCTURE_SEED ? seed & Lcg48.MASK : seed;
    }
}
