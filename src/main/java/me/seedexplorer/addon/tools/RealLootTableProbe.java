package me.seedexplorer.addon.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * A/B proof-of-concept: run the REAL vanilla LootTable.getRandomItems against a
 * known oracle chest seed, using the fake ServerLevel, and print the result.
 * If it matches the oracle (desert_pyramid seed 5464347146851317094 =
 * bone x5, rotten_flesh x5, saddle x1, carrot x1 ...), real loot tables can
 * replace the hand-rolled simulator and give exact enchantments for free.
 */
public final class RealLootTableProbe {
    private static final PrintStream OUT = System.out;

    private RealLootTableProbe() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();

            String tableId = args.length > 0 ? args[0] : "chests/desert_pyramid";
            long lootSeed = args.length > 1 ? Long.parseLong(args[1]) : 5464347146851317094L;

            // Load the bundled JSON and parse it into a REAL LootTable via DIRECT_CODEC.
            String resourcePath = "/data/minecraft/loot_table/" + tableId + ".json";
            JsonElement json;
            try (InputStream in = RealLootTableProbe.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    OUT.println("resource_not_found=" + resourcePath);
                    System.exit(1);
                    return;
                }
                json = JsonParser.parseReader(new InputStreamReader(in));
            }

            RegistryAccess registryAccess = WorldgenEngine.offlineRegistryAccess();
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
            LootTable table = LootTable.DIRECT_CODEC.parse(ops, json)
                .getOrThrow(err -> new IllegalStateException("codec parse failed: " + err));
            OUT.println("parsed_table=" + tableId + " loot_seed=" + lootSeed);

            Object fakeLevel = VanillaLootStructureSimulator.fakeLevelForProbe();
            if (fakeLevel == null) {
                OUT.println("fake_level_null");
                System.exit(1);
                return;
            }

            net.minecraft.server.level.ServerLevel level =
                (net.minecraft.server.level.ServerLevel) fakeLevel;
            LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, new Vec3(0, 64, 0))
                .create(LootContextParamSets.CHEST);

            var items = table.getRandomItems(params, lootSeed);
            OUT.println("item_count=" + items.size());
            for (ItemStack stack : items) {
                OUT.println("  " + stack.getCount() + "x "
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    + " components=" + stack.getComponents());
            }
        } catch (Throwable t) {
            OUT.println("probe_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }
}
