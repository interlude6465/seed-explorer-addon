package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares offline mineshaft minecart replay with an independent saved-world entity oracle. */
public final class MineshaftSavedOracleComparator {
    private static final int START_SEARCH_RADIUS_CHUNKS = 12;

    private MineshaftSavedOracleComparator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: <seed> <entity-region-dir> [limit]");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaLootTables.loadAllFromBundled();
        long seed = Long.parseLong(args[0]);
        Path entityDir = Path.of(args[1]).toAbsolutePath();
        Path chunkRegionDir = entityDir.resolveSibling("region");
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        List<MineshaftSavedOracleReport.SavedMinecart> all =
            MineshaftSavedOracleReport.scan(entityDir).minecarts();
        List<MineshaftSavedOracleReport.SavedMinecart> selected;
        if (args.length >= 5) {
            int targetX = Integer.parseInt(args[3]);
            int targetZ = Integer.parseInt(args[4]);
            selected = all.stream().filter(cart -> cart.blockX() == targetX && cart.blockZ() == targetZ)
                .limit(1).toList();
        } else {
            selected = representativeSample(all, limit);
        }

        Map<String, List<VanillaLootStructureSimulator.SimulatedContainer>> simulationCache = new HashMap<>();
        StringBuilder report = new StringBuilder();
        int coordinateMatches = 0;
        int seedMatches = 0;
        int resolvedContentsCompared = 0;
        int contentsMatches = 0;
        int noOwningStart = 0;
        for (MineshaftSavedOracleReport.SavedMinecart actual : selected) {
            List<GeneratedStructure> owners = owningStarts(seed, actual);
            Match best = null;
            Match seedMatched = null;
            for (GeneratedStructure owner : owners) {
                String startKey = owner.variant + '@' + owner.startChunkX + ',' + owner.startChunkZ;
                List<VanillaLootStructureSimulator.SimulatedContainer> predicted = simulationCache.computeIfAbsent(
                    startKey, ignored -> VanillaLootStructureSimulator.simulate(seed, 0, structureKey(owner),
                        owner.startChunkX, owner.startChunkZ));
                for (VanillaLootStructureSimulator.SimulatedContainer container : predicted) {
                    int distance = Math.abs(container.x() - actual.blockX())
                        + Math.abs(container.y() - actual.blockY())
                        + Math.abs(container.z() - actual.blockZ());
                    Match candidate = new Match(owner, container, distance);
                    if (best == null || candidate.distance() < best.distance()) best = candidate;
                    if (actual.lootSeed() != null && container.lootSeed() == actual.lootSeed()) {
                        seedMatched = candidate;
                    }
                }
            }
            boolean coordinateMatch = best != null && best.distance() == 0;
            boolean seedMatch = seedMatched != null;
            if (owners.isEmpty()) noOwningStart++;
            if (coordinateMatch) coordinateMatches++;
            if (seedMatch) seedMatches++;
            Map<String, Integer> predictedItems = best == null ? Map.of() : predictedItems(best.container());
            Boolean contentsMatch = null;
            if (actual.lootSeed() == null && !actual.items().isEmpty()) {
                resolvedContentsCompared++;
                contentsMatch = predictedItems.equals(actual.items());
                if (contentsMatch) contentsMatches++;
            }
            report.append("actual=").append(position(actual.blockX(), actual.blockY(), actual.blockZ()))
                .append(" seed=").append(actual.lootSeed())
                .append(" owner_candidates=").append(owners.size());
            if (best == null) {
                report.append(" predicted=<none>\n");
                continue;
            }
            report.append(" owner_start=").append(best.owner().startChunkX).append(',')
                .append(best.owner().startChunkZ).append(" owner_variant=").append(best.owner().variant)
                .append(" predicted=").append(position(best.container().x(), best.container().y(), best.container().z()))
                .append(" predicted_seed=").append(best.container().lootSeed())
                .append(" coordinate_match=").append(coordinateMatch)
                .append(" seed_match=").append(seedMatch)
                .append(" predicted_items=").append(predictedItems);
            if (seedMatched != null && seedMatched.distance() != 0) {
                report.append(" seed_matched_origin=").append(position(seedMatched.container().x(),
                        seedMatched.container().y(), seedMatched.container().z()))
                    .append(" displacement=").append(seedMatched.container().x() - actual.blockX()).append(',')
                    .append(seedMatched.container().y() - actual.blockY()).append(',')
                    .append(seedMatched.container().z() - actual.blockZ());
            }
            if (contentsMatch != null) report.append(" contents_match=").append(contentsMatch);
            report.append('\n');
            if (!coordinateMatch) {
                report.append("  generated_piece=").append(generatedPieceSummary(seed, best.owner(), actual))
                    .append('\n');
                report.append("  saved_start=").append(savedStartSummary(chunkRegionDir,
                    best.owner().startChunkX, best.owner().startChunkZ, actual)).append('\n');
                report.append("  index_sweep=").append(indexSweep(seed, best.owner(), actual)).append('\n');
                report.append("  owner_cart_summary=").append(ownerCartSummary(seed, best.owner(), all,
                    simulationCache.get(best.owner().variant + '@' + best.owner().startChunkX + ','
                        + best.owner().startChunkZ))).append('\n');
            }
        }

