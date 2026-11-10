package me.seedexplorer.addon.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic simulator for the supported vanilla loot-table operations. */
public final class LootTableSimulator {
    // Expansion order of #minecraft:on_random_loot in the bundled vanilla data.
    // Vanilla's lightweight construction registries expose values but do not bind tags,
    // so the offline differential harness must retain the data-pack order explicitly.
    private static final List<String> RANDOM_LOOT_ENCHANTMENTS = List.of(
        "protection", "fire_protection", "feather_falling", "blast_protection",
        "projectile_protection", "respiration", "aqua_affinity", "thorns", "depth_strider",
        "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting",
        "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch",
        "flame", "infinity", "luck_of_the_sea", "lure", "loyalty", "impaling", "riptide",
        "channeling", "multishot", "quick_charge", "piercing", "density", "breach", "lunge",
        "binding_curse", "vanishing_curse", "frost_walker", "mending"
    );

    private record EnchantmentRoll(String id, int level) {
    }

    // Enchantment pools never change after Bootstrap. Building them on every enchanted-item
    // roll (64 rolls/chest x hundreds of chests) was allocating enough to exhaust heap/CPU.
    // Cache both pools lazily on first use.
    private static volatile List<Holder<Enchantment>> bookEnchantmentPool;
    private static volatile List<String> bookEnchantmentIds;
    private static volatile List<Holder<Enchantment>> gearEnchantmentPool;
    private static final Set<String> REPORTED_VANILLA_FAILURES = ConcurrentHashMap.newKeySet();

    private LootTableSimulator() {
    }

    private static PredictionContext legacyContext(long worldSeed, int dimension) {
        return new PredictionContext(worldSeed, "26.3", dimension);
    }

    private static PredictionContext legacyCurrentContext(int dimension) {
        return new PredictionContext(0L, "26.3", dimension);
    }

    public static List<ItemLoot> simulate(LootTableDef table, long seed, int maxRolls) {
        if (table.pools().isEmpty()) return List.of();
        LootRandom random = new LootRandom(seed);
        List<ItemLoot> results = new ArrayList<>();
        simulateOnRandom(table, random, maxRolls, new HashMap<>(), results, new int[]{0});
        return List.copyOf(results);
    }

    /**
     * Executes the running Minecraft version's own loot-table codec and
     * executor. A null result means the exact path is unavailable; an empty
     * list is a valid empty loot roll.
     */
    public static List<ItemLoot> simulateVanilla(String tableId, long seed) {
        return simulateVanilla(legacyCurrentContext(0), tableId, seed, null);
    }

    public static List<ItemLoot> simulateVanilla(PredictionContext context, String tableId, long seed) {
        return simulateVanilla(context, tableId, seed, null);
    }

    /** Executes a table with the real predicted origin. Location predicates in
     * 26.3 camp tables depend on this position and its biome. */
    public static List<ItemLoot> simulateVanilla(String tableId, long seed,
                                                  long worldSeed, int dimension,
                                                  int blockX, int blockY, int blockZ) {
        PredictionContext context = legacyContext(worldSeed, dimension);
        return simulateVanilla(context, tableId, seed,
            new LootLocation(worldSeed, dimension, blockX, blockY, blockZ));
    }

    public static List<ItemLoot> simulateVanilla(PredictionContext context, String tableId, long seed,
                                                  int blockX, int blockY, int blockZ) {
        return simulateVanilla(context, tableId, seed,
            new LootLocation(context.worldSeed(), context.dimension(), blockX, blockY, blockZ));
    }

