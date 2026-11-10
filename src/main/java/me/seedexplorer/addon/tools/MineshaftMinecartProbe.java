package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.util.List;

/** Probe: does the offline simulator capture mineshaft minecart chests? */
public final class MineshaftMinecartProbe {
    private static final PrintStream OUT = System.out;

    private MineshaftMinecartProbe() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            VanillaLootTables.loadAllFromBundled();
            OUT.println("[PROBE classloc] simulator=" + VanillaLootStructureSimulator.class
                .getProtectionDomain().getCodeSource().getLocation());
            OUT.println("[PROBE classloc] probe=" + MineshaftMinecartProbe.class
                .getProtectionDomain().getCodeSource().getLocation());
            long seed = args.length > 0 ? Long.parseLong(args[0]) : -5330673357612662400L;
            int radius = 512;
            List<GeneratedStructure> overworld = VanillaStructurePredictor.predictDimension(
                seed, 0, -radius, -radius, radius, radius, true, true);
            List<GeneratedStructure> mineshafts = overworld.stream()
                .filter(s -> s.type == StructureType.MINESHAFT)
                .limit(8)
                .toList();
            OUT.println("seed=" + seed + " mineshafts_found=" + mineshafts.size());
            for (GeneratedStructure s : mineshafts) {
                var start = me.seedexplorer.addon.worldgen.WorldgenEngine
                    .generateSelectedStructureStart(seed, 0, BuiltinStructures.MINESHAFT,
                        new net.minecraft.world.level.ChunkPos(s.startChunkX, s.startChunkZ));
                OUT.println("  mineshaft start_chunk=" + s.startChunkX + "," + s.startChunkZ
                    + " at=" + s.x + "," + s.z
                    + " startValid=" + (start != null && start.isValid())
                    + " pieces=" + (start == null ? -1 : start.getPieces().size()));
                var containers = VanillaLootStructureSimulator.simulate(
                    seed, 0, BuiltinStructures.MINESHAFT, s.startChunkX, s.startChunkZ);
                OUT.println("    -> simulate containers=" + containers.size());
                for (var c : containers) {
                    var loot = me.seedexplorer.addon.loot.ChestLootPredictor.predictChest(
                        seed, c.x(), c.y(), c.z(), c.lootTableId(), c.lootSeed(), 27);
                    OUT.println("    container=" + c.x() + "," + c.y() + "," + c.z()
                        + " table=" + c.lootTableId() + " seed=" + c.lootSeed()
                        + " items=" + loot.size());
                    for (var item : loot) {
                        OUT.println("        " + item);
                    }
                }
            }

