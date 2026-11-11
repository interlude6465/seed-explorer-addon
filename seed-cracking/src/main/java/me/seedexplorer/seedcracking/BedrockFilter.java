package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Full-seed filter backed by bedrock observations.
 *
 * <p>A correct 26.1+ bedrock oracle should match every observation in an
 * untouched world. {@code allowedMismatches} exists only for observation noise
 * (resource-pack or version confusion); keep it at zero for seed recovery.</p>
 */
public final class BedrockFilter implements WorldSeedFilter {
    private final BedrockOracle oracle;
    private final List<BedrockObservation> observations;
    private final int allowedMismatches;

    public BedrockFilter(BedrockOracle oracle, List<BedrockObservation> observations) {
        this(oracle, observations, 0);
    }

    public BedrockFilter(BedrockOracle oracle, List<BedrockObservation> observations,
                         int allowedMismatches) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one bedrock observation is required");
        }
        if (allowedMismatches < 0 || allowedMismatches >= observations.size()) {
            throw new IllegalArgumentException(
                "allowedMismatches must be in [0, observations.size()): " + allowedMismatches);
        }
        this.observations = List.copyOf(observations);
        this.allowedMismatches = allowedMismatches;
    }

    @Override
    public boolean matches(long worldSeed) {
        int mismatches = 0;
        for (BedrockObservation observation : observations) {
            boolean actual = oracle.isBedrock(worldSeed,
                observation.blockX(), observation.blockY(), observation.blockZ());
            if (actual != observation.bedrock()) {
                mismatches++;
                if (mismatches > allowedMismatches) {
                    return false;
                }
            }
        }
        return true;
    }

    public List<BedrockObservation> observations() {
        return observations;
    }

    public int allowedMismatches() {
        return allowedMismatches;
    }

    @Override
    public String description() {
        return "bedrock filter (" + observations.size() + " block(s), "
            + allowedMismatches + " allowed mismatch(es))";
    }
}