    private static List<ItemLoot> simulateVanilla(PredictionContext context, String tableId, long seed,
                                                   LootLocation location) {
        if (tableId == null || !tableId.startsWith("minecraft:")) return null;
        try {
            BundledLootTableLoader.VersionProfile profile = versionProfile(context);
            JsonElement json = readLootTableJson(tableId, profile);
            if (json == null) return null;

            // A headless verifier has no fully constructed ServerLevel chunk source,
            // but worldgen still knows the exact biome for a predicted seed/origin.
            // Resolve only deterministic biome location conditions here, then leave
            // all pool selection, RNG, functions, and item construction to vanilla.
            if (location != null) {
                String biomeId = WorldgenEngine.getBiome(location.worldSeed(),
                    location.dimension(), location.x(), location.y(), location.z()).id();
                json = resolveBiomeLocationConditions(json, biomeId);
                if (json == null) return List.of();
                json = resolveExplorationMaps(json, location, new HashMap<>());
            }

            // LootTable references normally resolve through the server's reloadable
            // loot-table registry. The headless verifier intentionally has no full
            // server reload, so inline those referenced tables before decoding. The
            // decoded NestedLootTable still executes through vanilla and therefore
            // preserves the shared RNG stream, conditions, functions, and enchantments.
            json = expandNestedLootTables(
                json, tableId, new HashSet<>(Set.of(tableId)), profile);

            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE,
                WorldgenEngine.offlineRegistryAccess());
            LootTable table = LootTable.DIRECT_CODEC.parse(ops, json)
                .getOrThrow(error -> new IllegalStateException("loot table parse failed: " + error));

            int dimension = location == null ? 0 : location.dimension();
            long worldSeed = location == null ? 0L : location.worldSeed();
            int blockX = location == null ? 0 : location.x();
            int blockY = location == null ? 64 : location.y();
            int blockZ = location == null ? 0 : location.z();
            ServerLevel level = liveLevel(dimension, location);
            if (level == null) {
                Object fake = location == null
                    ? VanillaLootStructureSimulator.fakeLevelForProbe()
                    : VanillaLootStructureSimulator.fakeLevelForSeed(worldSeed);
                if (!(fake instanceof ServerLevel fallback)) return null;
                level = fallback;
            }
            LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN,
                    new Vec3(blockX + 0.5, blockY + 0.5, blockZ + 0.5))
                .create(LootContextParamSets.CHEST);