            verifySavedWorldOracle(seed);
            verifySulfurCaveLoot(seed);
        } catch (Throwable t) {
            OUT.println("probe_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    private static void verifySavedWorldOracle(long requestedSeed) {
        if (requestedSeed != -5330673357612662400L) {
            OUT.println("saved_oracle_skipped=true reason=custom_seed");
            return;
        }
        GeneratedStructure structure = new GeneratedStructure(
            -93 * 16, 5 * 16, -93, 5, StructureType.MINESHAFT,
            "minecraft:mineshaft", false);
        List<ChestLootOutput> predicted = ChestLootPredictor.predictForStructure(
            requestedSeed, structure, 64);
        List<ChestLootOutput> matches = predicted.stream()
            .filter(chest -> chest.blockX() == -1472 && chest.blockY() == 25
                && chest.blockZ() == 66)
            .toList();
        if (matches.size() != 1) {
            throw new AssertionError("normal predictor returned " + matches.size()
                + " saved-oracle minecarts at -1472,25,66; expected exactly one");
        }
        ChestLootOutput match = matches.get(0);
        if (!match.lootTableId().equals("minecraft:chests/abandoned_mineshaft")) {
            throw new AssertionError("saved-oracle minecart loot table mismatch: "
                + match.lootTableId());
        }
        if (match.seed() != 4095185177439303177L) {
            throw new AssertionError("saved-oracle minecart loot seed mismatch: " + match.seed());
        }
        if (match.predictedItems().isEmpty()) {
            throw new AssertionError("saved-oracle minecart produced no predicted loot items");
        }
        var vanillaItems = LootTableSimulator.simulateVanilla(
            match.lootTableId(), match.seed(), requestedSeed, 0,
            match.blockX(), match.blockY(), match.blockZ());
        if (vanillaItems == null || !vanillaItems.equals(match.predictedItems())) {
            throw new AssertionError("normal predictor did not use exact vanilla minecart loot");
        }
        List<ChestLootOutput> repeated = ChestLootPredictor.predictForStructure(
            requestedSeed, structure, 64);
        ChestLootOutput repeatedMatch = repeated.stream()
            .filter(chest -> chest.blockX() == -1472 && chest.blockY() == 25
                && chest.blockZ() == 66)
            .findFirst()
            .orElseThrow(() -> new AssertionError("saved-oracle minecart disappeared on repeat"));
        if (!match.predictedItems().equals(repeatedMatch.predictedItems())) {
            throw new AssertionError("saved-oracle minecart loot was not deterministic");
        }
        OUT.println("saved_oracle_normal_api_match=true unique=true table=true seed=true vanilla_items=true items="
            + match.predictedItems().size());
    }

    private static void verifySulfurCaveLoot(long worldSeed) {
        if (BundledLootTableLoader.compareVersions(
            BundledLootTableLoader.runtimeProfile().minecraftVersion(), "26.3") < 0) {
            OUT.println("sulfur_cave_loot_skipped=true reason=runtime_before_26.3");
            return;
        }
        BlockPos sulfur = findBiome(worldSeed, "minecraft:sulfur_caves");
        if (sulfur == null) {
            throw new AssertionError("no sulfur cave biome found in deterministic search window");
        }
        BlockPos ordinary = findDifferentBiome(worldSeed, sulfur);
        if (ordinary == null) {
            throw new AssertionError("no non-sulfur comparison biome found");
        }

        String table = "minecraft:chests/abandoned_mineshaft";
        for (long lootSeed = 0; lootSeed < 4096; lootSeed++) {
            List<me.seedexplorer.addon.loot.ItemLoot> sulfurItems =
                LootTableSimulator.simulateVanilla(table, lootSeed, worldSeed, 0,
                    sulfur.getX(), sulfur.getY(), sulfur.getZ());
            if (sulfurItems == null || sulfurItems.stream()
                .noneMatch(item -> item.itemId().equals("minecraft:music_disc_bounce"))) continue;
            List<me.seedexplorer.addon.loot.ItemLoot> ordinaryItems =
                LootTableSimulator.simulateVanilla(table, lootSeed, worldSeed, 0,
                    ordinary.getX(), ordinary.getY(), ordinary.getZ());
            if (ordinaryItems == null) {
                throw new AssertionError("ordinary-biome vanilla mineshaft loot was unavailable");
            }
            if (ordinaryItems.stream().anyMatch(item ->
                item.itemId().equals("minecraft:music_disc_bounce"))) {
                throw new AssertionError("sulfur-only music disc escaped its biome condition");
            }
            List<me.seedexplorer.addon.loot.ItemLoot> product =
                ChestLootPredictor.predictChest(worldSeed, sulfur.getX(), sulfur.getY(),
                    sulfur.getZ(), table, lootSeed, 64);
            if (!product.equals(sulfurItems)) {
                throw new AssertionError("product mineshaft chest path lost sulfur-cave loot context");
            }
            OUT.println("sulfur_cave_loot_match=true biome=" + sulfur.getX() + ','
                + sulfur.getY() + ',' + sulfur.getZ() + " comparison=" + ordinary.getX()
                + ',' + ordinary.getY() + ',' + ordinary.getZ() + " loot_seed=" + lootSeed);
            return;
        }
        throw new AssertionError("no sulfur-cave music disc roll found in 4096 loot seeds");
    }

    private static BlockPos findBiome(long seed, String target) {
        int[] heights = {-48, -32, -16, 0, 16, 32};
        for (int radius = 0; radius <= 768; radius += 16) {
            for (int x = -radius; x <= radius; x += 16) {
                for (int z : new int[]{-radius, radius}) {
                    for (int y : heights) {
                        if (me.seedexplorer.addon.worldgen.WorldgenEngine
                            .getBiome(seed, 0, x, y, z).id().equals(target)) {
                            return new BlockPos(x, y, z);
                        }
                    }
                }
            }
            for (int z = -radius + 16; z < radius; z += 16) {
                for (int x : new int[]{-radius, radius}) {
                    for (int y : heights) {
                        if (me.seedexplorer.addon.worldgen.WorldgenEngine
                            .getBiome(seed, 0, x, y, z).id().equals(target)) {
                            return new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findDifferentBiome(long seed, BlockPos sulfur) {
        for (int x = -256; x <= 256; x += 16) {
            for (int z = -256; z <= 256; z += 16) {
                String biome = me.seedexplorer.addon.worldgen.WorldgenEngine
                    .getBiome(seed, 0, x, sulfur.getY(), z).id();
                if (!biome.equals("minecraft:sulfur_caves")) {
                    return new BlockPos(x, sulfur.getY(), z);
                }
            }
        }
        return null;
    }
}
