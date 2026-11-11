package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Replays the shipwreck rotation and template-choice draws from a supplied
 * structure-generation LCG state.
 *
 * <p>Vanilla draws a random four-way rotation, then picks one entry from the
 * ocean or beached template list. The caller must provide the random source
 * already seeded and advanced exactly as the target-version structure start
 * does. This class does not predict whether a shipwreck starts or determine
 * its terrain-dependent height.</p>
 */
public final class ShipwreckTemplateSelector {
    private static final List<String> OCEAN_TEMPLATES = List.of(
        "minecraft:shipwreck/with_mast",
        "minecraft:shipwreck/upsidedown_full",
        "minecraft:shipwreck/upsidedown_fronthalf",
        "minecraft:shipwreck/upsidedown_backhalf",
        "minecraft:shipwreck/sideways_full",
        "minecraft:shipwreck/sideways_fronthalf",
        "minecraft:shipwreck/sideways_backhalf",
        "minecraft:shipwreck/rightsideup_full",
        "minecraft:shipwreck/rightsideup_fronthalf",
        "minecraft:shipwreck/rightsideup_backhalf",
        "minecraft:shipwreck/with_mast_degraded",
        "minecraft:shipwreck/upsidedown_full_degraded",
        "minecraft:shipwreck/upsidedown_fronthalf_degraded",
        "minecraft:shipwreck/upsidedown_backhalf_degraded",
        "minecraft:shipwreck/sideways_full_degraded",
        "minecraft:shipwreck/sideways_fronthalf_degraded",
        "minecraft:shipwreck/sideways_backhalf_degraded",
        "minecraft:shipwreck/rightsideup_full_degraded",
        "minecraft:shipwreck/rightsideup_fronthalf_degraded",
        "minecraft:shipwreck/rightsideup_backhalf_degraded");

    private static final List<String> BEACHED_TEMPLATES = List.of(
        "minecraft:shipwreck/with_mast",
        "minecraft:shipwreck/sideways_full",
        "minecraft:shipwreck/sideways_fronthalf",
        "minecraft:shipwreck/sideways_backhalf",
        "minecraft:shipwreck/rightsideup_full",
        "minecraft:shipwreck/rightsideup_fronthalf",
        "minecraft:shipwreck/rightsideup_backhalf",
        "minecraft:shipwreck/with_mast_degraded",
        "minecraft:shipwreck/rightsideup_full_degraded",
        "minecraft:shipwreck/rightsideup_fronthalf_degraded",
        "minecraft:shipwreck/rightsideup_backhalf_degraded");

    private ShipwreckTemplateSelector() {
    }

    /** Consumes the rotation draw followed by the template-index draw. */
    public static Selection select(boolean beached, Lcg48 random) {
        Objects.requireNonNull(random, "random");
        StructureGeometry.CompassFacing facing = switch (random.nextInt(4)) {
            case 0 -> StructureGeometry.CompassFacing.NORTH;
            case 1 -> StructureGeometry.CompassFacing.EAST;
            case 2 -> StructureGeometry.CompassFacing.SOUTH;
            case 3 -> StructureGeometry.CompassFacing.WEST;
            default -> throw new AssertionError("nextInt(4) returned an invalid rotation");
        };
        List<String> templates = beached ? BEACHED_TEMPLATES : OCEAN_TEMPLATES;
        String template = templates.get(random.nextInt(templates.size()));
        return new Selection(beached ? ShipwreckVariant.Kind.BEACHED
            : ShipwreckVariant.Kind.SHIPWRECK, facing, template);
    }

    /**
     * Replays the complete structure-start RNG input for an observed shipwreck.
     *
     * <p>{@code Structure.GenerationContext} initializes this stream with
     * {@code WorldgenRandom.setLargeFeatureSeed(worldSeed, chunkX, chunkZ)}.
     * Since that source is {@code LegacyRandomSource}, only the low 48 bits of
     * {@code worldSeed} affect the rotation/template choice.</p>
     */
    public static Selection selectForStart(long worldSeed, int chunkX, int chunkZ,
                                           boolean beached) {
        long startSeed = WorldgenSeeds263.largeFeatureSeed(worldSeed, chunkX, chunkZ);
        return select(beached, new Lcg48(startSeed));
    }

    public static List<String> oceanTemplates() {
        return OCEAN_TEMPLATES;
    }

    public static List<String> beachedTemplates() {
        return BEACHED_TEMPLATES;
    }

    public record Selection(ShipwreckVariant.Kind kind,
                            StructureGeometry.CompassFacing facing,
                            String templateId) {
        public Selection {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(facing, "facing");
            Objects.requireNonNull(templateId, "templateId");
        }

        public StructureGeometry.Orientation orientation() {
            return new StructureGeometry.Orientation(facing, StructureGeometry.Mirror.NONE);
        }
    }
}
