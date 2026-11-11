package me.seedexplorer.standalone;

import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.Minecraft;

/** Binds asynchronous loot publication to the client world session that requested it. */
final class StandaloneLootRequestGuard {
    private final PredictionContext context;
    private final Object level;
    private final StandaloneAncillaryRuntime runtime;
    private final long generation;

    private StandaloneLootRequestGuard(PredictionContext context, Object level,
                                       StandaloneAncillaryRuntime runtime, long generation) {
        this.context = context;
        this.level = level;
        this.runtime = runtime;
        this.generation = generation;
    }

    static StandaloneLootRequestGuard capture(PredictionContext context) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null) runtime.refreshSession(Minecraft.getInstance());
        return new StandaloneLootRequestGuard(context, Minecraft.getInstance().level, runtime,
            runtime == null ? Long.MIN_VALUE : runtime.sessionGeneration());
    }

    boolean isCurrent() {
        Minecraft client = Minecraft.getInstance();
        // The map can inspect another dimension while the player remains here.
        // Only an actual session/seed/version change invalidates publication.
        return client.level == level && (runtime == null
            || runtime.isCurrentSession(context, level, generation));
    }
}
