package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * Exact, version-aware vanilla bedrock oracle.
 *
 * <p>All audited 26.1-26.3 worlds use the same two
 * {@code VerticalGradientCondition} rules:</p>
 *
 * <ul>
 *   <li>{@code minecraft:bedrock_floor}: bedrock at and below {@code minY}, a
 *       linear random gradient from {@code minY} to {@code minY + 5}, and no
 *       bedrock above that.</li>
 *   <li>{@code minecraft:bedrock_roof}: the inverse gradient near the top of
 *       the noise-settings height, with bedrock guaranteed at {@code maxY}.</li>
 * </ul>
 *
 * <p>Which rules are active is a property of the world preset, not just the
 * dimension. The default Overworld is <strong>floor only</strong>; the caves
 * preset and the Nether use both. Select the preset explicitly with
 * {@link BedrockPreset} or use {@link VersionProfile#bedrockOracle(Dimension)}
 * for default dimension behavior.</p>
 *
 * <p>Overworld/End noise settings use Xoroshiro positional factories; Nether
 * noise settings set {@code legacy_random_source=true} and use Legacy positional
 * factories. Both are modeled here. Each condition asks a named positional random factory for
 * {@code nextFloat()} and compares it with the vertical gradient. The named
 * factories are derived from the full 64-bit world seed through
 * {@code RandomState → XoroshiroRandomSource → forkPositional →
 * fromHashOf(randomName) → forkPositional}. This makes bedrock an exact
 * upper-16-bit verifier: changing only the upper half of the seed changes the
 * pattern in the Overworld. Nether legacy factories retain only 48 seed bits,
 * so Nether bedrock can validate those bits but cannot identify the upper half.</p>
 *
 * <p>Assumption: vanilla material rules. A custom datapack can change anchors
 * or random names; supply a custom {@link BedrockModel} only when the anchors
 * match the vanilla shape, otherwise implement {@link BedrockOracle} directly.</p>
 */
public class VanillaBedrockOracle implements BedrockOracle {
    public static final int DEFAULT_MIN_Y = -64;
    public static final int DEFAULT_HEIGHT = 384;
    public static final int DEFAULT_MAX_Y = DEFAULT_MIN_Y + DEFAULT_HEIGHT - 1;
    public static final String FLOOR_RANDOM = "minecraft:bedrock_floor";
    public static final String ROOF_RANDOM = "minecraft:bedrock_roof";

    /** Number of blocks spanned by each gradient. */
    public static final int GRADIENT_SPAN = BedrockModel.GRADIENT_SPAN;

    private final MinecraftVersion version;
    private final BedrockModel model;
    private final ThreadLocal<SeedContext> contextCache = ThreadLocal.withInitial(() -> null);
    /** Nether noise settings opt into LegacyRandomSource, unlike Overworld/End. */
    private final boolean legacyRandom;

    /** Defaults to the latest audited version and the default Overworld preset. */
    public VanillaBedrockOracle() {
        this(MinecraftVersion.latest(), BedrockPreset.OVERWORLD);
    }

    public VanillaBedrockOracle(BedrockPreset preset) {
        this(MinecraftVersion.latest(), preset);
    }

    /** Default preset for the dimension; Overworld is floor-only. */
    public VanillaBedrockOracle(Dimension dimension) {
        this(MinecraftVersion.latest(), dimension);
    }

    public VanillaBedrockOracle(MinecraftVersion version, Dimension dimension) {
        this(version, BedrockModel.vanilla(
            Objects.requireNonNull(dimension, "dimension").defaultBedrockPreset()));
    }

    public VanillaBedrockOracle(MinecraftVersion version, BedrockPreset preset) {
        this(version, BedrockModel.vanilla(preset));
    }

    public VanillaBedrockOracle(MinecraftVersion version, BedrockModel model) {
        this.version = Objects.requireNonNull(version, "version");
        this.model = Objects.requireNonNull(model, "model");
        this.legacyRandom = "nether".equals(model.id());
    }

    public MinecraftVersion version() {
        return version;
    }

    public BedrockModel model() {
        return model;
    }

    public int minY() {
        return model.minY();
    }

    public int height() {
        return model.height();
    }

    public int maxY() {
        return model.maxY();
    }

    @Override
    public boolean isBedrock(long worldSeed, int blockX, int blockY, int blockZ) {
        if (blockY < model.minY() || blockY > model.maxY()) {
            return false;
        }
        if (model.floor() && isFloorBedrock(worldSeed, blockX, blockY, blockZ)) {
            return true;
        }
        return model.roof() && isRoofBedrock(worldSeed, blockX, blockY, blockZ);
    }

    /**
     * Evaluates the floor rule even when the selected preset does not include
     * it; useful for diagnostics and for comparing presets.
     */
    public boolean isFloorBedrock(long worldSeed, int blockX, int blockY, int blockZ) {
        if (blockY < model.minY() || blockY > model.maxY()) {
            return false;
        }
        int trueAtAndBelow = model.minY();
        int falseAtAndAbove = model.minY() + GRADIENT_SPAN;
        if (blockY <= trueAtAndBelow) {
            return true;
        }
        if (blockY >= falseAtAndAbove) {
            return false;
        }
        return gradientBelow(worldSeed, FLOOR_RANDOM, blockX, blockY, blockZ,
            trueAtAndBelow, falseAtAndAbove);
    }

    /**
     * Evaluates the roof rule even when the selected preset does not include
     * it. The default Overworld preset intentionally does not use this rule
     * for {@link #isBedrock}.
     */
    public boolean isRoofBedrock(long worldSeed, int blockX, int blockY, int blockZ) {
        if (blockY < model.minY() || blockY > model.maxY()) {
            return false;
        }
        int falseAtAndAbove = model.maxY();
        int trueAtAndBelow = model.maxY() - GRADIENT_SPAN;
        if (blockY >= falseAtAndAbove) {
            return true;
        }
        if (blockY <= trueAtAndBelow) {
            return false;
        }
        // The JSON wraps the gradient in "not".
        return !gradientBelow(worldSeed, ROOF_RANDOM, blockX, blockY, blockZ,
            trueAtAndBelow, falseAtAndAbove);
    }

    /** Raw gradient random for diagnostics and self-tests. */
    public float randomFloat(long worldSeed, String randomName,
                             int blockX, int blockY, int blockZ) {
        SeedContext context = context(worldSeed);
        if (context.legacySeed) return context.legacyFloat(randomName, blockX, blockY, blockZ);
        return context.factory(randomName).at(blockX, blockY, blockZ).nextFloat();
    }

    public float floorRandomFloat(long worldSeed, int blockX, int blockY, int blockZ) {
        return randomFloat(worldSeed, FLOOR_RANDOM, blockX, blockY, blockZ);
    }

    public float roofRandomFloat(long worldSeed, int blockX, int blockY, int blockZ) {
        return randomFloat(worldSeed, ROOF_RANDOM, blockX, blockY, blockZ);
    }

    private boolean gradientBelow(long worldSeed, String randomName,
                                  int blockX, int blockY, int blockZ,
                                  int trueAtAndBelow, int falseAtAndAbove) {
        double delta = ((double) blockY - trueAtAndBelow)
            / ((double) falseAtAndAbove - trueAtAndBelow);
        double gradient = 1.0 + delta * (0.0 - 1.0);
        float random = randomFloat(worldSeed, randomName, blockX, blockY, blockZ);
        return (double) random < gradient;
    }

    private SeedContext context(long worldSeed) {
        SeedContext cached = contextCache.get();
        if (cached != null && cached.worldSeed == worldSeed) {
            return cached;
        }
        SeedContext created = new SeedContext(worldSeed, legacyRandom);
        contextCache.set(created);
        return created;
    }

    private static final class SeedContext {
        private final long worldSeed;
        private final XoroshiroRandom263.PositionalFactory floorFactory;
        private final XoroshiroRandom263.PositionalFactory roofFactory;
        private final boolean legacySeed;
        private final long legacyFloorFactorySeed,legacyRoofFactorySeed;

        private SeedContext(long worldSeed, boolean legacy) {
            this.worldSeed = worldSeed;
            this.legacySeed = legacy;
            if(legacy) {
                floorFactory=null; roofFactory=null;
                Lcg48 root=new Lcg48(worldSeed);
                long rootPositional=root.nextLong();
                this.legacyFloorFactorySeed=legacyPositionalSeed(rootPositional,FLOOR_RANDOM);
                this.legacyRoofFactorySeed=legacyPositionalSeed(rootPositional,ROOF_RANDOM);
                return;
            }
            legacyFloorFactorySeed=legacyRoofFactorySeed=0;
            XoroshiroRandom263.PositionalFactory root =
                new XoroshiroRandom263(worldSeed).forkPositional();
            this.floorFactory = root.fromHashOf(FLOOR_RANDOM).forkPositional();
            this.roofFactory = root.fromHashOf(ROOF_RANDOM).forkPositional();
        }

        private XoroshiroRandom263.PositionalFactory factory(String randomName) {
            if(legacySeed) throw new UnsupportedOperationException("Use legacyFloat for a legacy Nether random factory");
            if (FLOOR_RANDOM.equals(randomName)) {
                return floorFactory;
            }
            if (ROOF_RANDOM.equals(randomName)) {
                return roofFactory;
            }
            throw new IllegalArgumentException("Unsupported bedrock random name: " + randomName);
        }
        private float legacyFloat(String randomName,int x,int y,int z) {
            long factorySeed=FLOOR_RANDOM.equals(randomName)?legacyFloorFactorySeed
                :ROOF_RANDOM.equals(randomName)?legacyRoofFactorySeed:throwName(randomName);
            Lcg48 random=new Lcg48(factorySeed ^ MthSeed263.getSeed(x,y,z));
            return random.nextFloat();
        }
        private static long legacyPositionalSeed(long rootSeed,String name) {
            Lcg48 named=new Lcg48((long)name.hashCode() ^ rootSeed);
            return named.nextLong();
        }
        private static long throwName(String name) { throw new IllegalArgumentException("Unsupported bedrock random name: "+name); }
    }
}