            List<ItemLoot> results = new ArrayList<>();
            for (ItemStack stack : table.getRandomItems(params, seed)) {
                if (stack == null || stack.isEmpty()) continue;
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
                ItemLoot item = ItemLoot.of(itemId, stack.getCount(), stack.getCount(), 0);
                String summary = enchantmentSummary(stack);
                if (summary != null && !summary.isBlank()) {
                    item = item.withPredictedEnchantments(summary);
                }
                results.add(item);
            }
            return List.copyOf(mergeExactStacks(results));
        } catch (Throwable failure) {
            reportVanillaFailure(tableId, failure);
            VanillaLootStructureSimulator.probe("[real-loot] failed table=" + tableId
                + " seed=" + seed + " error=" + failure);
            return null;
        }
    }

    private static JsonElement resolveBiomeLocationConditions(JsonElement element,
                                                               String biomeId) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonArray()) {
            JsonArray resolved = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                JsonElement value = resolveBiomeLocationConditions(child, biomeId);
                if (value != null) resolved.add(value);
            }
            return resolved;
        }
        if (!element.isJsonObject()) return element;

        JsonObject object = element.getAsJsonObject().deepCopy();
        if (!resolveAttachedCondition(object, "condition", biomeId)) return null;
        if (!resolveAttachedCondition(object, "conditions", biomeId)) return null;

        List<String> keys = new ArrayList<>();
        for (var entry : object.entrySet()) keys.add(entry.getKey());
        for (String key : keys) {
            JsonElement resolved = resolveBiomeLocationConditions(object.get(key), biomeId);
            if (resolved == null) object.remove(key);
            else object.add(key, resolved);
        }
        return object;
    }

    /**
     * Exploration-map functions need a fully running ServerLevel only to locate
     * the destination and attach saved-map data. Predict the destination with the
     * same structure placements, then remove those non-random mutations. The
     * exposed item id/count stream remains vanilla-exact.
     */
    private static JsonElement resolveExplorationMaps(JsonElement element, LootLocation location,
                                                       Map<String, Boolean> availability) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonArray()) {
            JsonArray resolved = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                resolved.add(resolveExplorationMaps(child, location, availability));
            }
            return resolved;
        }
        if (!element.isJsonObject()) return element;

        JsonObject object = element.getAsJsonObject().deepCopy();
        boolean unavailable = resolveExplorationModifierList(
            object, "modifier", location, availability);
        unavailable |= resolveExplorationModifierList(
            object, "functions", location, availability);
        if (unavailable && isItemEntry(object)) {
            JsonObject discard = new JsonObject();
            discard.addProperty("type", "minecraft:discard");
            JsonArray discardList = new JsonArray();
            discardList.add(discard);
            object.add("modifier", discardList);
            object.remove("functions");
        }

        List<String> keys = new ArrayList<>();
        for (var entry : object.entrySet()) keys.add(entry.getKey());
        for (String key : keys) {
            object.add(key, resolveExplorationMaps(object.get(key), location, availability));
        }
        return object;
    }

    /** Returns true when an exploration map cannot find a destination. */
    private static boolean resolveExplorationModifierList(JsonObject owner, String key,
                                                           LootLocation location,
                                                           Map<String, Boolean> availability) {
        JsonElement modifiers = owner.get(key);
        if (modifiers == null) return false;
        JsonArray source = modifiers.isJsonArray() ? modifiers.getAsJsonArray() : null;
        if (source == null) return false;

        JsonArray retained = new JsonArray();
        boolean foundExploration = false;
        boolean destinationAvailable = true;
        for (JsonElement modifier : source) {
            String type = objectType(modifier);
            if ("minecraft:exploration_map".equals(type)) {
                foundExploration = true;
                JsonObject function = modifier.getAsJsonObject();
                String destination = function.has("destination")
                    ? function.get("destination").getAsString() : "";
                int radius = function.has("search_radius")
                    ? function.get("search_radius").getAsInt() : 50;
                String cacheKey = destination + '|' + radius;
                destinationAvailable &= availability.computeIfAbsent(cacheKey,
                    ignored -> hasMapDestination(location, destination, radius));
                continue;
            }
            // This filter only validates that exploration_map attached map data.
            // Its success is already represented by destinationAvailable.
            if (foundExploration && "minecraft:filtered".equals(type)) continue;
            retained.add(modifier);
        }
        if (!foundExploration) return false;
        if (retained.isEmpty()) owner.remove(key);
        else owner.add(key, retained);
        return !destinationAvailable;
    }

    private static boolean isItemEntry(JsonObject object) {
        return "minecraft:item".equals(objectType(object));
    }

    private static String objectType(JsonElement element) {
        if (element == null || !element.isJsonObject()) return "";
        JsonElement type = element.getAsJsonObject().get("type");
        return type != null && type.isJsonPrimitive() ? type.getAsString() : "";
    }

    private static boolean hasMapDestination(LootLocation location, String destination,
                                              int searchRadius) {
        if (destination == null || destination.isBlank()) return false;
        Registry<Structure> structures = WorldgenEngine.offlineRegistryAccess()
            .lookupOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> targets;
        if (destination.startsWith("#")) {
            TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE,
                Identifier.parse(destination.substring(1)));
            targets = structures.get(tag).orElse(null);
        } else {
            ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE,
                Identifier.parse(destination));
            Holder.Reference<Structure> holder = structures.get(key).orElse(null);
            targets = holder == null ? null : HolderSet.direct(holder);
        }
        if (targets == null || targets.size() == 0) return false;

        var state = WorldgenEngine.structureState(location.worldSeed(), location.dimension());
        if (state == null) return false;
        int centerChunkX = Math.floorDiv(location.x(), 16);
        int centerChunkZ = Math.floorDiv(location.z(), 16);
        for (Holder<Structure> target : targets) {
            ResourceKey<Structure> key = target.unwrapKey().orElse(null);
            if (key == null) continue;
            for (StructurePlacement placement : state.getPlacementsForStructure(target)) {
                if (placement instanceof RandomSpreadStructurePlacement randomSpread
                    && hasRandomSpreadDestination(location, key, randomSpread,
                        state, centerChunkX, centerChunkZ, searchRadius)) {
                    return true;
                }
                if (placement instanceof ConcentricRingsStructurePlacement rings) {
                    List<ChunkPos> positions = state.getRingPositionsFor(rings);
                    if (positions != null && positions.stream().anyMatch(chunk ->
                        WorldgenEngine.generateStructureStart(location.worldSeed(),
                            location.dimension(), key, chunk).isValid())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasRandomSpreadDestination(
        LootLocation location, ResourceKey<Structure> key,
        RandomSpreadStructurePlacement placement,
        ChunkGeneratorStructureState state,
        int centerChunkX, int centerChunkZ, int searchRadius) {
        int spacing = placement.spacing();
        for (int radius = 0; radius <= searchRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                boolean xEdge = dx == -radius || dx == radius;
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!xEdge && dz != -radius && dz != radius) continue;
                    ChunkPos candidate = placement.getPotentialStructureChunk(
                        location.worldSeed(), centerChunkX + spacing * dx,
                        centerChunkZ + spacing * dz);
                    if (!placement.isStructureChunk(state, candidate.x(), candidate.z())) {
                        continue;
                    }
                    if (WorldgenEngine.generateStructureStart(location.worldSeed(),
                        location.dimension(), key, candidate).isValid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Returns false when the owning loot node must be excluded. */
    private static boolean resolveAttachedCondition(JsonObject owner, String key,
                                                    String biomeId) {
        JsonElement conditions = owner.get(key);
        if (conditions == null) return true;
        if (conditions.isJsonArray()) {
            JsonArray unresolved = new JsonArray();
            for (JsonElement condition : conditions.getAsJsonArray()) {
                Boolean result = evaluateBiomeCondition(condition, biomeId);
                if (Boolean.FALSE.equals(result)) return false;
                if (result == null) unresolved.add(condition);
            }
            if (unresolved.size() == 0) owner.remove(key);
            else owner.add(key, unresolved);
            return true;
        }
        Boolean result = evaluateBiomeCondition(conditions, biomeId);
        if (Boolean.FALSE.equals(result)) return false;
        if (Boolean.TRUE.equals(result)) owner.remove(key);
        return true;
    }

    /** Tri-state evaluation: null leaves unsupported conditions for vanilla. */
    private static Boolean evaluateBiomeCondition(JsonElement element, String biomeId) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject condition = element.getAsJsonObject();
        String type = condition.has("type") && condition.get("type").isJsonPrimitive()
            ? condition.get("type").getAsString() : "";
        if (type.equals("minecraft:inverted")) {
            Boolean result = evaluateBiomeCondition(condition.get("term"), biomeId);
            return result == null ? null : !result;
        }
        if (type.equals("minecraft:all_of") || type.equals("minecraft:any_of")) {
            JsonElement termsElement = condition.get("terms");
            if (termsElement == null || !termsElement.isJsonArray()) return null;
            boolean anyUnknown = false;
            for (JsonElement term : termsElement.getAsJsonArray()) {
                Boolean result = evaluateBiomeCondition(term, biomeId);
                if (result == null) anyUnknown = true;
                else if (type.equals("minecraft:all_of") && !result) return false;
                else if (type.equals("minecraft:any_of") && result) return true;
            }
            if (anyUnknown) return null;
            return type.equals("minecraft:all_of");
        }
        if (!type.equals("minecraft:location_check")) return null;
        JsonElement predicateElement = condition.get("predicate");
        if (predicateElement == null || !predicateElement.isJsonObject()) return null;
        JsonElement biomes = predicateElement.getAsJsonObject().get("biomes");
        if (biomes == null) return null;
        if (biomes.isJsonPrimitive()) {
            String expected = biomes.getAsString();
            return expected.startsWith("#") ? null : expected.equals(biomeId);
        }
        if (!biomes.isJsonArray()) return null;
        for (JsonElement biome : biomes.getAsJsonArray()) {
            if (biome.isJsonPrimitive() && biome.getAsString().equals(biomeId)) return true;
        }
        return false;
    }

    private record LootLocation(long worldSeed, int dimension, int x, int y, int z) {
    }

    private static ServerLevel liveLevel(int dimension, LootLocation location) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            MinecraftServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
            if (server == null) return null;
            ResourceKey<Level> key = dimension < 0 ? Level.NETHER
                : dimension > 0 ? Level.END : Level.OVERWORLD;
            ServerLevel level = server.getLevel(key);
            if (level == null) return null;
            // Offline audits and cross-seed searches may run while an unrelated
            // integrated world is open. Never let that world's structures or
            // exploration-map search contaminate a requested seed's loot roll.
            return location == null || level.getSeed() == location.worldSeed()
                ? level : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonElement readLootTableJson(
        String tableId, BundledLootTableLoader.VersionProfile profile) {
        try (InputStream in = BundledLootTableLoader.openAuthoritativeLootTable(profile, tableId)) {
            if (in == null) {
                reportVanillaFailure(tableId, new IllegalStateException("resource unavailable"));
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in));
        } catch (Throwable failure) {
            reportVanillaFailure(tableId, failure);
            return null;
        }
    }

    private static JsonElement expandNestedLootTables(
        JsonElement element, String ownerTable, Set<String> activeTables,
        BundledLootTableLoader.VersionProfile profile) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                array.set(i, expandNestedLootTables(
                    array.get(i), ownerTable, activeTables, profile));
            }
            return array;
        }
        if (!element.isJsonObject()) return element;

        var object = element.getAsJsonObject();
        String type = object.has("type") && object.get("type").isJsonPrimitive()
            ? object.get("type").getAsString() : null;
        if ("minecraft:loot_table".equals(type)
            && object.has("value")
            && object.get("value").isJsonPrimitive()) {
            String reference = object.get("value").getAsString();
            if (!reference.contains(":")) reference = "minecraft:" + reference;
            if (!activeTables.contains(reference)) {
                JsonElement nested = readLootTableJson(reference, profile);
                if (nested != null) {
                    Set<String> nestedActive = new HashSet<>(activeTables);
                    nestedActive.add(reference);
                    object.add("value", expandNestedLootTables(
                        nested, reference, nestedActive, profile));
                }
            }
            return object;
        }

        // Walk all nested objects/arrays. Loot-table JSON stores entries and their
        // functions several levels down, so only looking at direct children misses
        // references in alternatives or function payloads.
        List<String> keys = new ArrayList<>();
        for (var entry : object.entrySet()) keys.add(entry.getKey());
        for (String key : keys) {
            object.add(key, expandNestedLootTables(
                object.get(key), ownerTable, activeTables, profile));
        }
        return object;
    }

    private static BundledLootTableLoader.VersionProfile versionProfile(PredictionContext context) {
        String version = context == null ? "" : context.minecraftVersion();
        if (version == null || version.isBlank()) {
            return BundledLootTableLoader.runtimeProfile();
        }
        return BundledLootTableLoader.VersionProfile.forVersion(version);
    }

    private static void reportVanillaFailure(String tableId, Throwable failure) {
        String key = tableId + ":" + failure.getClass().getName() + ":" + String.valueOf(failure.getMessage());
        if (!REPORTED_VANILLA_FAILURES.add(key)) return;
        String message = "Seed Explorer vanilla loot unavailable"
            + " table=\"" + tableId.replace("\"", "\\\"") + "\""
            + " error=\"" + String.valueOf(failure.getMessage()).replace("\"", "\\\"") + "\"";
        System.err.println(message);
        if (Boolean.getBoolean("seedexplorer.lootDebug")) failure.printStackTrace(System.err);
    }

    private static List<ItemLoot> mergeExactStacks(List<ItemLoot> stacks) {
        List<ItemLoot> merged = new ArrayList<>();
        for (ItemLoot item : stacks) {
            int existingIndex = -1;
            for (int i = 0; i < merged.size(); i++) {
                ItemLoot existing = merged.get(i);
                if (existing.itemId().equals(item.itemId())
                    && Objects.equals(existing.enchantmentSummary(), item.enchantmentSummary())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex < 0) {
                merged.add(item);
                continue;
            }
            ItemLoot existing = merged.get(existingIndex);
            int count = existing.minCount() + item.minCount();
            merged.set(existingIndex, new ItemLoot(existing.itemId(), count, count, 0,
                existing.displayName(), existing.randomEnchantment(), existing.enchantmentId(),
                existing.enchantmentLevel(), null, false, 0, 0,
                existing.enchantmentSummary(), false));
        }
        return merged;
    }

    private static void simulateOnRandom(LootTableDef table, LootRandom random, int maxRolls,
                                          Map<String, Boolean> resolving, List<ItemLoot> results,
                                          int[] totalRollsRef) {
        for (LootPool pool : table.pools()) {
            // Handle random_chance condition
            if (pool.chance() < 1.0f && random.nextFloat() >= pool.chance()) {
                continue;
            }

            int rolls = pool.minRolls() + (pool.maxRolls() > pool.minRolls()
                ? random.nextInt(pool.maxRolls() - pool.minRolls() + 1) : 0);

            for (int r = 0; r < rolls; r++) {
                if (totalRollsRef[0] >= maxRolls) break;
                ItemLoot selected = selectWeightedEntry(pool, random);
                totalRollsRef[0]++;
                if (selected == null) continue;

                if (selected.referenceTableId() != null) {
                    if (resolving.putIfAbsent(selected.referenceTableId(), Boolean.TRUE) == null) {
                        LootTableDef refTable = VanillaLootTables.get(selected.referenceTableId());
                        if (refTable != null) {
                            simulateOnRandom(refTable, random, maxRolls, resolving, results, totalRollsRef);
                        }
                        resolving.remove(selected.referenceTableId());
                    }
                    continue;
                }

                if (selected.itemId().equals("minecraft:air")) continue;

                int count = selected.minCount();
                if (selected.maxCount() > selected.minCount()) {
                    count += random.nextInt(selected.maxCount() - selected.minCount() + 1);
                }
                // Vanilla's SetItemDamageFunction.run() calls random.nextFloat() before the
                // enchantment step. Consuming it here keeps the RNG stream aligned even though
                // predicted output doesn't include durability values.
                if (selected.hasDamageRoll()) {
                    random.nextFloat();
                }
                if (selected.randomEnchantment()) {
                    EnchantmentRoll roll = selected.enchantmentId() != null
                        ? consumeSpecificRandomEnchantment(random, selected.enchantmentId())
                        : consumeRandomEnchantment(random, selected.itemId());
                    selected = new ItemLoot(selected.itemId(), selected.minCount(), selected.maxCount(),
                        selected.weight(), selected.displayName(), true,
                        roll.id(), roll.level(), selected.referenceTableId());
                }
                if (selected.levelsEnchantment()) {
                    selected = applyLevelsEnchantment(selected, random);
                }

                merge(results, selected, count);
            }
            if (totalRollsRef[0] >= maxRolls) break;
        }
    }

    private static void merge(List<ItemLoot> results, ItemLoot selected, int count) {
        for (int i = 0; i < results.size(); i++) {
            ItemLoot existing = results.get(i);
            if (!canMerge(existing, selected)) continue;
            int mergedCount = existing.minCount() + count;
            results.set(i, new ItemLoot(selected.itemId(), mergedCount, mergedCount,
                selected.weight(), selected.displayName(), selected.randomEnchantment(),
                selected.enchantmentId(), selected.enchantmentLevel(), null,
                false, 0, 0, selected.enchantmentSummary()));
            return;
        }
        results.add(new ItemLoot(selected.itemId(), count, count, selected.weight(),
            selected.displayName(), selected.randomEnchantment(),
            selected.enchantmentId(), selected.enchantmentLevel(), null,
            false, 0, 0, selected.enchantmentSummary()));
    }

    private static boolean canMerge(ItemLoot existing, ItemLoot selected) {
        return existing.itemId().equals(selected.itemId())
            && existing.randomEnchantment() == selected.randomEnchantment()
            && Objects.equals(existing.enchantmentId(), selected.enchantmentId())
            && existing.enchantmentLevel() == selected.enchantmentLevel()
            && Objects.equals(existing.enchantmentSummary(), selected.enchantmentSummary());
    }

    private static ItemLoot selectWeightedEntry(LootPool pool, LootRandom random) {
        // Vanilla LootPool.addRandomItem selects a sole expanded entry directly.
        // In particular, it does not consume nextInt(1) before entry functions run.
        if (pool.entries().size() == 1) {
            ItemLoot only = pool.entries().getFirst();
            return only.weight() > 0 ? only : null;
        }

        int totalWeight = pool.totalWeight();
        if (totalWeight <= 0) return null;
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (ItemLoot entry : pool.entries()) {
            cumulative += entry.weight();
            if (roll < cumulative) return entry;
        }
        return pool.entries().isEmpty() ? null : pool.entries().getLast();
    }



    private static EnchantmentRoll consumeRandomEnchantment(LootRandom random, String itemId) {
        // Vanilla's EnchantRandomlyFunction splits by item type:
        // - enchanted_book: uses the #minecraft:on_random_loot tag (the 40-entry list).
        // - non-book gear (swords, helmets, etc.): uses ALL enchantments in the registry.
        // Using the wrong pool size causes nextInt(N) to diverge from vanilla's stream.
        boolean isBook = "minecraft:enchanted_book".equals(itemId);
        if (isBook) {
            // Books use #minecraft:on_random_loot — same as the RANDOM_LOOT_ENCHANTMENTS list.
            var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.ENCHANTMENT);
            List<Holder<Enchantment>> options = RANDOM_LOOT_ENCHANTMENTS.stream()
                .map(id -> ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace(id)))
                .map(registry::getOrThrow)
                .map(h -> (Holder<Enchantment>) h)
                .toList();
            if (options.isEmpty()) return new EnchantmentRoll(null, 0);
            int idx = random.nextInt(options.size());
            Holder<Enchantment> selected = options.get(idx);
            int min = selected.value().getMinLevel();
            int max = selected.value().getMaxLevel();
            int level = min;
            if (max > min) level += random.nextInt(max - min + 1);
            String id = Identifier.withDefaultNamespace(RANDOM_LOOT_ENCHANTMENTS.get(idx)).toString();
            return new EnchantmentRoll(id, level);
        }
        // Non-book gear: full enchantment registry.
        // offlineRegistryAccess() supports listElements(); vanillaLookup() throws UnsupportedOperationException.
        List<Holder<Enchantment>> options;
        try {
            options = WorldgenEngine.offlineRegistryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .listElements()
                .map(h -> (Holder<Enchantment>) h)
                .toList();
        } catch (Throwable ignored) {
            var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.ENCHANTMENT);
            options = RANDOM_LOOT_ENCHANTMENTS.stream()
                .map(id -> ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace(id)))
                .map(registry::getOrThrow)
                .map(h -> (Holder<Enchantment>) h)
                .toList();
        }
        if (options.isEmpty()) return new EnchantmentRoll(null, 0);
        int idx = random.nextInt(options.size());
        Holder<Enchantment> selected = options.get(idx);
        int min = selected.value().getMinLevel();
        int max = selected.value().getMaxLevel();
        int level = min;
        if (max > min) level += random.nextInt(max - min + 1);
        String id = selected.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        return new EnchantmentRoll(id, level);
    }

    private static EnchantmentRoll consumeSpecificRandomEnchantment(LootRandom random, String enchantmentId) {
        var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.ENCHANTMENT);
        String[] ids = enchantmentId.split(",");
        String chosen = ids.length == 1 ? ids[0] : ids[random.nextInt(ids.length)];
        Identifier identifier = Identifier.parse(chosen.trim());
        Holder<Enchantment> selected = registry.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, identifier));
        int min = selected.value().getMinLevel();
        int max = selected.value().getMaxLevel();
        int level = min;
        if (max > min) level += random.nextInt(max - min + 1);
        return new EnchantmentRoll(identifier.toString(), level);
    }

    private static ItemLoot applyLevelsEnchantment(ItemLoot selected, LootRandom random) {
        int min = Math.max(0, selected.enchantMinLevel());
        int max = Math.max(min, selected.enchantMaxLevel());
        int level = min + (max > min ? random.nextInt(max - min + 1) : 0);

        try {
            ItemStack stack = new ItemStack(selected.getItem());
            RegistryAccess registryAccess = WorldgenEngine.offlineRegistryAccess();
            Optional<HolderSet<Enchantment>> options;
            try {
                options = Optional.of(registryAccess.lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(EnchantmentTags.ON_RANDOM_LOOT));
            } catch (Throwable ignored) {
                options = Optional.empty();
            }

            ItemStack enchanted = EnchantmentHelper.enchantItem(random.raw(), stack, level, registryAccess, options);
            String summary = enchantmentSummary(enchanted);
            if (summary == null || summary.isBlank()) {
                summary = "Enchanted level " + level;
            }
            // Vanilla's EnchantWithLevelsFunction upgrades a book to an enchanted book.
            // Keeping the original item id makes stronghold library predictions report plain
            // books even though the stored enchantments were generated correctly.
            ItemLoot result = "minecraft:book".equals(selected.itemId())
                ? selected.withItemId("minecraft:enchanted_book")
                : selected;
            return result.withPredictedEnchantments(summary);
        } catch (Throwable ignored) {
            return selected.withPredictedEnchantments("Enchanted level " + level);
        }
    }

    private static String enchantmentSummary(ItemStack stack) {
        var enchantments = stack.getEnchantments();
        if (enchantments == null || enchantments.isEmpty()) {
            enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);
        }
        if (enchantments == null || enchantments.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        enchantments.entrySet().forEach(entry -> {
            String id = entry.getKey().unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("minecraft:unknown");
            parts.add(shortEnchantmentName(id) + " " + roman(entry.getIntValue()));
        });
        return String.join(", ", parts);
    }

    private static String shortEnchantmentName(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] words = path.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }
}
