package me.seedexplorer.seedcracking;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Version-specific view of the shared 26.1+ seed-cracking data.
 *
 * <p>Most of the seed math is identical across 26.1.2, 26.2, and 26.3. The
 * profile makes the real data differences explicit:</p>
 *
 * <ul>
 *   <li>the random-spread placement catalog, which adds
 *       {@code minecraft:abandoned_camp} in 26.3;</li>
 *   <li>the bedrock preset geometry for the selected world preset;</li>
 *   <li>the version label used in diagnostics and reports.</li>
 * </ul>
 *
 * <p>Profiles are immutable and cached. A future version must be added to
 * {@link MinecraftVersion} and audited before its profile can be used.</p>
 */
public final class VersionProfile {
    private static final Map<MinecraftVersion, VersionProfile> PROFILES;

    static {
        EnumMap<MinecraftVersion, VersionProfile> profiles =
            new EnumMap<>(MinecraftVersion.class);
        for (MinecraftVersion version : MinecraftVersion.values()) {
            profiles.put(version, new VersionProfile(version));
        }
        PROFILES = Map.copyOf(profiles);
    }

    private final MinecraftVersion version;
    private final PlacementCatalog placements;
    private final Map<BedrockPreset, BedrockModel> bedrockModels;

    private VersionProfile(MinecraftVersion version) {
        this.version = version;
        this.placements = PlacementCatalog.vanilla(version);
        EnumMap<BedrockPreset, BedrockModel> models = new EnumMap<>(BedrockPreset.class);
        for (BedrockPreset preset : BedrockPreset.values()) {
            models.put(preset, BedrockModel.vanilla(preset));
        }
        this.bedrockModels = Map.copyOf(models);
    }

    public static VersionProfile of(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return PROFILES.get(version);
    }

    public static VersionProfile latest() {
        return of(MinecraftVersion.latest());
    }

    /** Parses a version string such as {@code 26.1.2}. */
    public static VersionProfile parse(String text) {
        return of(MinecraftVersion.parse(text));
    }

    public MinecraftVersion version() {
        return version;
    }

    public PlacementCatalog placements() {
        return placements;
    }

    /** Alias that makes call sites clearer when they also use strongholds. */
    public PlacementCatalog structurePlacements() {
        return placements;
    }

    public boolean abandonedCampsAvailable() {
        return version.supportsAbandonedCamps();
    }

    public BedrockModel bedrock(BedrockPreset preset) {
        Objects.requireNonNull(preset, "preset");
        return bedrockModels.get(preset);
    }

    /** Default bedrock model for the dimension (Overworld is floor-only). */
    public BedrockModel bedrock(Dimension dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return bedrock(dimension.defaultBedrockPreset());
    }

    public BedrockModel overworldBedrock() {
        return bedrock(BedrockPreset.OVERWORLD);
    }

    public BedrockModel cavesBedrock() {
        return bedrock(BedrockPreset.OVERWORLD_CAVES);
    }

    public BedrockModel netherBedrock() {
        return bedrock(BedrockPreset.NETHER);
    }

    public BedrockModel endBedrock() {
        return bedrock(BedrockPreset.END);
    }

    public VanillaBedrockOracle bedrockOracle(BedrockPreset preset) {
        return new VanillaBedrockOracle(version, preset);
    }

    /** Default bedrock oracle for the dimension. */
    public VanillaBedrockOracle bedrockOracle(Dimension dimension) {
        return new VanillaBedrockOracle(version, bedrock(dimension));
    }

    @Override
    public String toString() {
        return "VersionProfile[" + version.id() + ", " + placements.size()
            + " random-spread set(s), abandonedCamps=" + abandonedCampsAvailable() + "]";
    }
}
