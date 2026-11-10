package me.seedexplorer.standalone;

import net.minecraft.core.BlockPos;

/** Optional pathing boundary; the standalone mod remains usable without Baritone. */
public interface StandaloneNavigationCapability {
    boolean available();

    Result navigate(BlockPos target, int radius);

    void stop();

    String status();

    record Result(boolean accepted, String message) {
    }

    static StandaloneNavigationCapability unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements StandaloneNavigationCapability {
        INSTANCE;

        @Override public boolean available() { return false; }
        @Override public Result navigate(BlockPos target, int radius) {
            return new Result(false, "No navigation provider is installed");
        }
        @Override public void stop() { }
        @Override public String status() { return "unavailable"; }
    }
}
