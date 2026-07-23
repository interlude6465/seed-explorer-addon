package me.seedexplorer.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureCache;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.List;

public class LootCommand extends Command {
    public LootCommand() {
        super("loot", "Predicts chest loot in structures near you.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            runLootNearby();
            return SINGLE_SUCCESS;
        });

        builder.then(argument("x", IntegerArgumentType.integer())
            .then(argument("z", IntegerArgumentType.integer())
                .executes(context -> {
                    int x = IntegerArgumentType.getInteger(context, "x");
                    int z = IntegerArgumentType.getInteger(context, "z");
                    runLootAt(x, z);
                    return SINGLE_SUCCESS;
                })
            )
        );
    }

    private void runLootNearby() {
        if (mc.player == null) {
            error("Join a world first.");
            return;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed <seed> first.");
            return;
        }

        int px = mc.player.blockPosition().getX();
        int pz = mc.player.blockPosition().getZ();
        int chunkX = px >> 4;
        int chunkZ = pz >> 4;

        int dimension = dimensionId();

        List<GeneratedStructure> structures = StructureCache.get().getStructures(
            chunkX - 16, chunkZ - 16, chunkX + 16, chunkZ + 16, dimension);

        GeneratedStructure closest = structures.stream()
            .filter(s -> ChestLootPredictor.canPredict(s.type))
            .min(Comparator.comparingDouble(s -> distSq(s.x, s.z, px, pz)))
            .orElse(null);

        if (closest == null) {
            structures = StructureCache.get().getStructures(
                chunkX - 16, chunkZ - 16, chunkX + 16, chunkZ + 16, dimension, true, true);
            closest = structures.stream()
                .filter(s -> ChestLootPredictor.canPredict(s.type))
                .min(Comparator.comparingDouble(s -> distSq(s.x, s.z, px, pz)))
                .orElse(null);
        }

        if (closest == null) {
            error("No loot-bearing structure found nearby. Try .loot <x> <z>.");
            return;
        }

        showLoot(closest);
    }

    private void runLootAt(int x, int z) {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed <seed> first.");
            return;
        }

        int dimension = dimensionId();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        List<GeneratedStructure> structures = StructureCache.get().getStructures(
            chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1, dimension, true, true);

        GeneratedStructure closest = structures.stream()
            .filter(s -> ChestLootPredictor.canPredict(s.type))
            .min(Comparator.comparingDouble(s -> distSq(s.x, s.z, x, z)))
            .orElse(null);

        if (closest == null) {
            info("No supported structure with loot at (%d, %d).", x, z);
            return;
        }

        showLoot(closest);
    }

    private void showLoot(GeneratedStructure structure) {
        info("Predicting loot for (highlight)%s(default) at (highlight)%d, %d(default)...",
            structure.displayName(), structure.x, structure.z);

        List<ChestLootOutput> results = ChestLootPredictor.predictForStructure(structure);

        if (results.isEmpty()) {
            info("  No loot table data available for this structure.");
            return;
        }

        for (ChestLootOutput chest : results) {
            String pos = "(" + chest.blockX() + ", " + chest.blockZ() + ")";
            if (chest.isEmpty()) {
                info("  Chest at %s: (warning)No prediction(default)", pos);
            } else {
                info("  Chest at %s: (highlight)%s(default)", pos, chest.summary());
            }
        }
    }

    private double distSq(int x1, int z1, int x2, int z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private int dimensionId() {
        if (mc.level == null) return 0;
        if (mc.level.dimension() == Level.NETHER) return -1;
        if (mc.level.dimension() == Level.END) return 1;
        return 0;
    }
}
