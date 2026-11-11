package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Request-safe selection and loot state for the standalone 3D map. */
final class Standalone3DSelectionController {
    private static final ThreadPoolExecutor LOOT_EXECUTOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer 3D loot");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });

    private final long seed;
    private final int dimension;
    private final StandaloneSeedExplorerConfig config;
    private final AtomicLong requests = new AtomicLong();
    private volatile GeneratedStructure structure;
    private volatile List<ChestLootOutput> chests = List.of();
    private volatile ChestLootOutput chest;
    private volatile boolean loading;
    private volatile boolean supported;
    private volatile String error = "";

    Standalone3DSelectionController(long seed, int dimension,
                                    StandaloneSeedExplorerConfig config) {
        this.seed = seed;
        this.dimension = dimension;
        this.config = config;
    }

    void select(GeneratedStructure selected) {
        structure = selected;
        chests = List.of();
        chest = null;
        error = "";
        long request = requests.incrementAndGet();
        if (selected == null) {
            supported = false;
            loading = false;
            return;
        }
        PredictionContext context = context();
        StandaloneLootRequestGuard guard = StandaloneLootRequestGuard.capture(context);
        supported = selected.type == StructureType.DUNGEON
            || StandaloneStructureLootPredictor.structureKey(context, selected) != null;
        loading = false;
        if (!supported) return;
        var cached = StandalonePersistentLootCache.get(context, selected);
        var observations = SeedExplorerFabricClient.lootObservations(context);
        if (cached.isPresent() && !StandaloneObservedLootRefinement.canRefine(context, selected, observations)) {
            chests = cached.get();
            chest = chests.isEmpty() ? null : chests.getFirst();
            loading = false;
            StandaloneObservedLootRefinement.remember(context, selected, chests, chests);
            return;
        }
        loading = true;
        LOOT_EXECUTOR.execute(() -> {
            if (requests.get() != request) return;
            try {
                List<ChestLootOutput> canonical = cached.orElseGet(() ->
                    StandaloneStructureLootPredictor.predict(context, selected, 64));
                if (cached.isEmpty()) StandalonePersistentLootCache.put(context, selected, canonical);
                List<ChestLootOutput> predicted = StandaloneObservedLootRefinement.refine(
                    context, selected, canonical, observations);
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (requests.get() != request || structure != selected) return;
                    if (!guard.isCurrent()) {
                        loading = false;
                        error = "World or prediction settings changed; select again";
                        return;
                    }
                    chests = predicted;
                    chest = predicted.isEmpty() ? null : predicted.getFirst();
                    loading = false;
                    StandaloneObservedLootRefinement.remember(context, selected, canonical, predicted);
                });
            } catch (Throwable failure) {
                com.mojang.logging.LogUtils.getLogger().error(
                    "3D loot prediction failed for {} at {},{} ({})",
                    selected.type, selected.startChunkX, selected.startChunkZ, context, failure);
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (requests.get() != request || structure != selected) return;
                    if (!guard.isCurrent()) {
                        loading = false;
                        error = "World or prediction settings changed; select again";
                        return;
                    }
                    error = failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
                    loading = false;
                });
            }
        });
    }

    void selectKnown(GeneratedStructure selected, List<ChestLootOutput> knownChests,
                     int selectedChestIndex) {
        requests.incrementAndGet();
        structure = selected;
        chests = knownChests == null ? List.of() : List.copyOf(knownChests);
        chest = selectedChestIndex >= 0 && selectedChestIndex < chests.size()
            ? chests.get(selectedChestIndex) : chests.isEmpty() ? null : chests.getFirst();
        error = "";
        loading = false;
        PredictionContext context = context();
        supported = selected != null && (selected.type == StructureType.DUNGEON
            || StandaloneStructureLootPredictor.structureKey(context, selected) != null);
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && selected != null) {
            List<ChestLootOutput> canonical = StandalonePersistentLootCache.get(context, selected)
                .orElse(chests);
            runtime.rememberStructureLoot(context,
                StandaloneObservedLootRefinement.structureKey(selected), canonical, chests);
        }
        if (selected != null && !StandaloneObservedLootRefinement.historySensitive(selected))
            StandalonePersistentLootCache.put(context, selected, chests);
    }

    void selectChest(ChestLootOutput selected) {
        if (selected != null && chests.contains(selected)) chest = selected;
    }

    void selectChest(int index) {
        if (index >= 0 && index < chests.size()) chest = chests.get(index);
    }

    int selectedChestIndex() {
        return chest == null ? -1 : chests.indexOf(chest);
    }

    PredictionContext context() {
        return new PredictionContext(seed, config.predictionVersion(), dimension);
    }

    GeneratedStructure structure() {
        return structure;
    }

    List<ChestLootOutput> chests() {
        return chests;
    }

    ChestLootOutput chest() {
        return chest;
    }

    boolean loading() {
        return loading;
    }

    boolean supported() {
        return supported;
    }

    String error() {
        return error;
    }

    void close() {
        requests.incrementAndGet();
        loading = false;
    }

}
