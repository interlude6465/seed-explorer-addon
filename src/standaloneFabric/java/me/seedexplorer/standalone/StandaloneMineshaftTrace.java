package me.seedexplorer.standalone;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Opt-in mineshaft diagnostics, kept outside the mixin-only package. */
public final class StandaloneMineshaftTrace {
    private StandaloneMineshaftTrace() {}
    public static boolean enabled() { return Boolean.getBoolean("seedexplorer.mineshaftTrace"); }
    public static String source(Object level) {
        return java.lang.reflect.Proxy.isProxyClass(level.getClass()) ? "offline" : "live";
    }
    public static int count(RandomSource random) {
        return random instanceof WorldgenRandom worldgen ? worldgen.getCount() : -1;
    }
    public static synchronized void log(String text) {
        try {
            Path file = Path.of("build/reports/mineshaft-placement.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, text + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

}
