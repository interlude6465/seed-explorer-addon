package me.seedexplorer.standalone.test;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.standalone.StandaloneChestLootVerification;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Opt-in current-version oracle. It asks the live 26.3 server for independent structure starts,
 * and separately materializes predictor-selected starts, so both missed and extra predictions are
 * observable. The default UI smoke remains fast because this test is property gated.
 */
@SuppressWarnings("UnstableApiUsage")
public final class StandalonePredictionOracleGameTest implements FabricClientGameTest {
    private static final String ENABLE_PROPERTY = "seedexplorer.runPredictionOracle";
    private static final String ORACLE_WINDOW_TITLE =
        "Seed Explorer - automated prediction test (closes when finished)";
    private static final int LOCATE_RADIUS = Integer.getInteger("seedexplorer.oracleLocateRadius", 384);
    private static final int WINDOW_RADIUS = Integer.getInteger("seedexplorer.oracleWindowRadius", 96);
    private static final int QUERY_ATTEMPTS = Integer.getInteger("seedexplorer.oracleQueryAttempts", 2);
    private static final int PREDICTED_SAMPLES = Integer.getInteger("seedexplorer.oraclePredictedSamples", 2);
    private static final int MAX_STRUCTURE_CHUNKS = Integer.getInteger("seedexplorer.oracleMaxStructureChunks", 1024);
    private static final int DUNGEON_RADIUS = Integer.getInteger("seedexplorer.oracleDungeonRadius", 4);
    private static final int CASE_TIMEOUT_TICKS = Integer.getInteger("seedexplorer.oracleCaseTimeoutTicks", 2400);
    private static final boolean DEBUG_SCANS = Boolean.getBoolean("seedexplorer.oracleDebugScans");
    private static final boolean SKIP_LOOT = Boolean.getBoolean("seedexplorer.oracleSkipLoot");

    @Override
    public void runTest(ClientGameTestContext test) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        setOracleWindowTitle(test);
        OracleReport report = new OracleReport();
        for (long seed : seeds()) {
            try (TestSingleplayerContext world = test.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(settings -> configure(settings, seed))
                .create()) {
                world.getConnection().waitForChunksRender();
                setOracleWindowTitle(test);
                report.seed(seed);
                auditSeed(test, world, seed, report);
            } catch (Throwable failure) {
                report.failure("seed=" + seed + " oracle crashed: " + failure);
            }
        }
        Path output = reportPath();
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(output, report.render(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError("Could not write prediction oracle report", failure);
        }
        if (!report.failures.isEmpty()) {
            throw new AssertionError("26.3 prediction oracle found " + report.failures.size()
                + " failures; see " + output.toAbsolutePath());
        }
    }

    private static void setOracleWindowTitle(ClientGameTestContext test) {
        test.runOnClient(client -> {
            client.getWindow().setTitle(ORACLE_WINDOW_TITLE);
            // The long-running audit supplies no human input. AFK throttling
            // slows Fabric's per-frame test/server rendezvous after ten minutes.
            client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            client.options.pauseOnLostFocus = false;
        });
    }