        report.insert(0, "seed=" + seed + " mineshaft_decoration_index="
            + VanillaLootStructureSimulator.decorationIndex(BuiltinStructures.MINESHAFT)
            + " mesa_decoration_index="
            + VanillaLootStructureSimulator.decorationIndex(BuiltinStructures.MINESHAFT_MESA)
            + " saved_minecarts=" + all.size() + " compared=" + selected.size()
            + " simulated_starts=" + simulationCache.size() + " coordinate_matches=" + coordinateMatches
            + " seed_matches=" + seedMatches + " resolved_contents_compared=" + resolvedContentsCompared
            + " contents_matches=" + contentsMatches + " no_owning_start=" + noOwningStart + '\n');
        Path reportPath = Path.of("build", "reports", "mineshaft-saved-oracle-comparison.txt").toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        System.out.print(report);
        System.out.println("report=" + reportPath);
    }

    private static List<MineshaftSavedOracleReport.SavedMinecart> representativeSample(
        List<MineshaftSavedOracleReport.SavedMinecart> carts, int limit) {
        List<MineshaftSavedOracleReport.SavedMinecart> sorted = carts.stream()
            .filter(cart -> cart.lootTable().equals("minecraft:chests/abandoned_mineshaft"))
            .sorted(Comparator.comparingInt(MineshaftSavedOracleReport.SavedMinecart::entityChunkX)
                .thenComparingInt(MineshaftSavedOracleReport.SavedMinecart::entityChunkZ))
            .toList();
        if (sorted.size() <= limit) return sorted;
        Set<MineshaftSavedOracleReport.SavedMinecart> sample = new LinkedHashSet<>();
        for (int i = 0; i < limit; i++) {
            int index = (int) Math.round(i * (sorted.size() - 1.0) / Math.max(1, limit - 1));
            sample.add(sorted.get(index));
        }
        return List.copyOf(sample);
    }

    private static List<GeneratedStructure> owningStarts(long seed,
        MineshaftSavedOracleReport.SavedMinecart cart) {
        int chunkX = Math.floorDiv(cart.blockX(), 16);
        int chunkZ = Math.floorDiv(cart.blockZ(), 16);
        List<GeneratedStructure> candidates = VanillaStructurePredictor.predictDimension(seed, 0,
            chunkX - START_SEARCH_RADIUS_CHUNKS, chunkZ - START_SEARCH_RADIUS_CHUNKS,
            chunkX + START_SEARCH_RADIUS_CHUNKS, chunkZ + START_SEARCH_RADIUS_CHUNKS,
            false, true).stream().filter(structure -> structure.type == StructureType.MINESHAFT).toList();
        BlockPos target = new BlockPos(cart.blockX(), cart.blockY(), cart.blockZ());
        List<GeneratedStructure> owners = new ArrayList<>();
        for (GeneratedStructure candidate : candidates) {
            try {
                StructureStart start = WorldgenEngine.generateSelectedStructureStart(seed, 0,
                    structureKey(candidate), new ChunkPos(candidate.startChunkX, candidate.startChunkZ));
                if (start.isValid() && start.getPieces().stream()
                    .anyMatch(piece -> piece.getBoundingBox().inflatedBy(1).isInside(target))) {
                    owners.add(candidate);
                }
            } catch (Throwable ignored) {
            }
        }
        return List.copyOf(owners);
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>
    structureKey(GeneratedStructure structure) {
        return structure.variant.equals("minecraft:mineshaft_mesa")
            ? BuiltinStructures.MINESHAFT_MESA : BuiltinStructures.MINESHAFT;
    }

    private static Map<String, Integer> predictedItems(
        VanillaLootStructureSimulator.SimulatedContainer container) {
        List<ItemLoot> items = LootTableSimulator.simulateVanilla(
            container.lootTableId(), container.lootSeed());
        if (items == null) {
            LootTableDef table = VanillaLootTables.get(container.lootTableId());
            items = table == null ? List.of() : LootTableSimulator.simulate(table, container.lootSeed(), 64);
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ItemLoot item : items) result.merge(item.itemId(), item.minCount(), Integer::sum);
        return Map.copyOf(result);
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String indexSweep(long seed, GeneratedStructure owner,
                                     MineshaftSavedOracleReport.SavedMinecart actual) {
        Map<Integer, List<VanillaLootStructureSimulator.SimulatedContainer>> sweep =
            VanillaLootStructureSimulator.simulateChunkIndexSweep(seed, structureKey(owner),
                owner.startChunkX, owner.startChunkZ, actual.entityChunkX(), actual.entityChunkZ(), 0, 63);
        List<String> coordinateHits = coordinateHits(actual, sweep);
        if (coordinateHits.isEmpty()) {
            Map<Integer, List<VanillaLootStructureSimulator.SimulatedContainer>> rawTerrainSweep =
                VanillaLootStructureSimulator.simulateChunkIndexSweep(seed, structureKey(owner),
                    owner.startChunkX, owner.startChunkZ, actual.entityChunkX(), actual.entityChunkZ(),
                    0, 63, false);
            List<String> rawTerrainHits = coordinateHits(actual, rawTerrainSweep);
            if (!rawTerrainHits.isEmpty()) coordinateHits.add("without_earlier_features=" + rawTerrainHits);
        }
        return coordinateHits.toString();
    }

    private static List<String> coordinateHits(MineshaftSavedOracleReport.SavedMinecart actual,
        Map<Integer, List<VanillaLootStructureSimulator.SimulatedContainer>> sweep) {
        List<String> coordinateHits = new ArrayList<>();
        for (Map.Entry<Integer, List<VanillaLootStructureSimulator.SimulatedContainer>> entry : sweep.entrySet()) {
            for (VanillaLootStructureSimulator.SimulatedContainer container : entry.getValue()) {
                if (container.x() == actual.blockX() && container.y() == actual.blockY()
                    && container.z() == actual.blockZ()) {
                    coordinateHits.add(entry.getKey() + ":seed=" + container.lootSeed()
                        + ":seedMatch=" + (actual.lootSeed() != null && actual.lootSeed() == container.lootSeed()));
                }
            }
        }
        return coordinateHits;
    }

    private static String ownerCartSummary(long seed, GeneratedStructure owner,
        List<MineshaftSavedOracleReport.SavedMinecart> actualCarts,
        List<VanillaLootStructureSimulator.SimulatedContainer> predicted) {
        StructureStart start = WorldgenEngine.generateSelectedStructureStart(seed, 0,
            structureKey(owner), new ChunkPos(owner.startChunkX, owner.startChunkZ));
        List<MineshaftSavedOracleReport.SavedMinecart> owned = actualCarts.stream()
            .filter(cart -> start.getPieces().stream().anyMatch(piece -> piece.getBoundingBox().inflatedBy(1)
                .isInside(new BlockPos(cart.blockX(), cart.blockY(), cart.blockZ()))))
            .toList();
        int coordinates = 0;
        int seeds = 0;
        int seedsAtAnyPosition = 0;
        for (MineshaftSavedOracleReport.SavedMinecart actual : owned) {
            VanillaLootStructureSimulator.SimulatedContainer match = predicted.stream().filter(container ->
                container.x() == actual.blockX() && container.y() == actual.blockY()
                    && container.z() == actual.blockZ()).findFirst().orElse(null);
            if (match == null) continue;
            coordinates++;
            if (actual.lootSeed() != null && actual.lootSeed() == match.lootSeed()) seeds++;
        }
        for (MineshaftSavedOracleReport.SavedMinecart actual : owned) {
            if (actual.lootSeed() != null && predicted.stream()
                .anyMatch(container -> container.lootSeed() == actual.lootSeed())) seedsAtAnyPosition++;
        }
        return "actual=" + owned.size() + " predicted=" + predicted.size()
            + " coordinate_matches=" + coordinates + " seed_matches_at_coordinate=" + seeds
            + " seed_matches_any_position=" + seedsAtAnyPosition;
    }

    private static String generatedPieceSummary(long seed, GeneratedStructure owner,
        MineshaftSavedOracleReport.SavedMinecart cart) {
        try {
            StructureStart start = WorldgenEngine.generateSelectedStructureStart(seed, 0,
                structureKey(owner), new ChunkPos(owner.startChunkX, owner.startChunkZ));
            BlockPos target = new BlockPos(cart.blockX(), cart.blockY(), cart.blockZ());
            return start.getPieces().stream()
                .filter(piece -> piece.getBoundingBox().inflatedBy(2).isInside(target))
                .map(piece -> piece.getClass().getSimpleName() + ':' + piece.getBoundingBox())
                .toList().toString();
        } catch (Throwable failure) {
            return "error:" + failure.getClass().getSimpleName();
        }
    }

    private static String savedStartSummary(Path regionDir, int startChunkX, int startChunkZ,
                                            MineshaftSavedOracleReport.SavedMinecart cart) {
        ChunkPos chunk = new ChunkPos(startChunkX, startChunkZ);
        Path file = regionDir.resolve("r." + Math.floorDiv(startChunkX, 32)
            + "." + Math.floorDiv(startChunkZ, 32) + ".mca");
        if (!Files.isRegularFile(file)) return "region_missing";
        RegionStorageInfo info = new RegionStorageInfo("mineshaft_start_oracle", Level.OVERWORLD, "chunk");
        try (RegionFile region = new RegionFile(info, file, regionDir, false);
             DataInputStream input = region.getChunkDataInputStream(chunk)) {
            if (input == null) return "chunk_missing";
            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            CompoundTag starts = root.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
            CompoundTag start = starts.getCompoundOrEmpty("minecraft:mineshaft");
            if (start.isEmpty()) start = starts.getCompoundOrEmpty("mineshaft");
            if (start.isEmpty()) return "start_missing keys=" + starts.keySet();
            List<String> nearby = new ArrayList<>();
            for (var value : start.getListOrEmpty("Children")) {
                if (!(value instanceof CompoundTag child)) continue;
                int[] box = child.getIntArray("BB").orElse(new int[0]);
                if (box.length != 6) continue;
                if (cart.blockX() >= box[0] - 2 && cart.blockX() <= box[3] + 2
                    && cart.blockY() >= box[1] - 2 && cart.blockY() <= box[4] + 2
                    && cart.blockZ() >= box[2] - 2 && cart.blockZ() <= box[5] + 2) {
                    nearby.add(child.getString("id").orElse("?") + ':'
                        + java.util.Arrays.toString(box));
                }
            }
            return "children=" + start.getListOrEmpty("Children").size() + " nearby=" + nearby;
        } catch (Throwable failure) {
            return "error:" + failure.getClass().getSimpleName() + ':' + failure.getMessage();
        }
    }

    private record Match(GeneratedStructure owner,
                         VanillaLootStructureSimulator.SimulatedContainer container,
                         int distance) {
    }
}
