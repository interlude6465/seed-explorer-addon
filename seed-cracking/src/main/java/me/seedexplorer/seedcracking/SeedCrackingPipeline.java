package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit, dependency-free counterpart to SeedcrackerX's phase engine.
 *
 * <p>The pipeline records the phases that were applicable to a run while
 * delegating the actual searches to the isolated building blocks. It is a
 * future integration point for a Minecraft observation collector; it does not
 * own client state or import Minecraft.</p>
 */
public final class SeedCrackingPipeline {
    public enum Phase {
        PILLARS,
        STRUCTURES,
        LIFTING,
        STRUCTURE_REDUCE,
        BIOMES
    }

    public record Result(CrackResult crackResult, List<Phase> phases) {
        public Result {
            Objects.requireNonNull(crackResult, "crackResult");
            phases = List.copyOf(Objects.requireNonNull(phases, "phases"));
        }

        public boolean found() {
            return crackResult.found();
        }
    }

    private SeedCrackingPipeline() {
    }

    /** Runs the same caller-driven stages as {@link SeedCracking#crack}. */
    public static Result run(List<StructureObservation> observations,
                             List<WorldSeedFilter> filters) {
        return run(observations, filters, LiftOptions.defaults(), RecoveryOptions.defaults());
    }

    public static Result run(List<StructureObservation> observations,
                             List<WorldSeedFilter> filters,
                             LiftOptions liftOptions,
                             RecoveryOptions recoveryOptions) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(liftOptions, "liftOptions");
        Objects.requireNonNull(recoveryOptions, "recoveryOptions");

        List<Phase> phases = new ArrayList<>();
        if (liftOptions.pillarFingerprint() != null) {
            phases.add(Phase.PILLARS);
        }
        phases.add(Phase.STRUCTURES);
        phases.add(Phase.LIFTING);

        LiftResult lift = StructureSeedLifter.lift(observations, liftOptions);
        if (lift.status() != LiftResult.Status.FOUND) {
            CrackResult result = new CrackResult(lift.structureSeeds(), List.of(),
                liftStatus(lift.status()), lift, null, lift.message());
            return new Result(result, phases);
        }

        phases.add(Phase.STRUCTURE_REDUCE);
        if (filters.isEmpty()) {
            CrackResult result = new CrackResult(lift.structureSeeds(), List.of(),
                CrackResult.Status.STRUCTURE_SEEDS_ONLY, lift, null,
                "Lifted " + lift.structureSeeds().size()
                    + " low-48 structure seed(s); no full-seed filter was supplied.");
            return new Result(result, phases);
        }

        phases.add(Phase.BIOMES);
        RecoveryResult recovery = UpperBitRecovery.recover(
            lift.structureSeeds(), filters, recoveryOptions);
        CrackResult.Status status = switch (recovery.status()) {
            case FOUND -> CrackResult.Status.WORLD_SEEDS_FOUND;
            case NO_STRUCTURE_SEEDS -> CrackResult.Status.NO_STRUCTURE_MATCH;
            case NO_MATCH -> CrackResult.Status.NO_WORLD_SEED_MATCH;
            case NEEDS_FILTERS -> CrackResult.Status.NEEDS_FILTERS;
            case BUDGET_EXHAUSTED -> CrackResult.Status.BUDGET_EXHAUSTED;
        };
        CrackResult result = new CrackResult(lift.structureSeeds(), recovery.worldSeeds(), status,
            lift, recovery, recovery.message());
        return new Result(result, phases);
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