    private static void configure(WorldCreationUiState settings, long seed) {
        var presets = settings.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET);
        settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
            presets.getOrThrow(WorldPresets.NORMAL)));
        settings.setGenerateStructures(true);
        settings.setSeed(Long.toString(seed));
    }

    private static void auditSeed(ClientGameTestContext test, TestSingleplayerContext world,
                                  long seed, OracleReport report) {
        RuntimeWorld runtime = computeServerResponsive(test, world, server ->
            new RuntimeWorld(server.overworld().getSeed(), server.getServerVersion()));
        report.runtime(seed, runtime.actualSeed, runtime.serverVersion);
        if (runtime.actualSeed != seed) {
            report.failure("runtime seed mismatch requested=" + seed + " actual=" + runtime.actualSeed);
            report.checkpoint();
            return;
        }
        int[] requestedDimensions = dimensions();
        for (int dimensionIndex = 0; dimensionIndex < requestedDimensions.length; dimensionIndex++) {
            int dimension = requestedDimensions[dimensionIndex];
            DimensionPlan plan = computeServerResponsive(test, world, server ->
                dimensionPlan(server, dimension));
            if (!plan.available) {
                report.unverified("seed=" + seed + " dimension=" + dimension + " (level unavailable)");
                continue;
            }
            report.dimension(dimension);
            report.inventory(plan.inventoryIds);
            if (dimensionIndex == 0) {
                for (String unsupported : plan.unsupportedIds) {
                    report.unverified("seed=" + seed + " registryId=" + unsupported
                        + " (unsupported by predictor)");
                }
            }

            Map<String, Coverage> coverage = new LinkedHashMap<>();
            if (!System.getProperty("seedexplorer.oracleStarts", "").isBlank() && plan.supportedIds.size() != 1) {
                throw new IllegalArgumentException("explicit start fixtures require exactly one registry structure ID");
            }
            for (String id : plan.supportedIds) {
                report.observation("RUNNING seed=" + seed + " dimension=" + dimension + " id=" + id);
                report.checkpoint();
                Coverage stats = auditStructureResponsive(test, world, seed, dimension, id, report);
                coverage.put(id, stats);
                setOracleWindowTitle(test);
                test.waitTicks(2);
            }
            report.coverage(seed, coverage);
            if (typeFilter().isEmpty()) {
                for (StructureType type : StructureType.values()) {
                    if (type.dimension == dimension && type.hasMapPrediction()
                        && type != StructureType.DUNGEON && !plan.registryTypes.contains(type)) {
                        report.unverified("seed=" + seed + " type=" + type
                            + " (not a structure-registry entry; requires a configured-feature oracle)");
                    }
                }
            }
            if (dimension == 0 && plan.auditDungeons) {
                auditDungeonsResponsive(test, world,
                    new PredictionContext(seed, "26.3", dimension), report);
                test.waitTicks(2);
            }
        }
    }

    private static DimensionPlan dimensionPlan(MinecraftServer server, int dimension) {
        ServerLevel level = levelFor(server, dimension);
        if (level == null) return new DimensionPlan(false, List.of(), List.of(), List.of(), Set.of(), false);
        Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<StructureKey> registryEntries = structures.entrySet().stream()
            .map(entry -> new StructureKey(entry.getKey(), entry.getValue(),
                VanillaStructurePredictor.typeForStructureId(entry.getKey().identifier().toString())))
            .sorted(Comparator.comparing(entry -> entry.key.identifier().toString()))
            .toList();
        Set<String> typeFilter = typeFilter();
        Set<String> omitTypeFilter = omitTypeFilter();
        List<StructureKey> supported = registryEntries.stream()
            .filter(entry -> entry.type != null && entry.type.dimension == dimension)
            .filter(entry -> matchesTypeFilter(typeFilter, entry.key.identifier().toString()))
            .filter(entry -> omitTypeFilter.isEmpty()
                || !matchesTypeFilter(omitTypeFilter, entry.key.identifier().toString()))
            .toList();
        Set<StructureType> registryTypes = new HashSet<>();
        supported.forEach(entry -> registryTypes.add(entry.type));
        List<String> unsupported = registryEntries.stream().filter(entry -> entry.type == null)
            .map(entry -> entry.key.identifier().toString()).toList();
        boolean dungeons = dimension == 0 && matchesTypeFilter(typeFilter, "minecraft:monster_room")
            && (omitTypeFilter.isEmpty()
                || !matchesTypeFilter(omitTypeFilter, "minecraft:monster_room"));
        List<String> inventory = registryEntries.stream().map(entry ->
            entry.key.identifier() + " type=" + (entry.type == null ? "unsupported" : entry.type.name()))
            .toList();
        return new DimensionPlan(true, inventory, supported.stream()
            .map(entry -> entry.key.identifier().toString()).toList(), unsupported,
            Set.copyOf(registryTypes), dungeons);
    }

    private static Coverage auditStructureResponsive(ClientGameTestContext test,
                                                       TestSingleplayerContext world,
                                                       long seed, int dimension, String id,
                                                       OracleReport report) {
        PredictionContext context = new PredictionContext(seed, "26.3", dimension);
        ResourceKey<Structure> structureKey = ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id));
        Map<BlockPos, RawContainer> containerCache = new LinkedHashMap<>();
        Coverage stats = new Coverage();
        Set<StartKey> audited = new LinkedHashSet<>();

        ChunkPos fixture = explicitStart(seed);
        if (fixture != null) {
            ActualStart actual = computeServerResponsive(test, world, server -> {
                ServerLevel level = requiredLevel(server, dimension);
                return startAt(level, level.registryAccess().lookupOrThrow(Registries.STRUCTURE),
                    structureKey, fixture.x(), fixture.z());
            });
            if (actual == null) {
                report.failure("requested fixture has no actual structure seed=" + seed
                    + " id=" + id + " start=" + fixture);
            } else {
                stats.actual++;
                auditInitialLayout(test, context, actual, stats, report);
                if (SKIP_LOOT) auditActualStructureOnly(test, context, actual, stats, report);
                else auditActualToPredictionResponsive(test, world, context, actual, stats, report, containerCache);
            }
            report.progress(seed, id, stats);
            report.checkpoint();
            return stats;
        }

        for (int attempt = 0; attempt < QUERY_ATTEMPTS; attempt++) {
            BlockPos origin = queryOrigin(seed, id, attempt);
            BlockPos located = computeServerResponsive(test, world, server -> {
                ServerLevel level = requiredLevel(server, dimension);
                Registry<Structure> structures = level.registryAccess()
                    .lookupOrThrow(Registries.STRUCTURE);
                Holder.Reference<Structure> holder = structures.get(structureKey)
                    .orElseThrow(() -> new IllegalStateException("structure unavailable: " + id));
                return level.findNearestMapStructure(
                    HolderSet.direct(holder), origin, LOCATE_RADIUS, false);
            });
            if (located == null) continue;
            ActualStart actual = findStartResponsive(test, world, dimension, structureKey,
                new ChunkPos(Math.floorDiv(located.getX(), 16), Math.floorDiv(located.getZ(), 16)), 4);
            if (actual == null || !audited.add(actual.identity())) continue;
            stats.actual++;
            auditInitialLayout(test, context, actual, stats, report);
            if (SKIP_LOOT) auditActualStructureOnly(test, context, actual, stats, report);
            else auditActualToPredictionResponsive(test, world, context, actual, stats, report,
                containerCache);
        }

        for (int attempt = 0; attempt < QUERY_ATTEMPTS; attempt++) {
            BlockPos origin = queryOrigin(seed, id, attempt);
            int centerX = Math.floorDiv(origin.getX(), 16);
            int centerZ = Math.floorDiv(origin.getZ(), 16);
            List<GeneratedStructure> predicted = computeOffThreadResponsive(test,
                () -> VanillaStructurePredictor.predictStructure(context, id,
                    centerX - WINDOW_RADIUS, centerZ - WINDOW_RADIUS,
                    centerX + WINDOW_RADIUS, centerZ + WINDOW_RADIUS).stream()
                    .sorted(Comparator.comparingLong(value -> distanceSquared(value, centerX, centerZ)))
                    .limit(PREDICTED_SAMPLES)
                    .toList());
            for (GeneratedStructure candidate : predicted) {
                StartKey key = new StartKey(id, candidate.startChunkX, candidate.startChunkZ);
                if (!audited.add(key)) continue;
                stats.predicted++;
                ActualStart actual = computeServerResponsive(test, world, server -> {
                    ServerLevel level = requiredLevel(server, dimension);
                    Registry<Structure> structures = level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE);
                    return startAt(level, structures, structureKey,
                        candidate.startChunkX, candidate.startChunkZ);
                });
                if (actual == null) {
                    stats.extra++;
                    report.failure("extra structure seed=" + seed + " id=" + id
                        + " chunk=" + candidate.startChunkX + "," + candidate.startChunkZ);
                } else {
                    stats.actual++;
                    auditInitialLayout(test, context, actual, stats, report);
                    if (!SKIP_LOOT) auditLootResponsive(test, world, context, actual, stats, report, containerCache);
                }
            }
        }
        report.progress(seed, id, stats);
        report.checkpoint();
        if (stats.actual == 0 && stats.predicted == 0) {
            report.unverified("seed=" + seed + " id=" + id
                + " (no actual or predicted sample located)");
        }
        return stats;
    }

    private static ChunkPos explicitStart(long seed) {
        String configured = System.getProperty("seedexplorer.oracleStarts", "");
        if (configured.isBlank()) return null;
        for (String entry : configured.split(",")) {
            String[] fields = entry.trim().split(":");
            if (fields.length != 3) throw new IllegalArgumentException("expected seed:chunkX:chunkZ fixture");
            if (Long.parseLong(fields[0]) == seed) {
                return new ChunkPos(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]));
            }
        }
        throw new IllegalArgumentException("no explicit start fixture for seed " + seed);
    }

    private static void auditActualStructureOnly(ClientGameTestContext test,
                                                  PredictionContext context, ActualStart actual,
                                                  Coverage stats, OracleReport report) {
        List<GeneratedStructure> predicted = computeOffThreadResponsive(test,
            () -> VanillaStructurePredictor.predictStructure(context,
                actual.key.identifier().toString(),
                actual.chunk.x(), actual.chunk.z(), actual.chunk.x(), actual.chunk.z()));
        boolean matched = predicted.stream().anyMatch(candidate -> candidate.startChunkX == actual.chunk.x()
            && candidate.startChunkZ == actual.chunk.z() && variantMatches(
                actual.key.identifier().toString(), candidate));
        if (!matched) {
            stats.missed++;
            report.failure("missed structure seed=" + context.worldSeed() + " id="
                + actual.key.identifier() + " chunk=" + actual.chunk.x() + "," + actual.chunk.z());
        } else {
            stats.predicted++;
        }
    }

    private static ServerLevel requiredLevel(MinecraftServer server, int dimension) {
        ServerLevel level = levelFor(server, dimension);
        if (level == null) throw new IllegalStateException("level unavailable: " + dimension);
        return level;
    }

    private static void auditInitialLayout(ClientGameTestContext test, PredictionContext context,
                                           ActualStart actual, Coverage stats, OracleReport report) {
        if (actual.initialPieces == null) {
            stats.layoutUnverified++;
            report.unverified("initial layout unavailable (chunk already decorated) seed="
                + context.worldSeed() + " structure=" + actual.key.identifier() + " start=" + actual.chunk);
            return;
        }
        List<net.minecraft.nbt.CompoundTag> predicted;
        try {
            predicted = computeOffThreadResponsive(test, () -> {
            StructureStart start = WorldgenEngine.generateSelectedStructureStart(
                context.worldSeed(), context.dimension(), actual.key, actual.chunk);
            // The generated pieces retain datagen Holder owners. The copied,
            // tag-bound offline registries have different owners, so use the
            // original lookup for encoding references by their resource keys.
            net.minecraft.core.RegistryAccess encodingAccess = (net.minecraft.core.RegistryAccess)
                java.lang.reflect.Proxy.newProxyInstance(net.minecraft.core.RegistryAccess.class.getClassLoader(),
                    new Class<?>[] { net.minecraft.core.RegistryAccess.class }, (proxy, method, args) -> {
                        if (method.getName().equals("createSerializationContext")) {
                            return WorldgenEngine.vanillaLookup().createSerializationContext(
                                (com.mojang.serialization.DynamicOps<?>) args[0]);
                        }
                        return method.invoke(WorldgenEngine.offlineRegistryAccess(), args);
                    });
            var offlineSerialization = new net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext(
                actual.serialization.resourceManager(), encodingAccess,
                WorldgenEngine.structureTemplateManager());
            return start.getPieces().stream().map(piece -> piece.createTag(offlineSerialization)).toList();
            });
        } catch (Throwable failure) {
            stats.layoutUnverified++;
            report.failure("layout verifier failed seed=" + context.worldSeed() + " structure="
                + actual.key.identifier() + " start=" + actual.chunk + " error=" + failure);
            return;
        }
        if (predicted.equals(actual.initialPieces)) {
            stats.layoutMatches++;
            report.observation("initial layout MATCH seed=" + context.worldSeed()
                + " structure=" + actual.key.identifier() + " start=" + actual.chunk
                + " pieces=" + predicted.size());
        } else {
            stats.layoutMismatches++;
            report.failure("initial piece layout/configuration mismatch seed=" + context.worldSeed()
                + " structure=" + actual.key.identifier() + " start=" + actual.chunk
                + " actual=" + actual.initialPieces + " predicted=" + predicted);
        }
    }

    private static ServerLevel levelFor(MinecraftServer server, int dimension) {
        ResourceKey<net.minecraft.world.level.Level> key = dimension < 0
            ? net.minecraft.world.level.Level.NETHER
            : dimension > 0 ? net.minecraft.world.level.Level.END : net.minecraft.world.level.Level.OVERWORLD;
        return server.getLevel(key);
    }

    private static <T> T computeServerResponsive(ClientGameTestContext test,
                                                  TestSingleplayerContext world,
                                                  Function<MinecraftServer, T> operation) {
        MinecraftServer server = world.getServer().computeOnServer(value -> value);
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(operation.apply(server));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        test.waitFor(ignored -> future.isDone(), CASE_TIMEOUT_TICKS);
        return future.join();
    }

    private static <T> T computeOffThreadResponsive(ClientGameTestContext test,
                                                     Supplier<T> operation) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(operation);
        test.waitFor(ignored -> future.isDone(), CASE_TIMEOUT_TICKS);
        return future.join();
    }

    private static void auditActualToPredictionResponsive(ClientGameTestContext test,
                                                           TestSingleplayerContext world,
                                                           PredictionContext context,
                                                           ActualStart actual, Coverage stats,
                                                           OracleReport report,
                                                           Map<BlockPos, RawContainer> containerCache) {
        String id = actual.key.identifier().toString();
        List<GeneratedStructure> predicted = computeOffThreadResponsive(test,
            () -> VanillaStructurePredictor.predictStructure(context, id,
                actual.chunk.x(), actual.chunk.z(), actual.chunk.x(), actual.chunk.z()));
        boolean matched = predicted.stream().anyMatch(candidate -> candidate.startChunkX == actual.chunk.x()
            && candidate.startChunkZ == actual.chunk.z()
            && variantMatches(id, candidate));
        if (!matched) {
            stats.missed++;
            report.failure("missed structure seed=" + context.worldSeed() + " id=" + id
                + " chunk=" + actual.chunk.x() + "," + actual.chunk.z());
            return;
        }
        stats.predicted++;
        auditLootResponsive(test, world, context, actual, stats, report, containerCache);
    }

    private static void auditLootResponsive(ClientGameTestContext test,
                                            TestSingleplayerContext world,
                                            PredictionContext context, ActualStart actual,
                                            Coverage stats, OracleReport report,
                                            Map<BlockPos, RawContainer> containerCache) {
        if ("minecraft:trial_chambers".equals(actual.key.identifier().toString())) return;
        if (!stats.lootStarts.add(actual.identity())) return;
        List<ChestLootOutput> predicted;
        StandaloneStructureLootPredictor.DetailedPrediction detailed;
        try {
            detailed = computeOffThreadResponsive(test,
                () -> StandaloneStructureLootPredictor.predictDetailed(context, actual.key,
                    actual.chunk.x(), actual.chunk.z(), 64));
            predicted = detailed.inventories();
        } catch (Throwable failure) {
            report.failure("loot simulator failed seed=" + context.worldSeed() + " id="
                + actual.key.identifier() + " chunk=" + actual.chunk + " error=" + failure);
            return;
        }
        List<ActualInventory> actualInventories = scanInventoriesResponsive(test, world,
            context.dimension(), actual, report, containerCache);
        if (actualInventories == null) return;
        stats.actualChests += actualInventories.size();
        stats.predictedChests += predicted.size();
        // Preserve ground truth even when the predictor has no matching position.
        // Successful-match-only records lose the loot seeds needed to diagnose a
        // later height/history correction against the same independently generated world.
        for (ActualInventory inventory : actualInventories) {
            report.observation("container ACTUAL seed=" + context.worldSeed()
                + " structure=" + actual.key.identifier() + " start=" + actual.chunk
                + " positions=" + inventory.positions + " tables=" + inventory.tables
                + " lootSeeds=" + inventory.seeds + " stacks=" + stackSizeSignatures(inventory.stacks)
                + " unrepresented=" + unrepresentedComponents(inventory.stacks));
        }

        if ("minecraft:shipwreck_beached".equals(actual.key.identifier().toString())
            || "minecraft:desert_pyramid".equals(actual.key.identifier().toString())) {
            auditObservedScatteredRefinement(test, world, context, actual, predicted,
                actualInventories, stats, report);
        }

        Set<Integer> matchedActual = new HashSet<>();
        PieceOrderComparison pieceOrder = null;
        for (ChestLootOutput chest : predicted) {
            int index = findInventory(actualInventories, chest);
            if (index < 0) {
                stats.extraChests++;
                report.failure("extra chest seed=" + context.worldSeed() + " structure="
                    + actual.key.identifier() + " predicted=" + chest.blockX() + ','
                    + chest.blockY() + ',' + chest.blockZ() + " table=" + chest.lootTableId()
                    + " groundTruth=" + describePositionResponsive(test, world,
                        context.dimension(), actual,
                        new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ())));
                continue;
            }
            matchedActual.add(index);
            ActualInventory inventory = actualInventories.get(index);
            String mismatch = lootMismatch(chest, inventory);
            if (mismatch == null) {
                mismatch = computeOffThreadResponsive(test, () -> stackSizeMismatch(
                    context, detailed.containers(), inventory, containerCache));
            }
            if (mismatch != null) {
                stats.lootMismatches++;
                String pieceContext = "";
                if (isVillage(actual)) {
                    if (pieceOrder == null) {
                        pieceOrder = comparePieceOrderResponsive(test, world, context, actual);
                        report.observation("village piece order seed=" + context.worldSeed()
                            + " structure=" + actual.key.identifier() + " start=" + actual.chunk
                            + " live=" + pieceOrder.liveOrder()
                            + " fresh=" + pieceOrder.freshOrder());
                    }
                    pieceContext = " pieceContext=" + pieceOrder.at(inventory.positions);
                }
                report.failure("loot mismatch seed=" + context.worldSeed() + " structure="
                    + actual.key.identifier() + " chest=" + inventory.positions + " " + mismatch
                    + pieceContext);
            } else if (metadataUnverified(chest, inventory)) {
                for (ItemStack stack : inventory.stacks) {
                    if ("minecraft:buried_treasure_map".equals(itemId(stack))) {
                        report.observation("map target MATCH seed=" + context.worldSeed()
                            + " structure=" + actual.key.identifier() + " chest=" + inventory.positions
                            + " decorations=" + LootTableSimulator.mapDecorationsComponent(stack)
                            + " (saved map ID/preview remains unverified)");
                    }
                }
                stats.metadataUnverified++;
                report.unverified("loot metadata not representable seed=" + context.worldSeed()
                    + " structure=" + actual.key.identifier() + " chest=" + inventory.positions
                    + " components=" + unrepresentedComponents(inventory.stacks));
            } else {
                stats.modeledLootMatches++;
                report.observation("container MATCH seed=" + context.worldSeed()
                    + " structure=" + actual.key.identifier() + " positions=" + inventory.positions
                    + " tables=" + inventory.tables + " lootSeeds=" + inventory.seeds
                    + " stacks=" + stackSizeSignatures(inventory.stacks));
                if (chest.exact()) {
                    stats.exactLootMatches++;
                } else {
                    report.unverified("modeled loot matched but exact flag is false seed="
                        + context.worldSeed() + " structure=" + actual.key.identifier()
                        + " chest=" + inventory.positions);
                }
            }
        }
        for (int index = 0; index < actualInventories.size(); index++) {
            if (matchedActual.contains(index)) continue;
            stats.missedChests++;
            ActualInventory inventory = actualInventories.get(index);
            report.failure("missed chest seed=" + context.worldSeed() + " structure="
                + actual.key.identifier() + " actual=" + inventory.positions
                + " tables=" + inventory.tables);
        }
    }

    private static boolean isVillage(ActualStart actual) {
        return actual.key.identifier().toString().startsWith("minecraft:village_");
    }

    private static String stackSizeMismatch(PredictionContext context,
            List<VanillaLootStructureSimulator.SimulatedContainer> predicted,
            ActualInventory actual, Map<BlockPos, RawContainer> captured) {
        List<ItemStack> filled = new ArrayList<>();
        for (BlockPos position : actual.positions) {
            var container = predicted.stream().filter(value -> value.x() == position.getX()
                && value.y() == position.getY() && value.z() == position.getZ()).findFirst();
            if (container.isEmpty()) return "missing physical container in grouped inventory at " + position;
            var predictedMember = container.get();
            RawContainer liveMember = captured.get(position);
            if (liveMember == null) return "missing independent container capture at " + position;
            if (!predictedMember.lootTableId().equals(liveMember.table)
                || predictedMember.lootSeed() != liveMember.seed) {
                return "physical container table/seed mismatch at " + position;
            }
            // Only inventory capacity comes from the independent capture. Table,
            // seed and coordinates above all come from the predictor.
            List<ItemStack> stacks = LootTableSimulator.simulateContainerStacks(context,
                predictedMember.lootTableId(), predictedMember.lootSeed(),
                predictedMember.x(), predictedMember.y(), predictedMember.z(), liveMember.slots);
            if (stacks == null) return "inventory-fill simulation failed at " + position;
            filled.addAll(stacks);
        }
        List<String> expected = stackSizeSignatures(filled);
        List<String> observed = stackSizeSignatures(actual.stacks);
        return expected.equals(observed) ? null : "stack sizes/properties predicted=" + expected + " actual=" + observed;
    }

    private static List<String> stackSizeSignatures(List<ItemStack> stacks) {
        return stacks.stream().filter(stack -> !stack.isEmpty())
            .map(stack -> itemId(stack) + "x" + stack.getCount()
                + " enchantments=" + new java.util.TreeMap<>(enchantments(stack))
                + " damage=" + LootTableSimulator.damageComponent(stack)
                + " potion=" + LootTableSimulator.potionContentsComponent(stack)
                + " stew=" + LootTableSimulator.suspiciousStewEffectsComponent(stack)
                + ("minecraft:buried_treasure_map".equals(itemId(stack))
                    ? " mapTarget=" + LootTableSimulator.mapDecorationsComponent(stack) : ""))
            .sorted().toList();
    }

    private static void auditObservedScatteredRefinement(ClientGameTestContext test,
            TestSingleplayerContext world, PredictionContext context, ActualStart actual,
            List<ChestLootOutput> canonical, List<ActualInventory> actualInventories,
            Coverage stats, OracleReport report) {
        String family = actual.key.identifier().getPath();
        List<VanillaLootStructureSimulator.ObservedContainerPosition> observed =
            actualInventories.stream().flatMap(inventory -> inventory.positions.stream())
                .distinct().map(position ->
                    new VanillaLootStructureSimulator.ObservedContainerPosition(
                        position.getX(), position.getY(), position.getZ()))
                .toList();
        List<VanillaLootStructureSimulator.PlacementHistoryCandidate> candidates =
            computeOffThreadResponsive(test, () ->
                VanillaLootStructureSimulator.simulateScatteredPlacementCandidates(
                    context, actual.key, actual.chunk.x(), actual.chunk.z()));
        var selected = VanillaLootStructureSimulator.selectUniqueContainerCandidate(
            candidates, observed);
        long matchingHistories = candidates.stream().filter(candidate ->
            selectedCandidateMismatch(candidate.containers(), actualInventories) == null).count();
        report.observation(family + " full-history matches seed=" + context.worldSeed()
            + " start=" + actual.chunk + " matching=" + matchingHistories + " candidates=" + candidates.size());
        if (matchingHistories == 0 && !actualInventories.isEmpty()) {
            report.unverified(family + " live output outside enumerated histories seed=" + context.worldSeed()
                + " start=" + actual.chunk);
        }

        GeneratedStructure structure = computeOffThreadResponsive(test, () ->
            VanillaStructurePredictor.predictStructure(context,
                actual.key.identifier().toString(), actual.chunk.x(), actual.chunk.z(),
                actual.chunk.x(), actual.chunk.z()).stream()
                .filter(candidate -> candidate.startChunkX == actual.chunk.x()
                    && candidate.startChunkZ == actual.chunk.z())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "missing " + family + " prediction at " + actual.chunk)));
        List<LiveChunkSnapshot> snapshots = computeServerResponsive(test, world, server ->
            captureActualSnapshots(requiredLevel(server, context.dimension()), canonical));
        List<ChestLootOutput> refined = computeOffThreadResponsive(test, () ->
            invokeObservedRefinement(context, structure, canonical, snapshots));

        String prefix = "seed=" + context.worldSeed() + " start=" + actual.chunk
            + " candidates=" + candidates.size() + " observed=" + observed.size();
        if (selected.isPresent()) {
            stats.observedUnique++;
            String selectedMismatch = selectedCandidateMismatch(selected.get(), actualInventories);
            String refinedMismatch = refinedOutputMismatch(refined, actualInventories, true);
            report.observation(family + " observed refinement UNIQUE " + prefix
                + " selectedContainers=" + selected.get().size()
                + " refinedContainers=" + refined.size());
            if (selectedMismatch != null) {
                report.failure(family + " observed candidate mismatch " + prefix + ' '
                    + selectedMismatch);
            }
            if (refinedMismatch != null) {
                report.failure(family + " production refinement mismatch " + prefix + ' '
                    + refinedMismatch);
            }
        } else {
            stats.observedAmbiguous++;
            String refinedMismatch = refinedOutputMismatch(refined, actualInventories, false);
            report.observation(family + " observed refinement AMBIGUOUS " + prefix
                + " refinedContainers=" + refined.size());
            if (refinedMismatch != null) {
                report.failure(family + " ambiguous refinement guessed loot " + prefix + ' '
                    + refinedMismatch);
            }
        }
    }

    private static List<LiveChunkSnapshot> captureActualSnapshots(ServerLevel level,
            List<ChestLootOutput> canonical) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (ChestLootOutput chest : canonical) {
            chunks.add(new ChunkPos(Math.floorDiv(chest.blockX(), 16),
                Math.floorDiv(chest.blockZ(), 16)));
        }
        List<LiveChunkSnapshot> snapshots = new ArrayList<>();
        for (ChunkPos position : chunks) {
            LevelChunk chunk = level.getChunk(position.x(), position.z());
            LiveChunkSnapshot.CaptureBuilder builder = LiveChunkSnapshot.captureBuilder(
                0, position.x(), position.z(), chunk.getMinY(), chunk.getMaxY());
            LevelChunkSection[] sections = chunk.getSections();
            int minSectionY = chunk.getMinSectionY();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) continue;
                int baseY = (minSectionY + sectionIndex) << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            var state = section.getBlockState(x, y, z);
                            if (!state.isAir()) builder.addBlock(x, baseY + y, z, state);
                        }
                    }
                }
            }
            LiveChunkSnapshot snapshot = builder.build();
            if (!snapshot.usableForTerrainOverride()) {
                throw new IllegalStateException("actual snapshot was incomplete: " + position);
            }
            snapshots.add(snapshot);
        }
        return List.copyOf(snapshots);
    }

    @SuppressWarnings("unchecked")
    private static List<ChestLootOutput> invokeObservedRefinement(PredictionContext context,
            GeneratedStructure structure, List<ChestLootOutput> canonical,
            List<LiveChunkSnapshot> snapshots) {
        try {
            Class<?> type = Class.forName(
                "me.seedexplorer.standalone.StandaloneObservedLootRefinement");
            Method method = type.getDeclaredMethod("refine", PredictionContext.class,
                GeneratedStructure.class, List.class, List.class);
            method.setAccessible(true);
            return List.copyOf((List<ChestLootOutput>) method.invoke(
                null, context, structure, canonical, snapshots));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("production observed refinement invocation failed", failure);
        }
    }

    private static String selectedCandidateMismatch(
            List<VanillaLootStructureSimulator.SimulatedContainer> selected,
            List<ActualInventory> actualInventories) {
        for (var container : selected) {
            ChestLootOutput output = new ChestLootOutput(container.x(), container.y(), container.z(),
                container.lootTableId(), List.of(), container.lootSeed());
            int index = findInventory(actualInventories, output);
            if (index < 0) return "extra=" + container;
            ActualInventory actual = actualInventories.get(index);
            if (!actual.tables.contains(container.lootTableId())
                || !actual.seeds.contains(container.lootSeed())) {
                return "container=" + container + " actualTables=" + actual.tables
                    + " actualSeeds=" + actual.seeds;
            }
        }
        if (selected.size() != actualInventories.size()) {
            return "count selected=" + selected.size() + " actual=" + actualInventories.size();
        }
        return null;
    }

    private static String refinedOutputMismatch(List<ChestLootOutput> refined,
            List<ActualInventory> actualInventories, boolean requireExactLoot) {
        Set<Integer> matched = new HashSet<>();
        for (ChestLootOutput chest : refined) {
            int index = findInventory(actualInventories, chest);
            if (index < 0) return "extra=" + chest;
            matched.add(index);
            if (requireExactLoot) {
                ActualInventory actual = actualInventories.get(index);
                if (!actual.tables.contains(chest.lootTableId())
                    || !actual.seeds.contains(chest.seed())) {
                    return "seeded=" + chest + " actualTables=" + actual.tables
                        + " actualSeeds=" + actual.seeds;
                }
            } else if (chest.exact() || chest.lootTableId() != null
                && !chest.lootTableId().isBlank() || chest.seed() != 0L
                || chest.predictedItems() != null && !chest.predictedItems().isEmpty()) {
                return "non-position-only=" + chest;
            }
        }
        return matched.size() == actualInventories.size()
            ? null : "count refined=" + refined.size() + " actual=" + actualInventories.size();
    }

    private static PieceOrderComparison comparePieceOrderResponsive(ClientGameTestContext test,
                                                                     TestSingleplayerContext world,
                                                                     PredictionContext context,
                                                                     ActualStart actual) {
        List<PieceSnapshot> live = computeServerResponsive(test, world, server ->
            pieceSnapshots(actual.start));
        List<PieceSnapshot> fresh = computeOffThreadResponsive(test, () -> {
            StructureStart start = WorldgenEngine.generateSelectedStructureStart(
                context.worldSeed(), context.dimension(), actual.key, actual.chunk);
            return pieceSnapshots(start);
        });
        return new PieceOrderComparison(live, fresh);
    }

    private static List<PieceSnapshot> pieceSnapshots(StructureStart start) {
        List<PieceSnapshot> result = new ArrayList<>();
        List<StructurePiece> pieces = start.getPieces();
        for (int index = 0; index < pieces.size(); index++) {
            StructurePiece piece = pieces.get(index);
            BoundingBox box = piece.getBoundingBox();
            result.add(new PieceSnapshot(index, pieceSignature(piece), box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ()));
        }
        return List.copyOf(result);
    }

    private static String describePositionResponsive(ClientGameTestContext test,
                                                       TestSingleplayerContext world,
                                                       int dimension, ActualStart actual,
                                                       BlockPos position) {
        return computeServerResponsive(test, world, server ->
            describePosition(requiredLevel(server, dimension), actual, position));
    }

    private static String describePosition(ServerLevel level, ActualStart actual, BlockPos position) {
        BlockEntity entity = level.getBlockEntity(position);
        String table = "none";
        if (entity instanceof RandomizableContainer container && container.getLootTable() != null) {
            table = container.getLootTable().identifier().toString();
        }
        List<String> containerEntities = new ArrayList<>();
        for (Entity candidate : level.getAllEntities()) {
            if (!(candidate instanceof ContainerEntity container)
                || !BlockPos.containing(candidate.position()).equals(position)) continue;
            String entityTable = container.getContainerLootTable() == null ? "none"
                : container.getContainerLootTable().identifier().toString();
            containerEntities.add(candidate.getClass().getSimpleName() + ':' + entityTable);
        }
        BoundingBox cached = actual.start.getBoundingBox();
        BoundingBox pieces = pieceBounds(actual.start);
        List<String> owningPieces = actual.start.getPieces().stream()
            .filter(piece -> piece.getBoundingBox().isInside(position))
            .map(StandalonePredictionOracleGameTest::pieceSignature)
            .toList();
        List<String> startPieces = actual.start.getPieces().stream()
            .map(StandalonePredictionOracleGameTest::pieceSignature)
            .toList();
        return "{state=" + level.getBlockState(position)
            + ",blockEntity=" + (entity == null ? "none" : entity.getClass().getSimpleName())
            + ",table=" + table + ",containerEntities=" + containerEntities
            + ",insideCached=" + cached.isInside(position)
            + ",insidePieceFootprint=" + insideFootprint(pieces, position)
            + ",owningPieces=" + owningPieces + ",startPieces=" + startPieces
            + ",startChunk=" + actual.chunk
            + ",cachedBox=" + cached + ",pieceBox=" + pieces + '}';
    }

    private static String pieceSignature(net.minecraft.world.level.levelgen.structure.StructurePiece piece) {
        return piece instanceof PoolElementStructurePiece poolPiece
            ? "element=" + poolPiece.getElement() + ",position=" + poolPiece.getPosition()
                + ",rotation=" + poolPiece.getRotation() + ",box=" + poolPiece.getBoundingBox()
            : "class=" + piece.getClass().getName() + ",box=" + piece.getBoundingBox();
    }

    private static void auditDungeonsResponsive(ClientGameTestContext test,
                                                TestSingleplayerContext world,
                                                PredictionContext context,
                                                OracleReport report) {
        Set<ChunkPos> origins = new LinkedHashSet<>();
        Set<ChunkPos> generated = new LinkedHashSet<>();
        for (int attempt = 0; attempt < QUERY_ATTEMPTS; attempt++) {
            BlockPos center = queryOrigin(context.worldSeed(), "minecraft:monster_room", attempt);
            int centerX = Math.floorDiv(center.getX(), 16);
            int centerZ = Math.floorDiv(center.getZ(), 16);
            for (int dz = -DUNGEON_RADIUS - 1; dz <= DUNGEON_RADIUS + 1; dz++) {
                for (int dx = -DUNGEON_RADIUS - 1; dx <= DUNGEON_RADIUS + 1; dx++) {
                    ChunkPos chunk = new ChunkPos(centerX + dx, centerZ + dz);
                    generated.add(chunk);
                    if (Math.abs(dx) <= DUNGEON_RADIUS && Math.abs(dz) <= DUNGEON_RADIUS) {
                        origins.add(chunk);
                    }
                }
            }
        }

        // Fabric's client-game-test threads rendezvous through a Phaser every tick. A single
        // multi-minute server task prevents SDL from pumping messages and Windows reports an
        // AppHang. Generate and inspect one chunk per task so the client can render between
        // bounded units; keep the offline replay off the server thread entirely.
        for (ChunkPos chunkPos : generated) {
            computeServerResponsive(test, world, server -> {
                ServerLevel level = levelFor(server, context.dimension());
                if (level == null) throw new IllegalStateException("level unavailable: "
                    + context.dimension());
                level.getChunk(chunkPos.x(), chunkPos.z());
                return Boolean.TRUE;
            });
        }

        Map<BlockPos, RawContainer> containerCache = new LinkedHashMap<>();
        Map<BlockPos, RawContainer> dungeonChests = new LinkedHashMap<>();
        Map<BlockPos, String> actual = new LinkedHashMap<>();
        for (ChunkPos chunkPos : generated) {
            computeServerResponsive(test, world, server -> {
                ServerLevel level = levelFor(server, context.dimension());
                if (level == null) throw new IllegalStateException("level unavailable: "
                    + context.dimension());
                LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
                for (BlockEntity entity : materializedBlockEntities(chunk)) {
                    if (entity instanceof SpawnerBlockEntity
                        && isDungeonRoom(level, entity.getBlockPos())) {
                        ChunkPos origin = new ChunkPos(
                            Math.floorDiv(entity.getBlockPos().getX(), 16),
                            Math.floorDiv(entity.getBlockPos().getZ(), 16));
                        if (origins.contains(origin)) {
                            actual.put(entity.getBlockPos().immutable(), spawnerEntityId(level,
                                (SpawnerBlockEntity) entity));
                        }
                    }
                    if (entity instanceof RandomizableContainer container) {
                        RawContainer captured = capture(containerCache, entity.getBlockPos(), container);
                        if (captured != null
                            && captured.table.equals("minecraft:chests/simple_dungeon")) {
                            dungeonChests.put(captured.position, captured);
                        }
                    }
                }
                return Boolean.TRUE;
            });
        }

        List<ActualInventory> groupedDungeonChests = computeServerResponsive(test, world, server -> {
            ServerLevel level = levelFor(server, context.dimension());
            if (level == null) throw new IllegalStateException("level unavailable: "
                + context.dimension());
            return groupInventories(level, dungeonChests);
        });

        Map<BlockPos, VanillaLootStructureSimulator.SimulatedDungeon> predicted = new LinkedHashMap<>();
        Map<BlockPos, ChunkPos> predictedOrigins = new LinkedHashMap<>();
        for (ChunkPos chunk : origins) {
            List<VanillaLootStructureSimulator.SimulatedDungeon> simulated =
                computeOffThreadResponsive(test, () -> VanillaLootStructureSimulator.simulateDungeons(
                    context.worldSeed(), chunk.x(), chunk.z()));
            for (VanillaLootStructureSimulator.SimulatedDungeon dungeon : simulated) {
                BlockPos position = new BlockPos(dungeon.x(), dungeon.y(), dungeon.z());
                predicted.put(position, dungeon);
                predictedOrigins.put(position, chunk);
            }
        }

        if (SKIP_LOOT) {
            for (BlockPos position : actual.keySet()) {
                if (!predicted.containsKey(position)) {
                    report.failure("missed dungeon seed=" + context.worldSeed()
                        + " spawner=" + position);
                }
            }
            for (BlockPos position : predicted.keySet()) {
                if (!actual.containsKey(position)) {
                    report.failure("extra dungeon seed=" + context.worldSeed()
                        + " spawner=" + position);
                }
            }
            report.dungeonProgress(context.worldSeed(), actual.size(), predicted.size());
            report.checkpoint();
            return;
        }

        for (BlockPos position : actual.keySet()) {
            if (!predicted.containsKey(position)) {
                report.failure("missed dungeon seed=" + context.worldSeed() + " spawner=" + position);
            }
        }
        for (Map.Entry<BlockPos, VanillaLootStructureSimulator.SimulatedDungeon> entry : predicted.entrySet()) {
            if (!actual.containsKey(entry.getKey())) {
                report.failure("extra dungeon seed=" + context.worldSeed() + " spawner=" + entry.getKey());
                continue;
            }
            VanillaLootStructureSimulator.SimulatedDungeon dungeon = entry.getValue();
            String actualSpawner = actual.get(entry.getKey());
            if (actualSpawner == null || !actualSpawner.equals(dungeon.spawnerEntityId())) {
                report.failure("dungeon spawner mismatch seed=" + context.worldSeed()
                    + " spawner=" + entry.getKey() + " predicted=" + dungeon.spawnerEntityId()
                    + " actual=" + actualSpawner);
            }
            ChunkPos origin = predictedOrigins.get(entry.getKey());
            GeneratedStructure structure = new GeneratedStructure(dungeon.x(), dungeon.y(), dungeon.z(),
                origin.x(), origin.z(),
                StructureType.DUNGEON, dungeon.spawnerEntityId(), false);
            List<ChestLootOutput> predictedLoot = computeOffThreadResponsive(test,
                () -> StandaloneStructureLootPredictor.predict(context, structure, 64));
            List<ActualInventory> actualLoot = groupedDungeonChests.stream()
                .filter(inventory -> inventory.positions.stream().anyMatch(position ->
                    Math.abs(position.getX() - dungeon.x()) <= 8
                        && Math.abs(position.getY() - dungeon.y()) <= 1
                        && Math.abs(position.getZ() - dungeon.z()) <= 8))
                .toList();
            compareDungeonLoot(test, context, entry.getKey(), predictedLoot, actualLoot,
                dungeon.containers(), containerCache, report);
        }
        report.dungeonProgress(context.worldSeed(), actual.size(), predicted.size());
        if (actual.isEmpty() && predicted.isEmpty()) {
            report.unverified("seed=" + context.worldSeed()
                + " type=DUNGEON (no actual or predicted room in bounded chunks)");
        }
        report.checkpoint();
    }

    private static void compareDungeonLoot(ClientGameTestContext test, PredictionContext context, BlockPos spawner,
                                           List<ChestLootOutput> predicted,
                                           List<ActualInventory> actual,
                                           List<VanillaLootStructureSimulator.SimulatedContainer> containers,
                                           Map<BlockPos, RawContainer> captured, OracleReport report) {
        Set<Integer> matched = new HashSet<>();
        for (ChestLootOutput chest : predicted) {
            int index = findInventory(actual, chest);
            if (index < 0) {
                report.failure("extra dungeon chest seed=" + context.worldSeed()
                    + " spawner=" + spawner + " predicted=" + chest.blockX() + ','
                    + chest.blockY() + ',' + chest.blockZ());
                continue;
            }
            matched.add(index);
            String mismatch = lootMismatch(chest, actual.get(index));
            if (mismatch == null) {
                ActualInventory inventory = actual.get(index);
                mismatch = computeOffThreadResponsive(test,
                    () -> stackSizeMismatch(context, containers, inventory, captured));
            }
            if (mismatch != null) {
                report.failure("dungeon loot mismatch seed=" + context.worldSeed()
                    + " spawner=" + spawner + " " + mismatch);
            } else if (metadataUnverified(chest, actual.get(index))) {
                report.unverified("dungeon loot metadata not representable seed=" + context.worldSeed()
                    + " spawner=" + spawner + " positions=" + actual.get(index).positions
                    + " components=" + unrepresentedComponents(actual.get(index).stacks));
            } else {
                report.observation("dungeon container MATCH seed=" + context.worldSeed()
                    + " spawner=" + spawner + " positions=" + actual.get(index).positions
                    + " tables=" + actual.get(index).tables + " lootSeeds=" + actual.get(index).seeds
                    + " stacks=" + stackSizeSignatures(actual.get(index).stacks));
                report.unverified("dungeon loot matched but remains approximate seed="
                    + context.worldSeed() + " spawner=" + spawner);
            }
        }
        for (int index = 0; index < actual.size(); index++) {
            if (!matched.contains(index)) {
                report.failure("missed dungeon chest seed=" + context.worldSeed()
                    + " spawner=" + spawner + " actual=" + actual.get(index).positions);
            }
        }
    }

    private static boolean isDungeonRoom(ServerLevel level, BlockPos spawner) {
        int dungeonBlocks = 0;
        for (int dz = -4; dz <= 4; dz++) {
            for (int dx = -4; dx <= 4; dx++) {
                var floor = level.getBlockState(spawner.offset(dx, -1, dz));
                if (floor.is(Blocks.COBBLESTONE) || floor.is(Blocks.MOSSY_COBBLESTONE)) {
                    dungeonBlocks++;
                }
            }
        }
        return dungeonBlocks >= 16;
    }

    private static String spawnerEntityId(ServerLevel level, SpawnerBlockEntity spawner) {
        Entity display = spawner.getSpawner().getOrCreateDisplayEntity(level, spawner.getBlockPos());
        return display == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(display.getType()).toString();
    }

    private static List<ActualInventory> scanInventoriesResponsive(ClientGameTestContext test,
                                                                    TestSingleplayerContext world,
                                                                    int dimension,
                                                                    ActualStart actual,
                                                                    OracleReport report,
                                                                    Map<BlockPos, RawContainer> containerCache) {
        BoundingBox cachedBox = computeServerResponsive(test, world, server ->
            actual.start.getBoundingBox());
        List<ChunkPos> cachedChunks = cachedBox.intersectingChunks().toList();
        Set<ChunkPos> retainedChunks = new LinkedHashSet<>();
        try {
            if (!loadChunksResponsive(test, world, dimension, actual, cachedChunks,
                retainedChunks, report)) return null;

            // StructureStart memoizes its first bounding box, but several pieces move vertically during
            // post-processing. Recompute directly from the pieces after generation, and scan the whole
            // build-height inside that current X/Z footprint so buried rooms cannot fall outside it.
            BoundingBox box = computeServerResponsive(test, world, server -> pieceBounds(actual.start));
            List<ChunkPos> chunks = box.intersectingChunks().toList();
            if (!loadChunksResponsive(test, world, dimension, actual, chunks,
                retainedChunks, report)) return null;
            box = computeServerResponsive(test, world, server -> pieceBounds(actual.start));
            chunks = box.intersectingChunks().toList();
            if (!loadChunksResponsive(test, world, dimension, actual, chunks,
                retainedChunks, report)) return null;

            // Terrain generation does not make far-away persistent entity sections resident. Keep
            // these chunks loaded and explicitly drain their entity loads before yielding a tick.
            // Without both steps, a mineshaft's rails are visible while its chest minecarts are not.
            waitForEntitiesResponsive(test, world, dimension, chunks);
            test.waitTicks(1);

            Set<String> ownedTables = lootTablesFor(actual.key.identifier().toString());
            if (ownedTables.isEmpty()) return List.of();
            BoundingBox finalBox = box;
            List<ChunkPos> finalChunks = chunks;
            if (DEBUG_SCANS) {
                debugStructureScanResponsive(test, world, dimension, actual, cachedBox, finalBox,
                    finalChunks, ownedTables, report);
            }
            Map<BlockPos, RawContainer> raw = new LinkedHashMap<>();
            for (ChunkPos chunkPos : finalChunks) {
                List<RawContainer> captured = computeServerResponsive(test, world, server -> {
                    ServerLevel level = requiredLevel(server, dimension);
                    LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
                    List<RawContainer> found = new ArrayList<>();
                    for (BlockEntity entity : materializedBlockEntities(chunk)) {
                        if (entity instanceof net.minecraft.world.level.block.entity.BrushableBlockEntity brushable
                            && insideFootprint(finalBox, entity.getBlockPos())) {
                            RawContainer value = captureBrushable(level, containerCache, brushable);
                            if (value != null && ownedTables.contains(value.table)) found.add(value);
                            continue;
                        }
                        if (!(entity instanceof RandomizableContainer container)
                            || !insideFootprint(finalBox, entity.getBlockPos())) continue;
                        RawContainer value = capture(containerCache, entity.getBlockPos(), container);
                        if (value != null && ownedTables.contains(value.table)) found.add(value);
                    }
                    return List.copyOf(found);
                });
                for (RawContainer value : captured) {
                    raw.put(value.position, value);
                }
            }
            List<RawContainer> entityContainers = computeServerResponsive(test, world, server -> {
                ServerLevel level = requiredLevel(server, dimension);
                List<RawContainer> found = new ArrayList<>();
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof ContainerEntity container)) continue;
                    BlockPos position = BlockPos.containing(entity.position());
                    if (!insidePieceBounds(actual.start, position)) continue;
                    RawContainer captured = containerCache.get(position);
                    if (captured == null && container.getContainerLootTable() != null) {
                        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> table =
                            container.getContainerLootTable();
                        long lootSeed = container.getContainerLootTableSeed();
                        container.unpackChestVehicleLootTable(null);
                        captured = new RawContainer(position.immutable(), table.identifier().toString(),
                            lootSeed, stacks(container), true, container.getContainerSize());
                        containerCache.put(position.immutable(), captured);
                    }
                    if (captured != null && ownedTables.contains(captured.table)) found.add(captured);
                }
                return List.copyOf(found);
            });
            for (RawContainer captured : entityContainers) {
                raw.put(captured.position, captured);
            }
            return computeServerResponsive(test, world, server ->
                groupInventories(requiredLevel(server, dimension), raw));
        } finally {
            releaseChunksResponsive(test, world, dimension, retainedChunks);
        }
    }

    private static boolean loadChunksResponsive(ClientGameTestContext test,
                                                TestSingleplayerContext world, int dimension,
                                                ActualStart actual, List<ChunkPos> chunks,
                                                Set<ChunkPos> retainedChunks,
                                                OracleReport report) {
        if (chunks.size() > MAX_STRUCTURE_CHUNKS) {
            report.unverified("structure too large for loot scan id=" + actual.key.identifier()
                + " chunk=" + actual.chunk + " chunks=" + chunks.size());
            return false;
        }
        for (ChunkPos chunk : chunks) {
            boolean addTicket = retainedChunks.add(chunk);
            computeServerResponsive(test, world, server -> {
                ServerLevel level = requiredLevel(server, dimension);
                if (addTicket) {
                    level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, chunk, 0);
                }
                level.getChunk(chunk.x(), chunk.z());
                return Boolean.TRUE;
            });
        }
        return true;
    }

    private static void waitForEntitiesResponsive(ClientGameTestContext test,
                                                  TestSingleplayerContext world, int dimension,
                                                  List<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            computeServerResponsive(test, world, server -> {
                requiredLevel(server, dimension).waitForEntities(chunk, 0);
                return Boolean.TRUE;
            });
        }
    }

    private static void releaseChunksResponsive(ClientGameTestContext test,
                                                 TestSingleplayerContext world, int dimension,
                                                 Set<ChunkPos> chunks) {
        // Removing tickets does not synchronously generate or load chunks.
        // Batch this cheap cleanup instead of paying a client/server tick barrier
        // for every chunk of a large structure.
        computeServerResponsive(test, world, server -> {
            var source = requiredLevel(server, dimension).getChunkSource();
            for (ChunkPos chunk : chunks) source.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunk, 0);
            return Boolean.TRUE;
        });
    }

    private static BoundingBox pieceBounds(StructureStart start) {
        return BoundingBox.encapsulatingBoxes(start.getPieces().stream()
            .map(piece -> piece.getBoundingBox()).toList()).orElse(start.getBoundingBox());
    }

    private static boolean insideFootprint(BoundingBox box, BlockPos position) {
        return position.getX() >= box.minX() && position.getX() <= box.maxX()
            && position.getZ() >= box.minZ() && position.getZ() <= box.maxZ();
    }

    private static boolean insidePieceBounds(StructureStart start, BlockPos position) {
        return start.getPieces().stream()
            .anyMatch(piece -> piece.getBoundingBox().isInside(position));
    }

    private static void debugStructureScanResponsive(ClientGameTestContext test,
                                                      TestSingleplayerContext world,
                                                      int dimension, ActualStart actual,
                                                      BoundingBox cachedBox,
                                                      BoundingBox pieceBox,
                                                      List<ChunkPos> chunks,
                                                      Set<String> ownedTables,
                                                      OracleReport report) {
        for (ChunkPos chunk : chunks) {
            String observation = computeServerResponsive(test, world, server ->
                debugStructureScan(requiredLevel(server, dimension), actual, cachedBox,
                    pieceBox, chunk, ownedTables));
            report.observation(observation);
        }
    }

    private static String debugStructureScan(ServerLevel level, ActualStart actual,
                                             BoundingBox cachedBox, BoundingBox pieceBox,
                                             ChunkPos chunkPos, Set<String> ownedTables) {
        List<String> blockEntities = new ArrayList<>();
        List<BlockPos> containerBlocks = new ArrayList<>();
        LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
        for (BlockEntity entity : materializedBlockEntities(chunk)) {
            if (!insideFootprint(pieceBox, entity.getBlockPos())) continue;
            String table = "none";
            if (entity instanceof RandomizableContainer container && container.getLootTable() != null) {
                table = container.getLootTable().identifier().toString();
            }
            blockEntities.add(entity.getBlockPos() + ":" + entity.getClass().getSimpleName()
                + ":" + table + ":owned=" + ownedTables.contains(table));
        }
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        for (int z = minZ; z <= chunkPos.getMaxBlockZ(); z++) {
            for (int x = minX; x <= chunkPos.getMaxBlockX(); x++) {
                if (x < pieceBox.minX() || x > pieceBox.maxX()
                    || z < pieceBox.minZ() || z > pieceBox.maxZ()) continue;
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    var block = level.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
                        || block == Blocks.BARREL || block == Blocks.DISPENSER) {
                        containerBlocks.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return "scan id=" + actual.key.identifier() + " start=" + actual.chunk
            + " cachedBox=" + cachedBox + " pieceBox=" + pieceBox + " chunk=" + chunkPos
            + " containerBlocks=" + containerBlocks + " blockEntities=" + blockEntities;
    }

    private static List<BlockEntity> materializedBlockEntities(LevelChunk chunk) {
        for (BlockPos position : List.copyOf(chunk.getBlockEntitiesPos())) {
            chunk.getBlockEntity(position);
        }
        return List.copyOf(chunk.getBlockEntities().values());
    }

    private static Set<String> lootTablesFor(String structureId) {
        StructureType type = VanillaStructurePredictor.typeForStructureId(structureId);
        if (type == null) return Set.of();
        return switch (type) {
            case MINESHAFT -> Set.of("minecraft:chests/abandoned_mineshaft");
            case DESERT_PYRAMID -> Set.of("minecraft:chests/desert_pyramid", "minecraft:archaeology/desert_pyramid");
            case STRONGHOLD -> Set.of("minecraft:chests/stronghold_corridor",
                "minecraft:chests/stronghold_crossing", "minecraft:chests/stronghold_library",
                "minecraft:chests/stronghold_storeroom");
            case SHIPWRECK -> Set.of("minecraft:chests/shipwreck_treasure",
                "minecraft:chests/shipwreck_supply", "minecraft:chests/shipwreck_map");
            case OUTPOST -> Set.of("minecraft:chests/pillager_outpost");
            case JUNGLE_TEMPLE -> Set.of("minecraft:chests/jungle_temple",
                "minecraft:chests/jungle_temple_dispenser");
            case TREASURE -> Set.of("minecraft:chests/buried_treasure");
            case RUINED_PORTAL, NETHER_RUINED_PORTAL -> Set.of("minecraft:chests/ruined_portal");
            case OCEAN_RUIN -> Set.of("minecraft:chests/underwater_ruin_small",
                "minecraft:chests/underwater_ruin_big", "minecraft:archaeology/ocean_ruin_cold",
                "minecraft:archaeology/ocean_ruin_warm");
            case IGLOO -> Set.of("minecraft:chests/igloo_chest");
            case ANCIENT_CITY -> Set.of("minecraft:chests/ancient_city",
                "minecraft:chests/ancient_city_ice_box");
            case TRIAL_CHAMBER -> Set.of(
                "minecraft:chests/trial_chambers/corridor",
                "minecraft:chests/trial_chambers/entrance",
                "minecraft:chests/trial_chambers/intersection",
                "minecraft:chests/trial_chambers/intersection_barrel",
                "minecraft:chests/trial_chambers/reward",
                "minecraft:chests/trial_chambers/supply",
                "minecraft:dispensers/trial_chambers/chamber",
                "minecraft:dispensers/trial_chambers/corridor",
                "minecraft:dispensers/trial_chambers/water");
            case TRAIL_RUINS -> Set.of("minecraft:archaeology/trail_ruins_common",
                "minecraft:archaeology/trail_ruins_rare");
            case VILLAGE -> Set.of(
                "minecraft:chests/village/village_armorer",
                "minecraft:chests/village/village_butcher",
                "minecraft:chests/village/village_cartographer",
                "minecraft:chests/village/village_desert_house",
                "minecraft:chests/village/village_fisher",
                "minecraft:chests/village/village_fletcher",
                "minecraft:chests/village/village_mason",
                "minecraft:chests/village/village_plains_house",
                "minecraft:chests/village/village_savanna_house",
                "minecraft:chests/village/village_shepherd",
                "minecraft:chests/village/village_snowy_house",
                "minecraft:chests/village/village_taiga_house",
                "minecraft:chests/village/village_tannery",
                "minecraft:chests/village/village_temple",
                "minecraft:chests/village/village_toolsmith",
                "minecraft:chests/village/village_weaponsmith");
            case ABANDONED_CAMP -> Set.of("minecraft:barrels/abandoned_camp_barrel",
                "minecraft:chests/abandoned_camp_common_chest",
                "minecraft:chests/abandoned_camp_secret_chest");
            case MANSION -> Set.of("minecraft:chests/woodland_mansion");
            case END_CITY -> Set.of("minecraft:chests/end_city_treasure");
            case FORTRESS -> Set.of("minecraft:chests/nether_bridge");
            case BASTION -> Set.of("minecraft:chests/bastion_treasure",
                "minecraft:chests/bastion_other", "minecraft:chests/bastion_hoglin_stable",
                "minecraft:chests/bastion_bridge");
            default -> Set.of();
        };
    }

    private static RawContainer captureBrushable(ServerLevel level, Map<BlockPos, RawContainer> cache,
            net.minecraft.world.level.block.entity.BrushableBlockEntity brushable) {
        BlockPos position = brushable.getBlockPos().immutable();
        RawContainer existing = cache.get(position);
        if (existing != null) return existing;
        var tag = brushable.saveWithoutMetadata(level.registryAccess());
        String table = tag.getStringOr("LootTable", "");
        if (table.isBlank()) return null;
        long seed = tag.getLongOr("LootTableSeed", 0L);
        try {
            // Invoke the real block entity's loot path in the independent world;
            // do not substitute the predictor's parsed loot table as the oracle.
            Method unpack = brushable.getClass().getDeclaredMethod("unpackLootTable", ServerLevel.class,
                net.minecraft.world.entity.LivingEntity.class, net.minecraft.world.item.ItemInstance.class);
            unpack.setAccessible(true);
            var player = level.getServer().getPlayerList().getPlayers().getFirst();
            unpack.invoke(brushable, level, player, new ItemStack(net.minecraft.world.item.Items.BRUSH));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not unpack independent archaeology loot at " + position, failure);
        }
        ItemStack item = brushable.getItem();
        RawContainer result = new RawContainer(position, table, seed,
            item.isEmpty() ? List.of() : List.of(item.copy()), false, 1);
        cache.put(position, result);
        return result;
    }

    private static RawContainer capture(Map<BlockPos, RawContainer> cache, BlockPos position,
                                        RandomizableContainer container) {
        BlockPos key = position.immutable();
        RawContainer captured = cache.get(key);
        if (captured != null) return captured;
        if (container.getLootTable() == null) return null;
        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> table = container.getLootTable();
        long lootSeed = container.getLootTableSeed();
        container.unpackLootTable(null);
        captured = new RawContainer(key, table.identifier().toString(), lootSeed,
            stacks(container), false, container.getContainerSize());
        cache.put(key, captured);
        return captured;
    }

    private static List<ActualInventory> groupInventories(ServerLevel level,
                                                           Map<BlockPos, RawContainer> raw) {
        List<ActualInventory> result = new ArrayList<>();
        Set<BlockPos> consumed = new HashSet<>();
        for (RawContainer first : raw.values()) {
            if (!consumed.add(first.position)) continue;
            List<RawContainer> members = new ArrayList<>();
            members.add(first);
            if (!first.entity && level.getBlockState(first.position).getBlock() instanceof ChestBlock) {
                var state = level.getBlockState(first.position);
                if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos partner = first.position.relative(ChestBlock.getConnectedDirection(state));
                    RawContainer second = raw.get(partner);
                    if (second != null && consumed.add(partner)) members.add(second);
                }
            }
            List<BlockPos> positions = members.stream().map(value -> value.position).toList();
            List<String> tables = members.stream().map(value -> value.table).toList();
            List<Long> seeds = members.stream().map(value -> value.seed).toList();
            List<ItemStack> stacks = members.stream().flatMap(value -> value.stacks.stream()).toList();
            result.add(new ActualInventory(positions, tables, seeds, stacks));
        }
        return List.copyOf(result);
    }

    private static ActualStart findStartResponsive(ClientGameTestContext test,
                                                   TestSingleplayerContext world,
                                                   int dimension,
                                                   ResourceKey<Structure> expected,
                                                   ChunkPos center, int radius) {
        List<ChunkPos> referenced = computeServerResponsive(test, world, server -> {
            ServerLevel level = requiredLevel(server, dimension);
            Registry<Structure> structures = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE);
            Structure expectedValue = structures.get(expected)
                .orElseThrow(() -> new IllegalStateException(
                    "structure unavailable: " + expected.identifier())).value();
            var locatedChunk = level.getChunk(center.x(), center.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES);
            return locatedChunk.getReferencesForStructure(expectedValue).stream()
                .map(reference -> new ChunkPos(
                    ChunkPos.getX(reference), ChunkPos.getZ(reference)))
                .toList();
        });
        Set<ChunkPos> candidates = new LinkedHashSet<>(referenced);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                candidates.add(new ChunkPos(center.x() + dx, center.z() + dz));
            }
        }
        for (ChunkPos candidate : candidates) {
            ActualStart start = computeServerResponsive(test, world, server -> {
                ServerLevel level = requiredLevel(server, dimension);
                Registry<Structure> structures = level.registryAccess()
                    .lookupOrThrow(Registries.STRUCTURE);
                return startAt(level, structures, expected, candidate.x(), candidate.z());
            });
            if (start != null) return start;
        }
        return null;
    }

    private static ActualStart startAt(ServerLevel level, Registry<Structure> structures,
                                       ResourceKey<Structure> expected, int chunkX, int chunkZ) {
        // Capture before FEATURES mutates scattered pieces (height, chest flags,
        // etc.). Comparing a placed live start with a fresh offline start would
        // conflate generation configuration with decoration history.
        var initialChunk = level.getChunk(chunkX, chunkZ,
            net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
        boolean hasExpectedStart = initialChunk.getAllStarts().entrySet().stream()
            .anyMatch(entry -> expected.identifier().equals(structures.getKey(entry.getKey()))
                && entry.getValue() != null && entry.getValue().isValid());
        if (!hasExpectedStart) return null;
        var serialization = net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(level);
        List<net.minecraft.nbt.CompoundTag> initialPieces = null;
        if (initialChunk.getPersistedStatus().isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES)) {
            for (var entry : initialChunk.getAllStarts().entrySet()) {
                if (expected.identifier().equals(structures.getKey(entry.getKey())) && entry.getValue().isValid()) {
                    initialPieces = entry.getValue().getPieces().stream()
                        .map(piece -> piece.createTag(serialization).copy()).toList();
                    break;
                }
            }
        }
        String firstChunk = System.getProperty("seedexplorer.oracleFirstChunk", "");
        if (!firstChunk.isBlank()) {
            if (System.getProperty("seedexplorer.oracleStarts", "").isBlank()) {
                throw new IllegalArgumentException("first-chunk research requires explicit structure fixtures");
            }
            String[] coordinates = firstChunk.split(":");
            if (coordinates.length != 2) throw new IllegalArgumentException("expected first chunkX:chunkZ");
            // Request an independently generated approach to the same structure.
            // This changes chunk-generation history, never predicted loot seeds.
            level.getChunk(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) continue;
            Identifier id = structures.getKey(entry.getKey());
            if (id == null || !id.equals(expected.identifier())) continue;
            return new ActualStart(expected, start, start.getChunkPos(), initialPieces, serialization);
        }
        return null;
    }

    private static int findInventory(List<ActualInventory> inventories, ChestLootOutput prediction) {
        BlockPos position = new BlockPos(prediction.blockX(), prediction.blockY(), prediction.blockZ());
        for (int index = 0; index < inventories.size(); index++) {
            if (inventories.get(index).positions.contains(position)) return index;
        }
        return -1;
    }

    private static String lootMismatch(ChestLootOutput prediction, ActualInventory actual) {
        if (!actual.tables.contains(prediction.lootTableId())) {
            return "table predicted=" + prediction.lootTableId() + " actual=" + actual.tables;
        }
        if (!actual.seeds.contains(prediction.seed())) {
            return "lootSeed predicted=" + prediction.seed() + " actual=" + actual.seeds;
        }
        StandaloneChestLootVerification.Result result = contentVerification(prediction, actual);
        if (result.status() == StandaloneChestLootVerification.Status.MISMATCH) {
            return "contents predicted=" + result.predicted() + " actual=" + result.actual()
                + " stacks=" + stackSummary(actual.stacks);
        }
        return null;
    }

    private static boolean metadataUnverified(ChestLootOutput prediction, ActualInventory actual) {
        return contentVerification(prediction, actual).status()
            == StandaloneChestLootVerification.Status.UNVERIFIED;
    }

    private static List<String> unrepresentedComponents(List<ItemStack> stacks) {
        return stacks.stream().filter(LootTableSimulator::hasUnrepresentedLootComponents)
            .map(stack -> itemId(stack) + "x" + stack.getCount()
                + " patch=" + stack.getComponentsPatch())
            .sorted().toList();
    }

    private static StandaloneChestLootVerification.Result contentVerification(
        ChestLootOutput prediction, ActualInventory actual) {
        List<StandaloneChestLootVerification.ActualStack> stacks = actual.stacks.stream()
            .filter(stack -> !stack.isEmpty())
            .map(stack -> new StandaloneChestLootVerification.ActualStack(
                itemId(stack), stack.getCount(), enchantments(stack),
                LootTableSimulator.damageComponent(stack),
                LootTableSimulator.potionContentsComponent(stack),
                LootTableSimulator.hasUnrepresentedLootComponents(stack),
                LootTableSimulator.mapDecorationsComponent(stack),
                LootTableSimulator.suspiciousStewEffectsComponent(stack)))
            .toList();
        return StandaloneChestLootVerification.compareContents(List.of(prediction),
            itemCounts(actual.stacks), stacks);
    }

    private static Map<String, Integer> itemCounts(List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) counts.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> enchantments(ItemStack stack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        addEnchantments(result, stack.get(DataComponents.ENCHANTMENTS));
        addEnchantments(result, stack.get(DataComponents.STORED_ENCHANTMENTS));
        return result;
    }

    private static void addEnchantments(Map<String, Integer> result, ItemEnchantments enchantments) {
        if (enchantments == null) return;
        for (Holder<net.minecraft.world.item.enchantment.Enchantment> enchantment : enchantments.keySet()) {
            enchantment.unwrapKey().ifPresent(key ->
                result.put(key.identifier().toString(), enchantments.getLevel(enchantment)));
        }
    }

    private static List<ItemStack> stacks(Container container) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) result.add(stack.copy());
        }
        return List.copyOf(result);
    }

    private static String stackSummary(List<ItemStack> stacks) {
        return stacks.stream().map(stack -> itemId(stack) + 'x' + stack.getCount()
            + enchantments(stack)).toList().toString();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean variantMatches(String id, GeneratedStructure prediction) {
        return switch (prediction.type) {
            case VILLAGE, ABANDONED_CAMP, RUINED_PORTAL, SHIPWRECK, OCEAN_RUIN, MINESHAFT ->
                id.equals(prediction.variant);
            default -> true;
        };
    }

    private static long distanceSquared(GeneratedStructure structure, int chunkX, int chunkZ) {
        long dx = (long) structure.startChunkX - chunkX;
        long dz = (long) structure.startChunkZ - chunkZ;
        return dx * dx + dz * dz;
    }

    private static BlockPos queryOrigin(long seed, String id, int attempt) {
        long mixed = mix(seed ^ (long) id.hashCode() * 0x9E3779B97F4A7C15L ^ attempt * 0xD1B54A32D192ED03L);
        int x = (int) (mixed & 0x7FFFL) - 0x4000;
        int z = (int) ((mixed >>> 17) & 0x7FFFL) - 0x4000;
        return new BlockPos(x, 64, z);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static long[] seeds() {
        String configured = System.getProperty("seedexplorer.oracleSeeds", "1,123456789,-7046029254386353131");
        return java.util.Arrays.stream(configured.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).mapToLong(Long::parseLong).toArray();
    }

    private static int[] dimensions() {
        String configured = System.getProperty("seedexplorer.oracleDimensions", "0");
        return java.util.Arrays.stream(configured.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).mapToInt(Integer::parseInt).toArray();
    }

    private static Set<String> typeFilter() {
        return parseTypeFilter(System.getProperty("seedexplorer.oracleTypes", ""));
    }

    private static Set<String> omitTypeFilter() {
        return parseTypeFilter(System.getProperty("seedexplorer.oracleOmitTypes", ""));
    }

    private static Set<String> parseTypeFilter(String configured) {
        Set<String> result = new LinkedHashSet<>();
        for (String raw : configured.split(",")) {
            String value = raw.trim();
            if (value.isEmpty()) continue;
            result.add(value.contains(":") ? value : "minecraft:" + value);
        }
        return Set.copyOf(result);
    }

    private static boolean matchesTypeFilter(Set<String> filter, String id) {
        if (filter.isEmpty()) return true;
        return filter.stream().anyMatch(value -> id.equals(value) || id.startsWith(value + '_'));
    }

    private static Path reportPath() {
        return Path.of(System.getProperty("seedexplorer.oracleReport",
            "build/reports/seed-explorer-prediction-oracle-26.3.txt"));
    }

    private record StructureKey(ResourceKey<Structure> key, Structure value, StructureType type) {
    }

    private record DimensionPlan(boolean available, List<String> inventoryIds, List<String> supportedIds,
                                 List<String> unsupportedIds, Set<StructureType> registryTypes,
                                 boolean auditDungeons) {
    }

    private record RuntimeWorld(long actualSeed, String serverVersion) {
    }

    private record StartKey(String id, int chunkX, int chunkZ) {
    }

    private record ActualStart(ResourceKey<Structure> key, StructureStart start, ChunkPos chunk,
                               List<net.minecraft.nbt.CompoundTag> initialPieces,
                               net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext serialization) {
        StartKey identity() {
            return new StartKey(key.identifier().toString(), chunk.x(), chunk.z());
        }
    }

    private record RawContainer(BlockPos position, String table, long seed,
                                List<ItemStack> stacks, boolean entity, int slots) {
    }

    private record ActualInventory(List<BlockPos> positions, List<String> tables,
                                   List<Long> seeds, List<ItemStack> stacks) {
    }

    private record PieceSnapshot(int index, String signature,
                                 int minX, int minY, int minZ,
                                 int maxX, int maxY, int maxZ) {
        boolean owns(BlockPos position) {
            return position.getX() >= minX && position.getX() <= maxX
                && position.getY() >= minY && position.getY() <= maxY
                && position.getZ() >= minZ && position.getZ() <= maxZ;
        }

        boolean intersectsChunk(BlockPos position) {
            int chunkMinX = Math.floorDiv(position.getX(), 16) * 16;
            int chunkMinZ = Math.floorDiv(position.getZ(), 16) * 16;
            return maxX >= chunkMinX && minX <= chunkMinX + 15
                && maxZ >= chunkMinZ && minZ <= chunkMinZ + 15;
        }

        String indexedSignature() {
            return index + ":" + signature;
        }
    }

    private record PieceOrderComparison(List<PieceSnapshot> live, List<PieceSnapshot> fresh) {
        List<String> liveOrder() {
            return live.stream().map(PieceSnapshot::indexedSignature).toList();
        }

        List<String> freshOrder() {
            return fresh.stream().map(PieceSnapshot::indexedSignature).toList();
        }

        String at(List<BlockPos> positions) {
            Set<BlockPos> unique = new LinkedHashSet<>(positions);
            return unique.stream().map(position -> "{position=" + position
                + ",liveOwning=" + matching(live, position, true)
                + ",freshOwning=" + matching(fresh, position, true)
                + ",livePlacementChunk=" + matching(live, position, false)
                + ",freshPlacementChunk=" + matching(fresh, position, false) + '}')
                .toList().toString();
        }

        private static List<String> matching(List<PieceSnapshot> pieces, BlockPos position,
                                             boolean requireOwnership) {
            return pieces.stream()
                .filter(piece -> requireOwnership ? piece.owns(position) : piece.intersectsChunk(position))
                .map(PieceSnapshot::indexedSignature)
                .toList();
        }
    }

    private static final class Coverage {
        private int actual;
        private int predicted;
        private int missed;
        private int extra;
        private int actualChests;
        private int predictedChests;
        private int missedChests;
        private int extraChests;
        private int lootMismatches;
        private int modeledLootMatches;
        private int metadataUnverified;
        private int layoutMatches;
        private int layoutMismatches;
        private int layoutUnverified;
        private int exactLootMatches;
        private int observedUnique;
        private int observedAmbiguous;
        private final Set<StartKey> lootStarts = new HashSet<>();
    }

    private static final class OracleReport {
        private final StringBuilder text = new StringBuilder(4096)
            .append("Seed Explorer 26.3 prediction oracle\ncreated=").append(Instant.now()).append('\n')
            .append("requestedSeeds=").append(System.getProperty("seedexplorer.oracleSeeds", "1,123456789,-7046029254386353131"))
            .append(" requestedDimensions=").append(System.getProperty("seedexplorer.oracleDimensions", "0"))
            .append(" includeTypes=").append(System.getProperty("seedexplorer.oracleTypes", "<all>"))
            .append(" omitTypes=").append(System.getProperty("seedexplorer.oracleOmitTypes", "<none>"))
            .append(" explicitStarts=").append(System.getProperty("seedexplorer.oracleStarts", "<none>"))
            .append(" firstChunk=").append(System.getProperty("seedexplorer.oracleFirstChunk", "<default>"))
            .append('\n')
            .append("locateRadiusChunks=").append(LOCATE_RADIUS)
            .append(" windowRadiusChunks=").append(WINDOW_RADIUS)
            .append(" queryAttempts=").append(QUERY_ATTEMPTS)
            .append(" predictedSamples=").append(PREDICTED_SAMPLES)
            .append(" dungeonRadiusChunks=").append(DUNGEON_RADIUS)
            .append(" caseTimeoutTicks=").append(CASE_TIMEOUT_TICKS)
            .append(" skipLoot=").append(SKIP_LOOT).append("\n\n");
        private final List<String> failures = new ArrayList<>();
        private final List<String> unverified = new ArrayList<>();

        void seed(long seed) {
            text.append("SEED ").append(seed).append('\n');
        }

        void dimension(int dimension) {
            text.append("DIMENSION ").append(dimension).append('\n');
        }

        void inventory(List<String> entries) {
            text.append("STRUCTURE_REGISTRY count=").append(entries.size()).append('\n');
            entries.forEach(entry -> text.append("  INVENTORY ").append(entry).append('\n'));
        }

        void runtime(long requestedSeed, long actualSeed, String serverVersion) {
            text.append("RUNTIME requestedSeed=").append(requestedSeed)
                .append(" actualSeed=").append(actualSeed)
                .append(" serverVersion=").append(serverVersion)
                .append(" requestedPreset=minecraft:normal requestedStructures=true\n");
        }

        void coverage(long seed, Map<String, Coverage> coverage) {
            for (Map.Entry<String, Coverage> entry : coverage.entrySet()) {
                Coverage value = entry.getValue();
                text.append("  ").append(entry.getKey())
                    .append(" actual=").append(value.actual)
                    .append(" predicted=").append(value.predicted)
                    .append(" missed=").append(value.missed)
                    .append(" extra=").append(value.extra)
                    .append(" actualChests=").append(value.actualChests)
                    .append(" predictedChests=").append(value.predictedChests)
                    .append(" missedChests=").append(value.missedChests)
                    .append(" extraChests=").append(value.extraChests)
                    .append(" lootMismatch=").append(value.lootMismatches)
                    .append(" modeledLootMatch=").append(value.modeledLootMatches)
                    .append(" metadataUnverified=").append(value.metadataUnverified)
                    .append(" layoutMatch=").append(value.layoutMatches)
                    .append(" layoutMismatch=").append(value.layoutMismatches)
                    .append(" layoutUnverified=").append(value.layoutUnverified)
                    .append(" exactLootMatch=").append(value.exactLootMatches)
                    .append(" observedUnique=").append(value.observedUnique)
                    .append(" observedAmbiguous=").append(value.observedAmbiguous)
                    .append('\n');
            }
            text.append('\n');
        }

        void progress(long seed, String id, Coverage value) {
            text.append("PROGRESS seed=").append(seed).append(" id=").append(id)
                .append(" actual=").append(value.actual)
                .append(" predicted=").append(value.predicted)
                .append(" missed=").append(value.missed)
                .append(" extra=").append(value.extra)
                .append(" actualChests=").append(value.actualChests)
                .append(" predictedChests=").append(value.predictedChests)
                .append(" missedChests=").append(value.missedChests)
                .append(" extraChests=").append(value.extraChests)
                .append(" lootMismatch=").append(value.lootMismatches)
                .append(" modeledLootMatch=").append(value.modeledLootMatches)
                .append(" metadataUnverified=").append(value.metadataUnverified)
                .append(" layoutMatch=").append(value.layoutMatches)
                .append(" layoutMismatch=").append(value.layoutMismatches)
                .append(" layoutUnverified=").append(value.layoutUnverified).append('\n');
        }

        void dungeonProgress(long seed, int actual, int predicted) {
            text.append("PROGRESS seed=").append(seed).append(" type=DUNGEON")
                .append(" actual=").append(actual)
                .append(" predicted=").append(predicted).append('\n');
        }

        void checkpoint() {
            try {
                Path output = reportPath();
                Path parent = output.toAbsolutePath().normalize().getParent();
                if (parent != null) Files.createDirectories(parent);
                StringBuilder snapshot = new StringBuilder(text)
                    .append("\nSTATUS CHECKPOINT_INCOMPLETE\n")
                    .append("CHECKPOINT failures=").append(failures.size())
                    .append(" unverified=").append(unverified.size()).append('\n');
                failures.forEach(value -> snapshot.append("  FAILURE ").append(value).append('\n'));
                unverified.forEach(value -> snapshot.append("  UNVERIFIED ").append(value).append('\n'));
                Files.writeString(output, snapshot, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // The final write remains authoritative; checkpoints are best effort.
            }
        }

        void failure(String message) {
            failures.add(message);
        }

        void unverified(String message) {
            unverified.add(message);
        }

        void observation(String message) {
            text.append("OBSERVATION ").append(message).append('\n');
        }

        String render() {
            text.append("STATUS COMPLETE\n")
                .append("FAILURES ").append(failures.size()).append('\n');
            failures.forEach(value -> text.append("  ").append(value).append('\n'));
            text.append("\nUNVERIFIED ").append(unverified.size()).append('\n');
            unverified.forEach(value -> text.append("  ").append(value).append('\n'));
            return text.toString();
        }
    }
}
