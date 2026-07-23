package me.seedexplorer.addon.loot;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

public class LootRandom {
    private final WorldgenRandom random;

    public LootRandom(long seed) {
        this.random = new WorldgenRandom(new LegacyRandomSource(0L));
        this.random.setSeed(seed);
    }

    public LootRandom(long worldSeed, int chunkX, int chunkZ) {
        this.random = new WorldgenRandom(new LegacyRandomSource(0L));
        this.random.setLargeFeatureSeed(worldSeed, chunkX, chunkZ);
    }

    public LootRandom(long worldSeed, int chunkX, int chunkZ, int salt) {
        this.random = new WorldgenRandom(new LegacyRandomSource(0L));
        this.random.setLargeFeatureWithSalt(worldSeed, chunkX, chunkZ, salt);
    }

    public LootRandom fork() {
        return new LootRandom(random.nextLong());
    }

    public long nextLong() {
        return random.nextLong();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public float nextFloat() {
        return random.nextFloat();
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public int nextInt() {
        return random.nextInt();
    }

    public RandomSource raw() {
        return random;
    }
}
