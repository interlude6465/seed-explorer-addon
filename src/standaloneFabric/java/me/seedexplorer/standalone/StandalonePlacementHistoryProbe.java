package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import net.minecraft.world.level.ChunkPos;

/** Opt-in diagnostic. Candidate replay is not independent live-world evidence. */
public final class StandalonePlacementHistoryProbe {
    private static final PrintStream OUT = System.out;

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException("Expected seed structure_id start_chunk_x start_chunk_z");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var context = new PredictionContext(Long.parseLong(args[0]), "26.3", 0);
        var key = ResourceKey.create(Registries.STRUCTURE, Identifier.parse(args[1]));
        int x = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
        String tracePath = System.getProperty("seedexplorer.historyTrace", "");
        if (!tracePath.isBlank()) {
            if (args.length != 4) throw new IllegalArgumentException("Trace replay cannot verify a saved pyramid region");
            if (!key.equals(net.minecraft.world.level.levelgen.structure.BuiltinStructures.MINESHAFT)
                && !key.equals(net.minecraft.world.level.levelgen.structure.BuiltinStructures.MINESHAFT_MESA)) {
                throw new IllegalArgumentException("Mineshaft trace replay requires a mineshaft structure ID");
            }
            var placement = Pattern.compile("phase=start source=live seed=(-?\\d+) chunk=\\[(-?\\d+), (-?\\d+)\\] start=\\[(-?\\d+), (-?\\d+)\\]");
            var order = new LinkedHashSet<ChunkPos>();
            for (String line : Files.readAllLines(Path.of(tracePath))) {
                var match = placement.matcher(line);
                if (!match.find() || Long.parseLong(match.group(1)) != context.worldSeed()
                    || Integer.parseInt(match.group(4)) != x || Integer.parseInt(match.group(5)) != z) continue;
                var chunk = new ChunkPos(Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
                if (!order.add(chunk)) throw new IllegalArgumentException("Duplicate placement chunk in trace: " + chunk);
            }
            if (order.isEmpty()) throw new IllegalArgumentException("No matching live placement trace");
            int referencedChunks = order.size();
            var start = WorldgenEngine.generateSelectedStructureStart(context.worldSeed(), 0, key, new ChunkPos(x, z));
            if (!start.isValid()) throw new IllegalArgumentException("No valid structure at the supplied start");
            var footprint = start.getPieces().stream()
                .flatMap(piece -> piece.getBoundingBox().intersectingChunks())
                .collect(java.util.stream.Collectors.toSet());
            // Vanilla references the whole enclosing bounding box, including gaps
            // where none of this start's pieces run. Preserve the relative order
            // of all actual placement chunks; the replay rejects missing chunks.
            order.removeIf(chunk -> !footprint.contains(chunk));
            int index = VanillaLootStructureSimulator.decorationIndex(key);
            var canonical = VanillaLootStructureSimulator.simulate(context.worldSeed(), 0, key, x, z, index);
            // The simulator validates that every intersecting chunk appears exactly once.
            // This uses observed generation order, never observed loot as replay input.
            var replay = VanillaLootStructureSimulator.simulateInChunkOrderForProbe(
                context.worldSeed(), 0, key, x, z, java.util.List.copyOf(order), index);
            OUT.println("TRACE_REPLAY seed=" + args[0] + " structure=" + args[1]
                + " start=" + x + "," + z + " referencedChunks=" + referencedChunks + " chunks=" + order.size());
            for (var container : canonical) OUT.println("CANONICAL " + container);
            for (var container : replay) {
                OUT.println("REPLAY " + container);
                var stacks = LootTableSimulator.simulateContainerStacks(context, container.lootTableId(),
                    container.lootSeed(), container.x(), container.y(), container.z(), 27);
                if (stacks == null) throw new AssertionError("Inventory simulation unavailable: " + container);
                OUT.println("REPLAY_STACKS pos=" + container.x() + "," + container.y() + "," + container.z()
                    + " stacks=" + stacks.stream().filter(stack -> !stack.isEmpty())
                        .map(StandalonePlacementHistoryProbe::stackSignature).sorted().toList());
            }
            OUT.println("TRACE_REPLAY_COMPLETE canonical=" + canonical.size() + " replay=" + replay.size());
            return;
        }
        var candidates = VanillaLootStructureSimulator.simulateScatteredPlacementCandidates(context, key, x, z);
        OUT.println("HISTORY_REPLAY seed=" + args[0] + " structure=" + args[1]
            + " start=" + x + "," + z + " candidates=" + candidates.size());
        for (var candidate : candidates) {
            OUT.println("FIRST_CHUNK " + candidate.firstChunk());
            for (var container : candidate.containers()) {
                OUT.println("CONTAINER pos=" + container.x() + "," + container.y() + "," + container.z()
                    + " table=" + container.lootTableId() + " seed=" + container.lootSeed());
            }
        }
        if (candidates.isEmpty()) throw new AssertionError("No placement candidates for the supplied fixture");
        if (args.length == 5) {
            if (!key.equals(net.minecraft.world.level.levelgen.structure.BuiltinStructures.DESERT_PYRAMID)) {
                throw new IllegalArgumentException("Saved region verification currently supports pyramids only");
            }
            StandaloneSavedPyramidProbe.verify(context, x, z, java.nio.file.Path.of(args[4]), OUT);
        }
    }

    private static String stackSignature(net.minecraft.world.item.ItemStack stack) {
        var enchantments = new java.util.TreeMap<String, Integer>();
        for (var type : java.util.List.of(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS)) {
            var values = stack.get(type);
            if (values == null) continue;
            for (var holder : values.keySet()) {
                holder.unwrapKey().ifPresent(key -> enchantments.put(key.identifier().toString(), values.getLevel(holder)));
            }
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount()
            + " enchantments=" + enchantments + " damage=" + LootTableSimulator.damageComponent(stack)
            + " potion=" + LootTableSimulator.potionContentsComponent(stack)
            + " stew=" + LootTableSimulator.suspiciousStewEffectsComponent(stack);
    }
}
