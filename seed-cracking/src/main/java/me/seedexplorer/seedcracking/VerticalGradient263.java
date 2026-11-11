package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * Exact 26.1-26.3 {@code VerticalGradientCondition} evaluator.
 *
 * <p>The condition returns true at and below {@code trueAtAndBelow}, false at
 * and above {@code falseAtAndAbove}, and in between compares a positional
 * Xoroshiro {@code nextFloat()} with a linear gradient mapped from 1 to 0. A
 * {@code not(...)} wrapper (used by the Overworld roof rule) inverts the whole
 * result.</p>
 *
 * <p>This is the reusable primitive behind {@link VanillaBedrockOracle}. It can
 * also model custom datapack material rules when the anchors and named random
 * factory are known.</p>
 */
public final class VerticalGradient263 {
    public static final int DEFAULT_MIN_Y = -64;
    public static final int DEFAULT_HEIGHT = 384;
    public static final int DEFAULT_MAX_Y = DEFAULT_MIN_Y + DEFAULT_HEIGHT - 1;
    public static final int GRADIENT_SPAN = 5;
    public static final String FLOOR_RANDOM = "minecraft:bedrock_floor";
    public static final String ROOF_RANDOM = "minecraft:bedrock_roof";

    private final int trueAtAndBelow;
    private final int falseAtAndAbove;
    private final boolean inverted;
    private final XoroshiroRandom263.PositionalFactory factory;

    public VerticalGradient263(int trueAtAndBelow, int falseAtAndAbove, boolean inverted,
                               XoroshiroRandom263.PositionalFactory factory) {
        if (falseAtAndAbove <= trueAtAndBelow) {
            throw new IllegalArgumentException("falseAtAndAbove must be greater than trueAtAndBelow");
        }
        this.trueAtAndBelow = trueAtAndBelow;
        this.falseAtAndAbove = falseAtAndAbove;
        this.inverted = inverted;
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** Vanilla Overworld floor rule: bedrock guaranteed at {@code minY}. */
    public static VerticalGradient263 floor(long worldSeed) {
        return floor(worldSeed, DEFAULT_MIN_Y, GRADIENT_SPAN);
    }

    public static VerticalGradient263 floor(long worldSeed, int minY, int gradientSpan) {
        return new VerticalGradient263(minY, minY + gradientSpan, false,
            namedFactory(worldSeed, FLOOR_RANDOM));
    }

    /** Vanilla Overworld roof rule: bedrock guaranteed at {@code maxY}. */
    public static VerticalGradient263 roof(long worldSeed) {
        return roof(worldSeed, DEFAULT_MAX_Y, GRADIENT_SPAN);
    }

    public static VerticalGradient263 roof(long worldSeed, int maxY, int gradientSpan) {
        return new VerticalGradient263(maxY - gradientSpan, maxY, true,
            namedFactory(worldSeed, ROOF_RANDOM));
    }

    /**
     * Exact named positional factory chain used by
     * {@code MaterialRuleContext.getOrCreateRandomFactory}.
     */
    public static XoroshiroRandom263.PositionalFactory namedFactory(long worldSeed, String randomName) {
        Objects.requireNonNull(randomName, "randomName");
        return new XoroshiroRandom263(worldSeed)
            .forkPositional()
            .fromHashOf(randomName)
            .forkPositional();
    }

    /** Evaluates the condition, including the optional {@code not} wrapper. */
    public boolean test(int blockX, int blockY, int blockZ) {
        boolean base;
        if (blockY <= trueAtAndBelow) {
            base = true;
        } else if (blockY >= falseAtAndAbove) {
            base = false;
        } else {
            base = (double) randomFloat(blockX, blockY, blockZ) < gradient(blockY);
        }
        return inverted != base;
    }

    /** The {@code Mth.map(y, trueAtAndBelow, falseAtAndAbove, 1, 0)} gradient. */
    public double gradient(int blockY) {
        if (blockY <= trueAtAndBelow) {
            return 1.0;
        }
        if (blockY >= falseAtAndAbove) {
            return 0.0;
        }
        double delta = ((double) blockY - trueAtAndBelow)
            / ((double) falseAtAndAbove - trueAtAndBelow);
        return 1.0 + delta * (0.0 - 1.0);
    }

    public float randomFloat(int blockX, int blockY, int blockZ) {
        return factory.at(blockX, blockY, blockZ).nextFloat();
    }

    public int trueAtAndBelow() {
        return trueAtAndBelow;
    }

    public int falseAtAndAbove() {
        return falseAtAndAbove;
    }

    public boolean inverted() {
        return inverted;
    }

    public XoroshiroRandom263.PositionalFactory factory() {
        return factory;
    }
}
