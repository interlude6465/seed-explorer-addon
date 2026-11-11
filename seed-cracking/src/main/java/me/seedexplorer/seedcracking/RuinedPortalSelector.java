package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Replays the RNG-visible choice sequence in 26.1-26.3 RuinedPortalStructure.
 *
 * <p>The caller must supply the structure-generation LCG at the exact state
 * immediately before {@code findGenerationPoint}. This class intentionally
 * does not guess that state from the world seed: structure-start RNG setup,
 * biome validation, template placement, and terrain-dependent Y selection are
 * separate inputs.</p>
 */
public final class RuinedPortalSelector {
    private static final List<String> NORMAL_TEMPLATES = List.of(
        "ruined_portal/portal_1", "ruined_portal/portal_2", "ruined_portal/portal_3",
        "ruined_portal/portal_4", "ruined_portal/portal_5", "ruined_portal/portal_6",
        "ruined_portal/portal_7", "ruined_portal/portal_8", "ruined_portal/portal_9",
        "ruined_portal/portal_10");
    private static final List<String> GIANT_TEMPLATES = List.of(
        "ruined_portal/giant_portal_1", "ruined_portal/giant_portal_2",
        "ruined_portal/giant_portal_3");

    private RuinedPortalSelector() {
    }

    /** Replays setup, air-pocket, giant-template, rotation, and mirror draws. */
    public static Selection select(List<Setup> setups, Lcg48 random) {
        Objects.requireNonNull(setups, "setups");
        Objects.requireNonNull(random, "random");
        if (setups.isEmpty()) {
            throw new IllegalArgumentException("At least one setup is required");
        }

        float totalWeight = 0.0F;
        for (Setup setup : setups) {
            Objects.requireNonNull(setup, "setup");
            totalWeight += setup.weight();
        }

        Setup setup;
        if (setups.size() == 1) {
            setup = setups.get(0);
        } else {
            float roll = random.nextFloat();
            setup = null;
            for (Setup candidate : setups) {
                roll -= candidate.weight() / totalWeight;
                if (roll < 0.0F) {
                    setup = candidate;
                    break;
                }
            }
            if (setup == null) {
                throw new IllegalStateException("Weighted setup selection did not resolve");
            }
        }

        boolean airPocket = sample(random, setup.airPocketProbability());
        boolean giant = random.nextFloat() < 0.05F;
        List<String> templates = giant ? GIANT_TEMPLATES : NORMAL_TEMPLATES;
        String template = templates.get(random.nextInt(templates.size()));
        Rotation rotation = Rotation.values()[random.nextInt(Rotation.values().length)];
        Mirror mirror = random.nextFloat() < 0.5F ? Mirror.NONE : Mirror.FRONT_BACK;
        return new Selection(setup.id(), airPocket, giant, template, rotation, mirror);
    }

    /** Standard biomes: 26.3 changed the surface air-pocket probability to zero. */
    public static List<Setup> standardSetups(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return List.of(new Setup("underground", 1.0F, 0.5F),
            new Setup("on_land_surface", version == MinecraftVersion.V26_3 ? 0.0F : 0.5F,
                0.5F));
    }

    public static List<String> normalTemplateIds() {
        return NORMAL_TEMPLATES;
    }

    public static List<String> giantTemplateIds() {
        return GIANT_TEMPLATES;
    }

    public static List<Setup> desertSetups() {
        return List.of(new Setup("partly_buried", 0.0F, 1.0F));
    }

    public static List<Setup> jungleSetups(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return List.of(new Setup("on_land_surface",
            version == MinecraftVersion.V26_3 ? 0.0F : 0.5F, 1.0F));
    }

    public static List<Setup> mountainSetups(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return List.of(new Setup("in_mountain", 1.0F, 0.5F),
            new Setup("on_land_surface", version == MinecraftVersion.V26_3 ? 0.0F : 0.5F,
                0.5F));
    }

    public static List<Setup> netherSetups(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return List.of(new Setup("in_nether", version == MinecraftVersion.V26_3 ? 0.0F : 0.5F,
            1.0F));
    }

    public static List<Setup> oceanSetups() {
        return List.of(new Setup("on_ocean_floor", 0.0F, 1.0F));
    }

    public static List<Setup> swampSetups() {
        return List.of(new Setup("on_ocean_floor", 0.0F, 1.0F));
    }

    private static boolean sample(Lcg48 random, float probability) {
        if (!(probability >= 0.0F && probability <= 1.0F)) {
            throw new IllegalArgumentException("probability must be in [0, 1]: " + probability);
        }
        if (probability == 0.0F) {
            return false;
        }
        return probability == 1.0F || random.nextFloat() < probability;
    }

    public record Setup(String id, float airPocketProbability, float weight) {
        public Setup {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (!(airPocketProbability >= 0.0F && airPocketProbability <= 1.0F)) {
                throw new IllegalArgumentException("airPocketProbability must be in [0, 1]");
            }
            if (!(weight > 0.0F) || !Float.isFinite(weight)) {
                throw new IllegalArgumentException("weight must be finite and positive");
            }
        }
    }

    public record Selection(String setupId, boolean airPocket, boolean giant,
                            String templateId, Rotation rotation, Mirror mirror) {
    }

    public enum Rotation {
        NONE,
        CLOCKWISE_90,
        CLOCKWISE_180,
        COUNTERCLOCKWISE_90
    }

    public enum Mirror {
        NONE,
        FRONT_BACK
    }
}
