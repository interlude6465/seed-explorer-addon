package me.seedexplorer.standalone;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Refines mutable scattered structures using observed blocks, never container contents. */
final class StandaloneObservedLootRefinement {
    private StandaloneObservedLootRefinement() { }

    static boolean historySensitive(GeneratedStructure structure) {
        return structure != null && (structure.type == StructureType.DESERT_PYRAMID
            || structure.type == StructureType.SHIPWRECK
                && "minecraft:shipwreck_beached".equals(structure.variant));
    }

    static boolean canRefine(PredictionContext context, GeneratedStructure structure,
                             List<LiveChunkSnapshot> observations) {
        return "26.3".equals(context.minecraftVersion()) && historySensitive(structure)
            && !observations.isEmpty();
    }

    static void remember(PredictionContext context, GeneratedStructure structure,
                          List<ChestLootOutput> canonical,
                          List<ChestLootOutput> displayed) {
        var runtime = StandaloneAncillaryRuntime.get();
        if (runtime == null) return;
        runtime.rememberStructureLoot(context, structureKey(structure), canonical, displayed);
    }

    static String structureKey(GeneratedStructure structure) {
        return structure.type + ":" + structure.startChunkX + ":" + structure.startChunkZ
            + ":" + structure.x + ":" + structure.y + ":" + structure.z + ":" + structure.variant;
    }

    /** Worker-only. The caller saves the canonical prediction before this operation;
     * refined output is specific to one observed world and must not enter the seed-only cache. */
    static List<ChestLootOutput> refine(PredictionContext context, GeneratedStructure structure,
                                        List<ChestLootOutput> canonical,
                                        List<LiveChunkSnapshot> observations) {
        if (!canRefine(context, structure, observations) || canonical.isEmpty()) return canonical;
        var columns = observedColumns(context.dimension(), canonical, observations, System.currentTimeMillis());
        if (columns.isEmpty()) return canonical;
        var observed = columns.values().stream().flatMap(List::stream).toList();
        if (observed.isEmpty()) return reconcile(canonical, columns, false);
        var candidates = VanillaLootStructureSimulator.simulateScatteredPlacementCandidates(
            context, StandaloneStructureLootPredictor.structureKey(context, structure),
            structure.startChunkX, structure.startChunkZ);
        var selected = VanillaLootStructureSimulator.selectUniqueContainerCandidate(candidates, observed);
        if (selected.isEmpty()) return reconcile(canonical, columns, false);
        var modeled = StandaloneStructureLootPredictor.predictContainers(context, selected.get(), 64);
        // Pyramid archaeology can move horizontally as well as vertically. Check
        // the selected candidate's columns too, using the same captured world.
        return reconcile(modeled, observedColumns(context.dimension(), modeled,
            observations, System.currentTimeMillis()), true);
    }

    static List<VanillaLootStructureSimulator.ObservedContainerPosition> observedContainers(
            int dimension, List<ChestLootOutput> canonical,
            List<LiveChunkSnapshot> observations, long now) {
        return observedColumns(dimension, canonical, observations, now).values()
            .stream().flatMap(List::stream).toList();
    }

    static List<ChestLootOutput> reconcileObservedPositions(int dimension,
            List<ChestLootOutput> modeled, List<LiveChunkSnapshot> observations,
            long now, boolean uniqueHistory) {
        return reconcile(modeled, observedColumns(dimension, modeled, observations, now), uniqueHistory);
    }

    private static Map<Long, List<VanillaLootStructureSimulator.ObservedContainerPosition>> observedColumns(
            int dimension, List<ChestLootOutput> canonical,
            List<LiveChunkSnapshot> observations, long now) {
        Map<Long, LiveChunkSnapshot> chunks = new HashMap<>();
        for (LiveChunkSnapshot snapshot : observations) {
            long age = now - snapshot.updatedAt();
            if (snapshot.dimension() == dimension && age >= 0
                && age <= StandaloneLiveChunkCapture.RECAPTURE_MILLIS
                && snapshot.usableForTerrainOverride()) {
                chunks.put(key(snapshot.chunkX(), snapshot.chunkZ()), snapshot);
            }
        }
        Map<Long, List<VanillaLootStructureSimulator.ObservedContainerPosition>> columns = new LinkedHashMap<>();
        for (ChestLootOutput chest : canonical) {
            long column = key(chest.blockX(), chest.blockZ());
            if (columns.containsKey(column)) continue;
            LiveChunkSnapshot snapshot = chunks.get(key(Math.floorDiv(chest.blockX(), 16),
                Math.floorDiv(chest.blockZ(), 16)));
            if (snapshot == null || chest.blockY() < snapshot.minY()
                || chest.blockY() >= snapshot.maxY()) continue;
            boolean archaeology = canonical.stream().anyMatch(value ->
                key(value.blockX(), value.blockZ()) == column
                    && value.lootTableId().startsWith("minecraft:archaeology/"));
            boolean ordinaryChest = canonical.stream().anyMatch(value ->
                key(value.blockX(), value.blockZ()) == column
                    && !value.lootTableId().startsWith("minecraft:archaeology/"));
            List<VanillaLootStructureSimulator.ObservedContainerPosition> found = new ArrayList<>();
            int localX = Math.floorMod(chest.blockX(), 16), localZ = Math.floorMod(chest.blockZ(), 16);
            for (int y = snapshot.minY(); y < snapshot.maxY(); y++) {
                var state = snapshot.block(localX, y, localZ);
                if (state != null && (ordinaryChest && state.is(Blocks.CHEST)
                    || archaeology && (state.is(Blocks.SUSPICIOUS_SAND)
                        || state.is(Blocks.SUSPICIOUS_GRAVEL)))) {
                    found.add(new VanillaLootStructureSimulator.ObservedContainerPosition(
                        chest.blockX(), y, chest.blockZ()));
                }
            }
            columns.put(column, List.copyOf(found));
        }
        return columns;
    }

    private static List<ChestLootOutput> reconcile(List<ChestLootOutput> modeled,
            Map<Long, List<VanillaLootStructureSimulator.ObservedContainerPosition>> observations,
            boolean uniqueHistory) {
        if (observations.isEmpty()) return modeled;
        List<ChestLootOutput> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (ChestLootOutput chest : modeled) {
            long column = key(chest.blockX(), chest.blockZ());
            var observed = observations.get(column);
            if (observed == null) {
                result.add(chest);
                continue;
            }
            if (!visited.add(column)) continue;
            for (var position : observed) {
                ChestLootOutput matching = uniqueHistory ? modeled.stream().filter(candidate ->
                    candidate.blockX() == position.x() && candidate.blockY() == position.y()
                        && candidate.blockZ() == position.z()).findFirst().orElse(null) : null;
                result.add(matching == null
                    ? ChestLootOutput.positionOnly(position.x(), position.y(), position.z()) : matching);
            }
        }
        return List.copyOf(result);
    }

    private static long key(int x, int z) { return (long) x << 32 ^ z & 0xFFFFFFFFL; }
}
