package me.seedexplorer.standalone;

import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** File logger for opt-in live village RNG diagnostics. */
public final class VillageRngTrace {
    private static final ThreadLocal<String> FEATURE = new ThreadLocal<>();

    public static void beginFeature(String description) {
        if (Boolean.getBoolean("seedexplorer.villageRngTrace")) FEATURE.set(description);
    }

    public static void endFeature() {
        FEATURE.remove();
    }

    public static String feature() {
        return FEATURE.get();
    }
    private VillageRngTrace() {
    }

    public static void chest(BlockPos pos, long lootSeed, int before, int after, long worldSeed) {
        if (!Boolean.getBoolean("seedexplorer.villageRngTrace")) return;
        append("phase=chest pos=" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()
            + " worldSeed=" + worldSeed + " count=" + before + "->" + after
            + " lootSeed=" + lootSeed);
    }

    public static void append(String line) {
        String target = System.getProperty("seedexplorer.villageRngTraceFile",
            "build/reports/village-live-rng.log");
        try {
            Path path = Path.of(target);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, line + System.lineSeparator(), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
