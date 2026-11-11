package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * High-level entry point for the dependency-free seed-cracking pipeline.
 *
 * <p>Typical use:</p>
 * <ol>
 *   <li>Collect {@link StructureObservation}s from structure starts.</li>
 *   <li>{@link #crack} lifts the low 48 bits and, if full-seed filters are
 *       supplied, enumerates the 65,536 upper halves.</li>
 *   <li>If only structure seeds are returned, add a biome, terrain, stronghold,
 *       bedrock, structure-start, or loot oracle and call
 *       {@link #recoverUpperBits}.</li>
 * </ol>
 *
 * <p>Nothing in this package imports Minecraft. Minecraft-dependent stages are
 * supplied through {@link WorldSeedFilter} and the oracle interfaces. Select
 * structure specs with {@link VanillaPlacements#forVersion(MinecraftVersion)}
 * and construct version/preset-aware oracles through {@link VersionProfile}.</p>
 */
public final class SeedCracking {
    private SeedCracking() {
    }

    /** Runs with conservative default budgets. */
    public static CrackResult crack(List<StructureObservation> observations,
                                    List<WorldSeedFilter> filters) {
        return crack(observations, filters, LiftOptions.defaults(), RecoveryOptions.defaults());
    }

    public static CrackResult crack(List<StructureObservation> observations,
                                    List<WorldSeedFilter> filters,
                                    LiftOptions liftOptions,
                                    RecoveryOptions recoveryOptions) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(liftOptions, "liftOptions");
        Objects.requireNonNull(recoveryOptions, "recoveryOptions");

        LiftResult lift = StructureSeedLifter.lift(observations, liftOptions);
        if (lift.status() != LiftResult.Status.FOUND) {
            return new CrackResult(lift.structureSeeds(), List.of(), liftStatus(lift.status()),
                lift, null, lift.message());
        }
        if (filters.isEmpty()) {
            return new CrackResult(lift.structureSeeds(), List.of(),
                CrackResult.Status.STRUCTURE_SEEDS_ONLY, lift, null,
                "Lifted " + lift.structureSeeds().size() + " low-48 structure seed(s), but no full-seed "
                    + "filter was supplied. The upper 16 bits are still unknown; add a biome, terrain, "
                    + "stronghold, bedrock, structure-start, or loot filter.");
        }

        RecoveryResult recovery = UpperBitRecovery.recover(
            lift.structureSeeds(), filters, recoveryOptions);
        return combine(lift, lift.structureSeeds(), recovery);
    }

    /** Upper-bit recovery when the low-48 structure seeds are already known. */
    public static CrackResult recoverUpperBits(List<Long> structureSeeds,
                                               List<WorldSeedFilter> filters,
                                               RecoveryOptions options) {
        Objects.requireNonNull(structureSeeds, "structureSeeds");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(options, "options");
        List<Long> normalized = UpperBitRecovery.distinctStructureSeeds(structureSeeds);
        RecoveryResult recovery = UpperBitRecovery.recover(normalized, filters, options);
        return combine(null, normalized, recovery);
    }

    /** Convenience delegate for callers that only want the lifting result. */
    public static LiftResult lift(List<StructureObservation> observations, LiftOptions options) {
        return StructureSeedLifter.lift(observations, options);
    }

    /** Convenience delegate for callers that only want upper-bit recovery. */
    public static RecoveryResult recover(List<Long> structureSeeds,
                                         List<WorldSeedFilter> filters,
                                         RecoveryOptions options) {
        return UpperBitRecovery.recover(structureSeeds, filters, options);
    }

    /**
     * Replays every structure placement against a full world seed. Only the low
     * 48 bits matter; the upper half is ignored by placement.
     */
    public static boolean verifyStructures(long worldSeed, List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        long structureSeed = Lcg48.structureSeed(worldSeed);
        for (StructureObservation observation : observations) {
            if (!observation.placementMatches(structureSeed)) {
                return false;
            }
        }
        return true;
    }

    private static CrackResult combine(LiftResult lift, List<Long> structureSeeds,
                                       RecoveryResult recovery) {
        CrackResult.Status status = switch (recovery.status()) {
            case FOUND -> CrackResult.Status.WORLD_SEEDS_FOUND;
            case NO_STRUCTURE_SEEDS -> CrackResult.Status.NO_STRUCTURE_MATCH;
            case NO_MATCH -> CrackResult.Status.NO_WORLD_SEED_MATCH;
            case NEEDS_FILTERS -> CrackResult.Status.NEEDS_FILTERS;
            case BUDGET_EXHAUSTED -> CrackResult.Status.BUDGET_EXHAUSTED;
        };
        return new CrackResult(structureSeeds, recovery.worldSeeds(), status,
            lift, recovery, recovery.message());
    }

    private static CrackResult.Status liftStatus(LiftResult.Status status) {
        return switch (status) {
            case FOUND -> throw new IllegalStateException("FOUND is handled by the caller");
            case NEEDS_MORE_OBSERVATIONS -> CrackResult.Status.NEEDS_MORE_OBSERVATIONS;
            case NO_MATCH -> CrackResult.Status.NO_STRUCTURE_MATCH;
            case BUDGET_EXHAUSTED -> CrackResult.Status.BUDGET_EXHAUSTED;
        };
    }
}
