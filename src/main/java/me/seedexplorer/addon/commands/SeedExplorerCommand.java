/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.seedexplorer.addon.debug.PredictionDebugLogger;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.map.BiomeGenerator;
import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import me.seedexplorer.addon.render.BlockTextureAtlas;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.PredictionStatus;
import me.seedexplorer.addon.workers.WorkerManager;
import me.seedexplorer.addon.worldgen.PredictedBiome;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class SeedExplorerCommand extends Command {
    private static final double PICKUP_DISTANCE_SQUARED = 4.0;
    private static final int VEIN_SCAN_RADIUS = 6;
    private static final int PICKUP_GOAL_RADIUS = 1;
    private static final int PICKUP_SEARCH_RADIUS = 6;
    private static final int PICKUP_SETTLE_TICKS = 12;
    private static final int PICKUP_TIMEOUT_TICKS = 20 * 12;
    private static final int PROBE_TARGET_AFTER_TICKS = 20;
    private static final int PROBE_CLOSE_SKIP_TICKS = 20 * 5;
    private static final double PROBE_CLOSE_DISTANCE_SQUARED = 9.0;
    private static final int TARGET_SKIP_TIMEOUT_TICKS = 20 * 90;
    private static final int PATH_REFRESH_TICKS = 80;
    private static final int VEIN_RESCAN_RADIUS = 2;
    private static final int VEIN_RETRY_PASSES = 1;
    private static final int BREAK_STUCK_TICKS = 20 * 8;
    private static final int ANCIENT_DEBRIS_BREAK_STUCK_TICKS = 20 * 14;
    private static final int MINE_DEBUG_MAX_LINES = 500;
    private static final int MINE_DEBUG_SAMPLE_TICKS = 20;
    private static final int TP_LOOT_WAIT_TICKS = 20 * 60;
    private static final int TP_LOOT_DIMENSION_WAIT_TICKS = 20 * 90;
    private static final int TP_LOOT_OPEN_WAIT_TICKS = 36;
    private static final int TP_LOOT_MAX_OPEN_ATTEMPTS = 3;
    private static final int TP_LOOT_CHAT_INTERVAL_TICKS = 20;
    private static final int TP_LOOT_RETP_INTERVAL_TICKS = 20 * 5;
    private static final int TP_LOOT_FULL_MAX_STRUCTURE_PROBES = 24;
    private static final int TP_LOOT_ANCIENT_CITY_SAMPLE_CHESTS = 5;
    private static final int TP_LOOT_END_CITY_SHIP_TARGETS = 2;
    private static final int TP_LOOT_END_CITY_NO_SHIP_TARGETS = 2;

    private final AtomicInteger minePredictionJobIds = new AtomicInteger();
    private final AtomicInteger tpLootPrepareJobIds = new AtomicInteger();
    private final List<String> predictedMineDebugLines = new ArrayList<>();
    private List<OrePatch> predictedMineTargets = List.of();
    private OreType predictedMineType;
    private int predictedMineDimension;
    private int predictedMineIndex;
    private long predictedMinePathTargetKey = Long.MIN_VALUE;
    private int predictedMinePathRefreshTicks;
    private int predictedMineTargetTicks;
    private int predictedMineVeinIndex;
    private int predictedMinePickupTicks;
    private int predictedMineDebugTicks;
    private int predictedMineProbeCloseTicks;
    private int predictedMineBreakTicks;
    private int predictedMineVeinRetryPasses;
    private List<BlockPos> predictedMineVeinTargets = List.of();
    private List<BlockPos> predictedMinePickupTargets = List.of();
    private int predictedMinePickupIndex;
    private final List<BlockPos> predictedMineMinedBlocks = new ArrayList<>();
    private final Set<Long> predictedMineVeinDeferred = new HashSet<>();
    private BlockPos predictedMinePickupPos;
    private long predictedMineBreakTargetKey = Long.MIN_VALUE;
    private boolean predictedMineActive;
    private boolean predictedMineBreaking;
    private boolean predictedMinePickupActive;
    private volatile boolean predictedMineSearchActive;
    private List<TpLootTarget> tpLootTargets = List.of();
    private int tpLootIndex;
    private int tpLootWaitTicks;
    private int tpLootOpenTicks;
    private int tpLootOpenAttempts;
    private int tpLootMismatches;
    private int tpLootVerified;
    private int tpLootOpenedCount;
    private int tpLootSkipped;
    private int tpLootUnverified;
    private boolean tpLootRunning;
    private volatile boolean tpLootTargetsComplete;
    private volatile boolean tpLootStartRequested;
    private boolean tpLootOpened;
    private boolean tpLootClearedAbove;
    private int tpLootForcedDimension = Integer.MIN_VALUE;
    private int tpLootForcedChunkX = Integer.MIN_VALUE;
    private int tpLootForcedChunkZ = Integer.MIN_VALUE;
    private boolean tpLootRetryNudgeAway;
    private volatile boolean tpLootPreparing;
    private volatile String tpLootPrepareStatus = "";
    private volatile long tpLootPrepareLastNoticeMs;
    private StringBuilder tpLootReport = new StringBuilder();
    private long tpLootStartSeed;
    private String tpLootMode = "";
    private String lastTpLootSummary = "";

    private static final List<String> OVERWORLD_LOCATE_BATCH = List.of(
        "minecraft:village_plains",
        "minecraft:village_desert",
        "minecraft:village_savanna",
        "minecraft:village_snowy",
        "minecraft:village_taiga",
        "minecraft:desert_pyramid",
        "minecraft:jungle_pyramid",
        "minecraft:swamp_hut",
        "minecraft:igloo",
        "minecraft:pillager_outpost",
        "minecraft:monument",
        "minecraft:mansion",
        "minecraft:ancient_city",
        "minecraft:trial_chambers",
        "minecraft:trail_ruins",
        "minecraft:ruined_portal",
        "minecraft:ruined_portal_desert",
        "minecraft:ruined_portal_jungle",
        "minecraft:ruined_portal_swamp",
        "minecraft:ruined_portal_mountain",
        "minecraft:ruined_portal_ocean",
        "minecraft:shipwreck",
        "minecraft:shipwreck_beached",
        "minecraft:ocean_ruin_cold",
        "minecraft:ocean_ruin_warm"
    );

    public SeedExplorerCommand() {
        super("seed-explorer", "Base command for the Seed Explorer addon.");
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            info("Seed Explorer addon loaded. Use sub-commands for specific functionality.");
            return SINGLE_SUCCESS;
        });

        builder.then(literal("biome-debug").executes(context -> {
            if (mc.player == null || mc.level == null) {
                error("Join a world first.");
                return SINGLE_SUCCESS;
            }

            long seed = SeedManager.get().getWorldSeed();
            if (seed == 0) {
                error("No seed set. Use .seed <seed> or the Seed Explorer seed box first.");
                return SINGLE_SUCCESS;
            }

            BlockPos pos = mc.player.blockPosition();
            int dimension = dimensionId();
            PredictedBiome predicted = BiomeGenerator.getPredictedBiome(pos.getX(), pos.getZ(), seed, dimension);
            PredictedBiome actual = WorldgenEngine.fromRuntimeBiome(mc.level.getBiome(pos));

            info("Seed Explorer biome debug at (highlight)%d, %d, %d(default):", pos.getX(), pos.getY(), pos.getZ());
            info("Predicted: (highlight)%s(default) [%s]", predicted.displayName(), predicted.id());
            info("Loaded world: (highlight)%s(default) [%s]", actual.displayName(), actual.id());
            if (!predicted.id().equals(actual.id())) {
                warning("Mismatch. This means the backend still needs more work for this seed/dimension.");
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("structure-debug").executes(context -> {
            if (mc.player == null || mc.level == null) {
                error("Join a world first.");
                return SINGLE_SUCCESS;
            }

            long seed = SeedManager.get().getWorldSeed();
            if (seed == 0) {
                error("No seed set. Use .seed <seed> or the Seed Explorer seed box first.");
                return SINGLE_SUCCESS;
            }

            if (dimensionId() != 0) {
                error("Overworld structure debug only supports the Overworld right now.");
                return SINGLE_SUCCESS;
            }

            BlockPos pos = mc.player.blockPosition();
            int chunkX = Math.floorDiv(pos.getX(), 16);
            int chunkZ = Math.floorDiv(pos.getZ(), 16);
            String version = SeedManager.get().getMcVersion();
            StringBuilder report = new StringBuilder();
            appendLine(report, "Seed Explorer structure-debug");
            appendLine(report, "seed=" + seed);
            appendLine(report, "version=" + (version == null || version.isBlank() ? "unknown" : version));
            appendLine(report, "player=" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            appendLine(report, "chunk=" + chunkX + "," + chunkZ);
            info("Structure debug using seed (highlight)%d(default)%s at chunk (highlight)%d, %d(default).",
                seed,
                version == null || version.isBlank() ? "" : " version " + version,
                chunkX,
                chunkZ);
            List<GeneratedStructure> structures = VanillaStructurePredictor.predictOverworld(seed, chunkX - 160, chunkZ - 160, chunkX + 160, chunkZ + 160);

            if (structures.isEmpty()) {
                List<VanillaStructurePredictor.DebugCandidate> candidates = VanillaStructurePredictor.debugOverworld(seed, chunkX - 160, chunkZ - 160, chunkX + 160, chunkZ + 160);
                if (candidates.isEmpty()) {
                    warning("No vanilla placement candidates found nearby.");
                    appendLine(report, "result=no vanilla placement candidates nearby");
                } else {
                    VanillaStructurePredictor.DebugCandidate closestCandidate = candidates.stream()
                        .min(Comparator.comparingLong(s -> distanceSquared(pos.getX(), pos.getZ(), s.x(), s.z())))
                        .orElse(null);
                    if (closestCandidate != null) {
                        int distance = (int) Math.round(Math.sqrt(distanceSquared(pos.getX(), pos.getZ(), closestCandidate.x(), closestCandidate.z())));
                        warning("No valid supported structure nearby. Closest raw candidate:");
                        info("(highlight)%s(default) at (highlight)%d, %d(default), biome %s, valid=%s, distance %d blocks.",
                            closestCandidate.structureId(), closestCandidate.x(), closestCandidate.z(),
                            closestCandidate.biomeId(), closestCandidate.validBiome(), distance);
                        appendLine(report, "result=no valid supported structure nearby");
                        appendLine(report, "closest_raw=" + closestCandidate.structureId()
                            + " x=" + closestCandidate.x()
                            + " z=" + closestCandidate.z()
                            + " biome=" + closestCandidate.biomeId()
                            + " valid=" + closestCandidate.validBiome()
                            + " distance=" + distance);
                    }
                }
                DebugReportWriter.copyAndSave("structure-debug", report.toString());
                return SINGLE_SUCCESS;
            }

            GeneratedStructure closest = structures.stream()
                .min(Comparator.comparingLong(s -> distanceSquared(pos.getX(), pos.getZ(), s.x, s.z)))
                .orElse(null);
            if (closest == null) return SINGLE_SUCCESS;

            long distanceSquared = distanceSquared(pos.getX(), pos.getZ(), closest.x, closest.z);
            int distance = (int) Math.round(Math.sqrt(distanceSquared));
            info("Closest supported prediction: (highlight)%s(default) at (highlight)%d, %d(default), distance %d blocks.",
                closest.displayName(), closest.x, closest.z, distance);
            info("Biome there: (highlight)%s(default)", VanillaStructurePredictor.biomeAt(seed, closest.x, closest.z));
            appendLine(report, "closest=" + closest.displayName()
                + " x=" + closest.x
                + " z=" + closest.z
                + " distance=" + distance
                + " biome=" + VanillaStructurePredictor.biomeAt(seed, closest.x, closest.z));
            DebugReportWriter.copyAndSave("structure-debug", report.toString());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("structure-check")
            .then(argument("x", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer())
                    .executes(context -> {
                        int x = IntegerArgumentType.getInteger(context, "x");
                        int z = IntegerArgumentType.getInteger(context, "z");
                        return runStructureCheck(x, z);
                    })
                )
            )
        );

        builder.then(literal("ore-debug")
            .executes(context -> runOreDebug(3))
            .then(argument("radius", IntegerArgumentType.integer(0, 8))
                .executes(context -> runOreDebug(IntegerArgumentType.getInteger(context, "radius")))
            )
            .then(literal("stop")
                .executes(context -> {
                    OreDebugRunner.get().stop();
                    info("Stopped ore-debug.");
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("mine-predicted")
            .then(literal("stop")
                .executes(context -> stopBaritonePathing())
            )
            .then(literal("debug")
                .executes(context -> writePredictedMineDebugReport())
            )
            .then(argument("ore", StringArgumentType.word())
                .executes(context -> runMinePredicted(StringArgumentType.getString(context, "ore"), 8, 24))
                .then(argument("radius", IntegerArgumentType.integer(1, 12))
                    .executes(context -> runMinePredicted(
                        StringArgumentType.getString(context, "ore"),
                        IntegerArgumentType.getInteger(context, "radius"),
                        24
                    ))
                    .then(argument("targets", IntegerArgumentType.integer(1, 64))
                        .executes(context -> runMinePredicted(
                            StringArgumentType.getString(context, "ore"),
                            IntegerArgumentType.getInteger(context, "radius"),
                            IntegerArgumentType.getInteger(context, "targets")
                        ))
                    )
                )
            )
        );

        builder.then(literal("locate-batch")
            .executes(context -> runLocateBatch())
            .then(literal("stop")
                .executes(context -> {
                    LocateBatchRunner.get().stop();
                    info("Stopped locate batch.");
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("validate-overworld")
            .executes(context -> runValidateOverworld())
            .then(literal("stop")
                .executes(context -> {
                    LocateBatchRunner.get().stop();
                    info("Stopped overworld validation scan.");
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("test")
            .executes(context -> runLootPredictionTest(1))
            .then(argument("level", IntegerArgumentType.integer(1, 3))
                .executes(context -> runLootPredictionTest(IntegerArgumentType.getInteger(context, "level")))
            )
        );

        builder.then(literal("test-tp")
            .executes(context -> runTeleportLootTest(3, List.of(0, -1, 1), true, true, "full"))
            .then(literal("start")
                .executes(context -> runTeleportLootTest(1))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level")))
                )
            )
            .then(literal("audit")
                .executes(context -> runTeleportLootTest(3, List.of(0, -1, 1), true, true, "audit"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(0, -1, 1), true, true, "audit"))
                )
            )
            .then(literal("full")
                .executes(context -> runTeleportLootTest(3, List.of(0, -1, 1), true, true, "full"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(0, -1, 1), true, true, "full"))
                )
            )
            .then(literal("all")
                .executes(context -> runTeleportLootTest(3, List.of(0, -1, 1), true, true, "all"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(0, -1, 1), true, true, "all"))
                )
            )
            .then(literal("all-structures")
                .executes(context -> runTeleportLootTest(3, List.of(0, -1, 1), true, true, true, "all-structures"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(0, -1, 1), true, true, true, "all-structures"))
                )
            )
            .then(literal("overworld")
                .executes(context -> runTeleportLootTest(3, List.of(0), false, "overworld"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(0), false, "overworld"))
                )
            )
            .then(literal("nether")
                .executes(context -> runTeleportLootTest(3, List.of(-1), false, "nether"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(-1), false, "nether"))
                )
            )
            .then(literal("end")
                .executes(context -> runTeleportLootTest(3, List.of(1), true, "end"))
                .then(argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(context -> runTeleportLootTest(IntegerArgumentType.getInteger(context, "level"), List.of(1), true, "end"))
                )
            )
            .then(literal("status")
                .executes(context -> teleportLootTestStatus())
            )
            .then(literal("last")
                .executes(context -> writeLastTeleportLootSummary())
            )
            .then(literal("stop")
                .executes(context -> stopTeleportLootTest(true))
            )
        );

        builder.then(literal("prediction-status")
            .executes(context -> runPredictionStatus())
        );

        builder.then(literal("3d-status")
            .executes(context -> run3dStatus())
        );
    }

    private int run3dStatus() {
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        info("Seed Explorer 3D: %s. Texture object %s.",
            atlas.status(), atlas.texture() == null ? "missing" : "ready");
        return SINGLE_SUCCESS;
    }

    private int runPredictionStatus() {
        StringBuilder report = new StringBuilder();
        appendLine(report, "Seed Explorer Overworld prediction status");
        appendLine(report, "");
        for (PredictionStatus.Entry entry : PredictionStatus.overworld()) {
            String line = entry.name() + " = " + entry.status();
            if (!entry.note().isBlank()) line += " (" + entry.note() + ")";
            appendLine(report, line);
            info(line);
        }
        DebugReportWriter.copyAndSave("prediction-status", report.toString());
        return SINGLE_SUCCESS;
    }

    private int runOreDebug(int radiusChunks) {
        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> or the Seed Explorer seed box first.");
            return SINGLE_SUCCESS;
        }

        OreDebugRunner.get().start(radiusChunks);
        return SINGLE_SUCCESS;
    }

    public int runMinePredictedFromChat(String oreName, int radiusChunks, int targetLimit) {
        return runMinePredicted(oreName, radiusChunks, targetLimit);
    }

    public int stopMinePredictedFromChat() {
        return stopBaritonePathing();
    }

    public boolean hasActivePredictedMineSession() {
        return predictedMineActive || predictedMineSearchActive;
    }

    private int runMinePredicted(String oreName, int radiusChunks, int targetLimit) {
        if (oreName != null && "debug".equalsIgnoreCase(oreName.trim())) {
            return writePredictedMineDebugReport();
        }

        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> or the Seed Explorer seed box first.");
            return SINGLE_SUCCESS;
        }

        OreType oreType = parseOreType(oreName);
        if (oreType == null) {
            error("Unknown ore '%s'. Try diamond, ancient_debris, quartz, nether_gold, iron, gold, redstone, lapis, coal, copper, or emerald.", oreName);
            return SINGLE_SUCCESS;
        }

        int dimension = dimensionId();
        if (oreType.dimension != dimension) {
            error("%s prediction is for the %s, but you are in the %s.",
                oreType.displayName,
                dimensionName(oreType.dimension),
                dimensionName(dimension));
            return SINGLE_SUCCESS;
        }

        BlockPos playerPos = mc.player.blockPosition();
        int radius = Math.max(1, Math.min(radiusChunks, 12));
        int maxTargets = Math.max(1, Math.min(targetLimit, 64));
        int centerChunkX = Math.floorDiv(playerPos.getX(), 16);
        int centerChunkZ = Math.floorDiv(playerPos.getZ(), 16);
        int centerX = playerPos.getX();
        int centerY = playerPos.getY();
        int centerZ = playerPos.getZ();
        int jobId = minePredictionJobIds.incrementAndGet();
        predictedMineSearchActive = true;

        info("Searching predicted %s targets within %d chunk%s...",
            oreType.displayName,
            radius,
            radius == 1 ? "" : "s");

        if (!WorkerManager.get().submit(() -> runMinePredictionJob(
            jobId,
            seed,
            dimension,
            oreType,
            radius,
            maxTargets,
            centerChunkX,
            centerChunkZ,
            centerX,
            centerY,
            centerZ
        ))) {
            predictedMineSearchActive = false;
            error("Seed Explorer worker queue is full. Try again in a moment.");
        }

        return SINGLE_SUCCESS;
    }

    private void runMinePredictionJob(int jobId, long seed, int dimension, OreType oreType, int radius,
                                      int maxTargets, int centerChunkX, int centerChunkZ,
                                      int centerX, int centerY, int centerZ) {
        List<OrePatch> candidates;
        try {
            candidates = OrePredictor.predictInChunkRadius(
                centerChunkX,
                centerChunkZ,
                radius,
                dimension,
                oreType,
                maxTargets * 3,
                centerX,
                centerY,
                centerZ,
                seed
            );
        } catch (Throwable throwable) {
            mc.execute(() -> {
                if (jobId == minePredictionJobIds.get()) predictedMineSearchActive = false;
                error("Predicted ore search failed: %s: %s",
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage());
            });
            return;
        }

        List<OrePatch> targets = filterMineTargets(candidates, dimension, maxTargets);

        mc.execute(() -> finishMinePredictionJob(jobId, oreType, radius, targets));
    }

    private void finishMinePredictionJob(int jobId, OreType oreType, int radius, List<OrePatch> targets) {
        if (jobId != minePredictionJobIds.get()) return;
        predictedMineSearchActive = false;

        if (targets.isEmpty()) {
            warning("No uncleared predicted %s blocks found within %d chunks.", oreType.displayName, radius);
            return;
        }

        startPredictedMineSession(oreType, targets);
    }

    private void startPredictedMineSession(OreType oreType, List<OrePatch> targets) {
        if (predictedMineActive) stopPredictedMineSession(false);
        else cancelBaritonePathing(false);

        predictedMineTargets = List.copyOf(targets);
        predictedMineType = oreType;
        predictedMineDimension = oreType.dimension;
        predictedMineIndex = 0;
        predictedMinePathTargetKey = Long.MIN_VALUE;
        predictedMinePathRefreshTicks = 0;
        predictedMineTargetTicks = 0;
        predictedMineVeinIndex = 0;
        predictedMinePickupTicks = 0;
        predictedMineDebugTicks = 0;
        predictedMineProbeCloseTicks = 0;
        predictedMineBreakTicks = 0;
        predictedMineVeinRetryPasses = 0;
        predictedMineVeinTargets = List.of();
        predictedMinePickupTargets = List.of();
        predictedMinePickupIndex = 0;
        predictedMineMinedBlocks.clear();
        predictedMineVeinDeferred.clear();
        predictedMinePickupPos = null;
        predictedMineBreakTargetKey = Long.MIN_VALUE;
        predictedMineBreaking = false;
        predictedMinePickupActive = false;
        predictedMineActive = true;
        predictedMineDebugLines.clear();
        recordPredictedMineDebug("start type=" + oreType.name()
            + " targets=" + targets.size()
            + " dimension=" + predictedMineDimension
            + " player=" + formatBlockPos(mc.player.blockPosition()));

        if (!pathToCurrentPredictedOre()) {
            predictedMineActive = false;
            return;
        }

        OrePatch first = predictedMineTargets.getFirst();
        info("Mining predicted %s queue: %d target%s, first at %d, %d, %d.",
            oreType.displayName,
            predictedMineTargets.size(),
            predictedMineTargets.size() == 1 ? "" : "s",
            first.x,
            first.y,
            first.z);
    }

    private boolean pathToCurrentPredictedOre() {
        OrePatch target = currentPredictedMineTarget();
        if (target == null) {
            finishPredictedMineSession();
            return false;
        }

        BaritoneResult result = startBaritonePathing(List.of(target), 0);
        if (!result.success()) {
            error(result.message());
            return false;
        }

        predictedMinePathTargetKey = BlockPos.asLong(target.x, target.y, target.z);
        predictedMinePathRefreshTicks = 0;
        return true;
    }

    private OrePatch currentPredictedMineTarget() {
        if (!predictedMineActive || predictedMineIndex < 0 || predictedMineIndex >= predictedMineTargets.size()) return null;
        return predictedMineTargets.get(predictedMineIndex);
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        tickPredictedMineSession();
        tickTeleportLootPrepareHeartbeat();
        tickTeleportLootTest();
    }

    private void tickTeleportLootPrepareHeartbeat() {
        if (!tpLootPreparing) return;
        long now = System.currentTimeMillis();
        if (tpLootPrepareLastNoticeMs == 0L) {
            tpLootPrepareLastNoticeMs = now;
            return;
        }
        if (now - tpLootPrepareLastNoticeMs < 15_000L) return;
        tpLootPrepareLastNoticeMs = now;
        if (tpLootPrepareStatus.isBlank()) {
            info("Seed Explorer TP test: still preparing targets.");
        } else {
            info("Seed Explorer TP test: still preparing targets - %s.", tpLootPrepareStatus);
        }
    }

    private void tickPredictedMineSession() {
        if (!predictedMineActive) return;
        predictedMineDebugTicks++;

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            recordPredictedMineDebug("stop missing-player-or-level");
            clearPredictedMineSession();
            return;
        }

        if (dimensionId() != predictedMineDimension) {
            recordPredictedMineDebug("stop dimension-changed current=" + dimensionId() + " expected=" + predictedMineDimension);
            warning("Stopped predicted mining because you changed dimension.");
            stopPredictedMineSession(false);
            return;
        }

        if (predictedMinePickupActive) {
            recordPredictedMineDebugSample("pickup");
            tickPredictedMinePickup();
            return;
        }

        if (!predictedMineVeinTargets.isEmpty()) {
            recordPredictedMineDebugSample("vein");
            tickPredictedMineVein();
            return;
        }

        OrePatch target = currentPredictedMineTarget();
        if (target == null) {
            recordPredictedMineDebug("finish no-current-target");
            finishPredictedMineSession();
            return;
        }

        predictedMineTargetTicks++;
        BlockPos targetPos = new BlockPos(target.x, target.y, target.z);
        recordPredictedMineDebugSample("search target=" + formatBlockPos(targetPos));

        if (SeedManager.get().isClearedOre(target, predictedMineDimension)) {
            recordPredictedMineDebug("skip already-cleared target=" + formatBlockPos(targetPos));
            advancePredictedMineTarget();
            return;
        }

        if (!isBlockLoaded(targetPos)) {
            predictedMineBreaking = false;
            predictedMinePathRefreshTicks++;

            if (predictedMinePathTargetKey != BlockPos.asLong(target.x, target.y, target.z) || predictedMinePathRefreshTicks >= PATH_REFRESH_TICKS) {
                pathToCurrentPredictedOre();
            }

            if (predictedMineTargetTicks >= TARGET_SKIP_TIMEOUT_TICKS) {
                recordPredictedMineDebug("skip unloaded target=" + formatBlockPos(targetPos));
                warning("Skipping predicted %s at %d, %d, %d because its chunk never loaded.",
                    target.type.displayName,
                    target.x,
                    target.y,
                    target.z);
                advancePredictedMineTarget();
            }
            return;
        }

        BlockState state = mc.level.getBlockState(targetPos);
        boolean targetIsOre = predictedMineType.matches(state);
        if (targetIsOre) {
            recordPredictedMineDebug("found ore target=" + formatBlockPos(targetPos)
                + " state=" + blockStateId(state));
            beginPredictedMineVein(targetPos);
            return;
        }

        if (state.isAir()) {
            recordPredictedMineDebug("skip air target=" + formatBlockPos(targetPos)
                + " state=" + blockStateId(state));
            markCurrentPredictedOreCleared();
            advancePredictedMineTarget();
            return;
        }

        if (predictedMineTargetTicks >= PROBE_TARGET_AFTER_TICKS && shouldProbePredictedTarget(state)) {
            if (probeLoadedWrongPrediction(targetPos, state)) return;

            predictedMineProbeCloseTicks++;
            if (predictedMineProbeCloseTicks >= PROBE_CLOSE_SKIP_TICKS && isCloseTo(targetPos, PROBE_CLOSE_DISTANCE_SQUARED) && !isBaritonePathing()) {
                recordPredictedMineDebug("skip close wrong-prediction target=" + formatBlockPos(targetPos)
                    + " state=" + blockStateId(state)
                    + " blocker=" + formatBlockPos(targetBlocker(targetPos)));
                warning("Skipping predicted %s at %d, %d, %d because the loaded block is %s.",
                    target.type.displayName,
                    target.x,
                    target.y,
                    target.z,
                    blockStateId(state));
                markCurrentPredictedOreCleared();
                advancePredictedMineTarget();
                return;
            }
        } else {
            predictedMineProbeCloseTicks = 0;
        }

        if (isCloseTo(targetPos, PROBE_CLOSE_DISTANCE_SQUARED) && !isBaritonePathing()) {
            predictedMinePathRefreshTicks = PATH_REFRESH_TICKS;
        }

        if (predictedMineTargetTicks >= TARGET_SKIP_TIMEOUT_TICKS / 3 && isCloseTo(targetPos, PROBE_CLOSE_DISTANCE_SQUARED) && !isBaritonePathing()) {
            recordPredictedMineDebug("skip stalled near non-ore target=" + formatBlockPos(targetPos)
                + " state=" + blockStateId(state));
            warning("Skipping predicted %s at %d, %d, %d because Baritone stopped beside a non-ore block.",
                target.type.displayName,
                target.x,
                target.y,
                target.z);
            markCurrentPredictedOreCleared();
            advancePredictedMineTarget();
            return;
        }

        if (predictedMineTargetTicks >= TARGET_SKIP_TIMEOUT_TICKS) {
            recordPredictedMineDebug("skip not-found target=" + formatBlockPos(targetPos)
                + " state=" + blockStateId(state));
            warning("Skipping predicted %s at %d, %d, %d because it was not found.",
                target.type.displayName,
                target.x,
                target.y,
                target.z);
            markCurrentPredictedOreCleared();
            advancePredictedMineTarget();
            return;
        }

        predictedMineBreaking = false;
        predictedMinePathRefreshTicks++;

        if (predictedMinePathTargetKey != BlockPos.asLong(target.x, target.y, target.z) || predictedMinePathRefreshTicks >= PATH_REFRESH_TICKS) {
            pathToCurrentPredictedOre();
        }
    }

    private void beginPredictedMineVein(BlockPos seedPos) {
        predictedMineVeinTargets = new ArrayList<>(findConnectedOreBlocks(seedPos));
        predictedMineVeinIndex = 0;
        predictedMineBreaking = false;
        predictedMineBreakTargetKey = Long.MIN_VALUE;
        predictedMineBreakTicks = 0;
        predictedMineVeinRetryPasses = 0;
        predictedMineVeinDeferred.clear();
        predictedMinePathRefreshTicks = PATH_REFRESH_TICKS;
        predictedMineMinedBlocks.clear();
        recordPredictedMineDebug("begin-vein seed=" + formatBlockPos(seedPos)
            + " blocks=" + predictedMineVeinTargets.size()
            + " player=" + formatBlockPos(mc.player.blockPosition()));
        cancelBaritonePathing(false);
        tickPredictedMineVein();
    }

    private void tickPredictedMineVein() {
        while (prepareNextVeinTarget()) {
            BlockPos pos = predictedMineVeinTargets.get(predictedMineVeinIndex);
            if (!isBlockLoaded(pos)) {
                recordPredictedMineDebugSample("vein-target-unloaded pos=" + formatBlockPos(pos));
                pathToMineTarget(pos);
                return;
            }

            BlockState state = mc.level.getBlockState(pos);

            if (!predictedMineType.matches(state)) {
                recordPredictedMineDebug("ore-gone pos=" + formatBlockPos(pos)
                    + " state=" + blockStateId(state));
                markOreCleared(pos);
                if (predictedMineMinedBlocks.stream().noneMatch(pos::equals)) predictedMineMinedBlocks.add(pos);
                predictedMinePickupPos = pos;
                predictedMineVeinIndex++;
                predictedMineBreaking = false;
                predictedMineBreakTargetKey = Long.MIN_VALUE;
                predictedMineBreakTicks = 0;
                rescanVisibleVeinOres();
                continue;
            }

            if (canTargetBlock(pos)) {
                long key = pos.asLong();
                if (!predictedMineBreaking || predictedMineBreakTargetKey != key) {
                    cancelBaritonePathing(false);
                    predictedMineBreakTicks = 0;
                }
                predictedMineBreaking = true;
                predictedMineBreakTargetKey = key;
                predictedMineBreakTicks++;
                if (predictedMineBreakTicks >= breakStuckTimeoutTicks()) {
                    deferCurrentVeinTarget(pos, "ore-break-stuck");
                    return;
                }
                ensurePickaxeSelected();
                boolean started = BlockUtils.breakBlock(pos, true);
                recordPredictedMineDebugSample("break-ore pos=" + formatBlockPos(pos)
                    + " started=" + started
                    + " dist=" + distanceToPlayer(pos)
                    + " breakTicks=" + predictedMineBreakTicks);
                if (!started) pathToMineTarget(pos);
                return;
            }

            BlockPos blocker = targetBlocker(pos);
            if (blocker != null && canTargetBlock(blocker)) {
                long key = blocker.asLong();
                if (!predictedMineBreaking || predictedMineBreakTargetKey != key) predictedMineBreakTicks = 0;
                predictedMineBreaking = true;
                predictedMineBreakTargetKey = key;
                predictedMineBreakTicks++;
                if (predictedMineBreakTicks >= breakStuckTimeoutTicks()) {
                    deferCurrentVeinTarget(pos, "blocker-break-stuck blocker=" + formatBlockPos(blocker));
                    return;
                }
                ensurePickaxeSelected();
                boolean started = BlockUtils.breakBlock(blocker, true);
                recordPredictedMineDebugSample("break-blocker ore=" + formatBlockPos(pos)
                    + " blocker=" + formatBlockPos(blocker)
                    + " blockerState=" + blockStateId(mc.level.getBlockState(blocker))
                    + " started=" + started
                    + " breakTicks=" + predictedMineBreakTicks);
                if (!started) pathToMineTarget(pos);
                return;
            }

            predictedMineBreaking = false;
            predictedMineBreakTicks = 0;
            recordPredictedMineDebugSample("path-to-ore pos=" + formatBlockPos(pos)
                + " dist=" + distanceToPlayer(pos)
                + " blocker=" + formatBlockPos(blocker));
            pathToMineTarget(pos);
            return;
        }

        recordPredictedMineDebug("vein-complete minedBlocks=" + predictedMineMinedBlocks.size());
        beginPredictedMinePickup();
    }

    private boolean prepareNextVeinTarget() {
        while (predictedMineVeinIndex < predictedMineVeinTargets.size()
            && predictedMineVeinDeferred.contains(predictedMineVeinTargets.get(predictedMineVeinIndex).asLong())) {
            predictedMineVeinIndex++;
        }

        if (predictedMineVeinIndex < predictedMineVeinTargets.size()) return true;

        if (rescanVisibleVeinOres()) return true;

        if (!predictedMineVeinDeferred.isEmpty() && predictedMineVeinRetryPasses < VEIN_RETRY_PASSES) {
            predictedMineVeinRetryPasses++;
            predictedMineVeinDeferred.clear();
            predictedMineVeinIndex = 0;
            predictedMinePathRefreshTicks = PATH_REFRESH_TICKS;
            recordPredictedMineDebug("retry-deferred-vein-blocks pass=" + predictedMineVeinRetryPasses);
            return !predictedMineVeinTargets.isEmpty();
        }

        return false;
    }

    private void deferCurrentVeinTarget(BlockPos pos, String reason) {
        predictedMineVeinDeferred.add(pos.asLong());
        predictedMineVeinIndex++;
        predictedMineBreaking = false;
        predictedMineBreakTargetKey = Long.MIN_VALUE;
        predictedMineBreakTicks = 0;
        predictedMinePathRefreshTicks = PATH_REFRESH_TICKS;
        recordPredictedMineDebug("defer-vein-target pos=" + formatBlockPos(pos)
            + " reason=" + reason
            + " deferred=" + predictedMineVeinDeferred.size()
            + "/" + predictedMineVeinTargets.size());
    }

    private void beginPredictedMinePickup() {
        predictedMineVeinTargets = List.of();
        predictedMineVeinIndex = 0;
        predictedMineBreakTargetKey = Long.MIN_VALUE;
        predictedMineBreaking = false;
        predictedMinePickupTargets = findPickupTargets();
        predictedMinePickupIndex = 0;
        predictedMinePickupActive = true;
        predictedMinePickupTicks = 0;

        BlockPos pickup = currentPickupTarget();
        if (pickup == null) {
            recordPredictedMineDebug("pickup-skip no-targets");
            predictedMinePickupActive = false;
            advancePredictedMineTarget();
            return;
        }

        recordPredictedMineDebug("begin-pickup targets=" + predictedMinePickupTargets.size()
            + " first=" + formatBlockPos(pickup));
        pathNearPickup(pickup);
    }

    private void tickPredictedMinePickup() {
        predictedMinePickupTicks++;
        BlockPos visibleDrop = nearestPredictedMineDrop();
        if (visibleDrop != null) {
            predictedMinePickupPos = visibleDrop;
        } else if (predictedMinePickupPos == null || isCloseTo(predictedMinePickupPos, PICKUP_DISTANCE_SQUARED)) {
            BlockPos next = currentPickupTarget();
            while (next != null && isCloseTo(next, PICKUP_DISTANCE_SQUARED)) {
                predictedMinePickupIndex++;
                next = currentPickupTarget();
            }
            predictedMinePickupPos = next;
        }

        BlockPos pickup = predictedMinePickupPos;
        boolean closeEnough = pickup == null || isCloseTo(pickup, PICKUP_DISTANCE_SQUARED);
        boolean dropsGone = nearestPredictedMineDrop() == null;

        if (dropsGone && pickup == null && predictedMinePickupTicks >= PICKUP_SETTLE_TICKS) {
            recordPredictedMineDebug("pickup-complete no-visible-drop");
            predictedMinePickupActive = false;
            predictedMinePickupPos = null;
            predictedMinePickupTargets = List.of();
            advancePredictedMineTarget();
            return;
        }

        if (dropsGone && closeEnough && predictedMinePickupTicks >= PICKUP_SETTLE_TICKS && predictedMinePickupIndex >= predictedMinePickupTargets.size()) {
            recordPredictedMineDebug("pickup-complete close-and-drops-gone");
            predictedMinePickupActive = false;
            predictedMinePickupPos = null;
            predictedMinePickupTargets = List.of();
            advancePredictedMineTarget();
            return;
        }

        if (predictedMinePickupTicks >= PICKUP_TIMEOUT_TICKS) {
            recordPredictedMineDebug("pickup-timeout pickup=" + formatBlockPos(pickup)
                + " visibleDrop=" + formatBlockPos(visibleDrop)
                + " close=" + closeEnough);
            warning("Continuing after pickup timeout near predicted %s vein.", predictedMineType.displayName);
            predictedMinePickupActive = false;
            predictedMinePickupPos = null;
            predictedMinePickupTargets = List.of();
            advancePredictedMineTarget();
            return;
        }

        if (pickup != null && (predictedMinePickupTicks % PATH_REFRESH_TICKS == 1 || predictedMinePathTargetKey != pickup.asLong())) {
            pathNearPickup(pickup);
        }
    }

    private void pathToMineTarget(BlockPos pos) {
        long key = pos.asLong();
        predictedMinePathRefreshTicks++;
        if (predictedMinePathTargetKey == key && predictedMinePathRefreshTicks < PATH_REFRESH_TICKS) return;

        predictedMinePathTargetKey = key;
        predictedMinePathRefreshTicks = 0;
        recordPredictedMineDebug("baritone-path ore-target=" + formatBlockPos(pos)
            + " dist=" + distanceToPlayer(pos));
        startBaritonePathing(List.of(new OrePatch(pos.getX(), pos.getY(), pos.getZ(), predictedMineType, true)), 0);
    }

    private void pathNearPickup(BlockPos pickup) {
        predictedMinePathTargetKey = pickup.asLong();
        predictedMinePathRefreshTicks = 0;
        recordPredictedMineDebug("baritone-path pickup=" + formatBlockPos(pickup)
            + " dist=" + distanceToPlayer(pickup));
        startBaritonePathing(List.of(new OrePatch(pickup.getX(), pickup.getY(), pickup.getZ(), predictedMineType, true)), PICKUP_GOAL_RADIUS);
    }

    private boolean probeLoadedWrongPrediction(BlockPos targetPos, BlockState targetState) {
        BlockPos breakPos = null;
        String action = "none";

        if (canTargetBlock(targetPos)) {
            breakPos = targetPos;
            action = "target";
        } else {
            BlockPos blocker = targetBlocker(targetPos);
            if (blocker != null && canTargetBlock(blocker)) {
                breakPos = blocker;
                action = "blocker";
            }
        }

        if (breakPos == null) return false;

        long key = breakPos.asLong();
        if (!predictedMineBreaking || predictedMineBreakTargetKey != key) {
            cancelBaritonePathing(false);
            predictedMineBreakTicks = 0;
        }

        predictedMineBreaking = true;
        predictedMineBreakTargetKey = key;
        predictedMineBreakTicks++;

        if (predictedMineBreakTicks >= breakStuckTimeoutTicks()) {
            OrePatch target = currentPredictedMineTarget();
            recordPredictedMineDebug("skip stuck probe target=" + formatBlockPos(targetPos)
                + " break=" + formatBlockPos(breakPos)
                + " action=" + action
                + " targetState=" + blockStateId(targetState)
                + " breakState=" + blockStateId(mc.level.getBlockState(breakPos)));
            if (target != null) {
                warning("Skipping predicted %s at %d, %d, %d because the probe block would not break.",
                    target.type.displayName,
                    target.x,
                    target.y,
                    target.z);
            }
            markCurrentPredictedOreCleared();
            advancePredictedMineTarget();
            return true;
        }

        ensurePickaxeSelected();
        boolean started = BlockUtils.breakBlock(breakPos, true);
        recordPredictedMineDebugSample("probe-" + action
            + " target=" + formatBlockPos(targetPos)
            + " break=" + formatBlockPos(breakPos)
            + " targetState=" + blockStateId(targetState)
            + " breakState=" + blockStateId(mc.level.getBlockState(breakPos))
            + " started=" + started
            + " dist=" + distanceToPlayer(breakPos)
            + " breakTicks=" + predictedMineBreakTicks);
        if (!started) pathToCurrentPredictedOre();
        return true;
    }

    private boolean canTargetBlock(BlockPos pos) {
        if (mc.player == null || mc.level == null) return false;

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        if (eyes.distanceToSqr(center) > mc.player.blockInteractionRange() * mc.player.blockInteractionRange()) return false;

        BlockHitResult result = mc.level.clip(new ClipContext(eyes, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos);
    }

    private BlockPos targetBlocker(BlockPos target) {
        if (mc.player == null || mc.level == null) return null;

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        if (eyes.distanceToSqr(center) > mc.player.blockInteractionRange() * mc.player.blockInteractionRange()) return null;

        BlockHitResult result = mc.level.clip(new ClipContext(eyes, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (result.getType() != HitResult.Type.BLOCK) return null;

        BlockPos blocker = result.getBlockPos();
        if (blocker.equals(target)) return null;

        BlockState state = mc.level.getBlockState(blocker);
        if (!BlockUtils.canBreak(blocker, state)) return null;

        return blocker;
    }

    private boolean isBlockLoaded(BlockPos pos) {
        return mc.level != null && mc.level.getChunkSource().hasChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }

    /**
     * True once the target chest block has actually arrived at pos. {@link #isBlockLoaded}
     * only checks {@code hasChunk}, which returns true for a stale/still-settling client
     * chunk that still shows terrain. Deep/far targets were being skipped as
     * "not-container" on the first tick hasChunk flipped true because the real chest block
     * had not been swapped in yet. Gate the terminal read on the block (or its randomizable
     * container block entity) actually being present.
     */
    private boolean isChestBlockPresent(BlockPos pos) {
        if (mc.level == null) return false;
        if (isChestLootBlock(mc.level.getBlockState(pos))) return true;
        return mc.level.getBlockEntity(pos) instanceof RandomizableContainer;
    }

    private boolean shouldProbePredictedTarget(BlockState state) {
        if (state == null || predictedMineType == null) return false;

        Block block = state.getBlock();
        if (predictedMineType.dimension == -1) {
            return block == Blocks.NETHERRACK
                || block == Blocks.BASALT
                || block == Blocks.SMOOTH_BASALT
                || block == Blocks.BLACKSTONE;
        }

        return block == Blocks.STONE
            || block == Blocks.DEEPSLATE
            || block == Blocks.TUFF
            || block == Blocks.GRANITE
            || block == Blocks.DIORITE
            || block == Blocks.ANDESITE
            || block == Blocks.CALCITE
            || block == Blocks.DRIPSTONE_BLOCK;
    }

    private boolean isCloseTo(BlockPos pos, double distanceSquared) {
        return mc.player != null && mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distanceSquared;
    }

    private void ensurePickaxeSelected() {
        if (mc.player == null || mc.player.getAbilities().instabuild || mc.player.getMainHandItem().is(ItemTags.PICKAXES)) return;

        FindItemResult pickaxe = InvUtils.findInHotbar(stack -> stack.is(ItemTags.PICKAXES));
        if (pickaxe.found() && pickaxe.isHotbar()) InvUtils.swap(pickaxe.slot(), false);
    }

    private int writePredictedMineDebugReport() {
        StringBuilder report = new StringBuilder();
        appendLine(report, "Seed Explorer predicted mining debug");
        appendLine(report, "active=" + predictedMineActive);
        appendLine(report, "searchActive=" + predictedMineSearchActive);
        appendLine(report, "type=" + (predictedMineType == null ? "none" : predictedMineType.name()));
        appendLine(report, "dimension=" + predictedMineDimension + " current=" + (mc.level == null ? "none" : dimensionId()));
        appendLine(report, "index=" + predictedMineIndex + "/" + predictedMineTargets.size());
        appendLine(report, "targetTicks=" + predictedMineTargetTicks);
        appendLine(report, "veinIndex=" + predictedMineVeinIndex + "/" + predictedMineVeinTargets.size());
        appendLine(report, "probeCloseTicks=" + predictedMineProbeCloseTicks);
        appendLine(report, "breakTicks=" + predictedMineBreakTicks);
        appendLine(report, "veinRetryPasses=" + predictedMineVeinRetryPasses + " deferred=" + predictedMineVeinDeferred.size());
        appendLine(report, "pickupActive=" + predictedMinePickupActive + " pickupTicks=" + predictedMinePickupTicks);
        appendLine(report, "pathTargetKey=" + predictedMinePathTargetKey);
        appendLine(report, "breakTargetKey=" + predictedMineBreakTargetKey);
        appendLine(report, "player=" + (mc.player == null ? "none" : formatBlockPos(mc.player.blockPosition())));
        appendLine(report, "baritone=" + baritoneDebugState());

        OrePatch target = currentPredictedMineTarget();
        if (target != null) {
            BlockPos pos = new BlockPos(target.x, target.y, target.z);
            appendLine(report, "currentTarget=" + describeMineTarget(pos));
        }

        if (!predictedMineVeinTargets.isEmpty() && predictedMineVeinIndex >= 0 && predictedMineVeinIndex < predictedMineVeinTargets.size()) {
            appendLine(report, "currentVeinTarget=" + describeMineTarget(predictedMineVeinTargets.get(predictedMineVeinIndex)));
        }

        BlockPos pickup = predictedMinePickupPos == null ? currentPickupTarget() : predictedMinePickupPos;
        if (pickup != null) appendLine(report, "currentPickup=" + describeMineTarget(pickup));

        appendLine(report, "");
        appendLine(report, "recent events:");
        for (String line : predictedMineDebugLines) appendLine(report, line);

        DebugReportWriter.copyAndSave("predicted-mine-debug", report.toString());
        return SINGLE_SUCCESS;
    }

    private String describeMineTarget(BlockPos pos) {
        if (pos == null) return "none";
        BlockState state = isBlockLoaded(pos) ? mc.level.getBlockState(pos) : null;
        BlockPos blocker = targetBlocker(pos);
        return formatBlockPos(pos)
            + " loaded=" + isBlockLoaded(pos)
            + " state=" + (state == null ? "unloaded" : blockStateId(state))
            + " ore=" + (state != null && predictedMineType != null && predictedMineType.matches(state))
            + " dist=" + distanceToPlayer(pos)
            + " canTarget=" + canTargetBlock(pos)
            + " blocker=" + formatBlockPos(blocker)
            + (blocker == null || mc.level == null ? "" : " blockerState=" + blockStateId(mc.level.getBlockState(blocker)));
    }

    private void recordPredictedMineDebugSample(String message) {
        if (predictedMineDebugTicks % MINE_DEBUG_SAMPLE_TICKS == 0) recordPredictedMineDebug(message);
    }

    private void recordPredictedMineDebug(String message) {
        String line = String.format(Locale.ROOT,
            "tick=%d idx=%d vein=%d pickup=%s player=%s baritone=%s %s",
            predictedMineDebugTicks,
            predictedMineIndex,
            predictedMineVeinIndex,
            predictedMinePickupActive,
            mc.player == null ? "none" : formatBlockPos(mc.player.blockPosition()),
            baritoneDebugState(),
            message);
        predictedMineDebugLines.add(line);
        while (predictedMineDebugLines.size() > MINE_DEBUG_MAX_LINES) predictedMineDebugLines.remove(0);
    }

    private String baritoneDebugState() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            Class<?> pathingBehaviorClass = Class.forName("baritone.api.behavior.IPathingBehavior");

            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = providerClass.getMethod("getPrimaryBaritone").invoke(provider);
            Object pathingBehavior = baritoneClass.getMethod("getPathingBehavior").invoke(baritone);
            Object goal = pathingBehaviorClass.getMethod("getGoal").invoke(pathingBehavior);
            boolean pathing = (boolean) pathingBehaviorClass.getMethod("isPathing").invoke(pathingBehavior);
            Object ticks = pathingBehaviorClass.getMethod("estimatedTicksToGoal").invoke(pathingBehavior);
            return "pathing=" + pathing + ",goal=" + goal + ",eta=" + ticks;
        } catch (Throwable throwable) {
            return "unavailable:" + throwable.getClass().getSimpleName();
        }
    }

    private String formatBlockPos(BlockPos pos) {
        if (pos == null) return "none";
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String distanceToPlayer(BlockPos pos) {
        if (mc.player == null || pos == null) return "none";
        return String.format(Locale.ROOT, "%.2f", Math.sqrt(mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
    }

    private int breakStuckTimeoutTicks() {
        return predictedMineType == OreType.ANCIENT_DEBRIS ? ANCIENT_DEBRIS_BREAK_STUCK_TICKS : BREAK_STUCK_TICKS;
    }

    private boolean isBaritonePathing() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            Class<?> pathingBehaviorClass = Class.forName("baritone.api.behavior.IPathingBehavior");

            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = providerClass.getMethod("getPrimaryBaritone").invoke(provider);
            Object pathingBehavior = baritoneClass.getMethod("getPathingBehavior").invoke(baritone);
            return (boolean) pathingBehaviorClass.getMethod("isPathing").invoke(pathingBehavior);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<BlockPos> findConnectedOreBlocks(BlockPos seedPos) {
        List<BlockPos> results = new ArrayList<>();
        List<BlockPos> queue = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        queue.add(seedPos);
        seen.add(seedPos.asLong());

        for (int index = 0; index < queue.size() && results.size() < Math.max(1, predictedMineType.veinSize() * 3); index++) {
            BlockPos pos = queue.get(index);
            if (Math.abs(pos.getX() - seedPos.getX()) > VEIN_SCAN_RADIUS
                || Math.abs(pos.getY() - seedPos.getY()) > VEIN_SCAN_RADIUS
                || Math.abs(pos.getZ() - seedPos.getZ()) > VEIN_SCAN_RADIUS) {
                continue;
            }

            if (!isBlockLoaded(pos)) continue;
            if (!predictedMineType.matches(mc.level.getBlockState(pos))) continue;
            results.add(pos);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (seen.add(next.asLong())) queue.add(next);
                    }
                }
            }
        }

        results.sort(Comparator.comparingLong(pos -> BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ())));
        return results.isEmpty() ? List.of(seedPos) : List.copyOf(results);
    }

    private boolean rescanVisibleVeinOres() {
        if (mc.level == null || predictedMineType == null) return false;

        Set<Long> known = new HashSet<>();
        for (BlockPos pos : predictedMineVeinTargets) known.add(pos.asLong());
        for (BlockPos pos : predictedMineMinedBlocks) known.add(pos.asLong());

        List<BlockPos> anchors = new ArrayList<>();
        anchors.addAll(predictedMineVeinTargets);
        anchors.addAll(predictedMineMinedBlocks);

        List<BlockPos> additions = new ArrayList<>();
        for (BlockPos anchor : anchors) {
            for (int dx = -VEIN_RESCAN_RADIUS; dx <= VEIN_RESCAN_RADIUS; dx++) {
                for (int dy = -VEIN_RESCAN_RADIUS; dy <= VEIN_RESCAN_RADIUS; dy++) {
                    for (int dz = -VEIN_RESCAN_RADIUS; dz <= VEIN_RESCAN_RADIUS; dz++) {
                        BlockPos pos = anchor.offset(dx, dy, dz);
                        long key = pos.asLong();
                        if (!known.add(key)) continue;
                        if (Math.abs(pos.getX() - anchor.getX()) > VEIN_SCAN_RADIUS
                            || Math.abs(pos.getY() - anchor.getY()) > VEIN_SCAN_RADIUS
                            || Math.abs(pos.getZ() - anchor.getZ()) > VEIN_SCAN_RADIUS) {
                            continue;
                        }
                        if (!isBlockLoaded(pos)) continue;
                        if (!predictedMineType.matches(mc.level.getBlockState(pos))) continue;
                        additions.add(pos);
                    }
                }
            }
        }

        if (additions.isEmpty()) return false;

        additions.sort(Comparator.comparingDouble(pos -> mc.player == null ? 0.0 : mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        if (!(predictedMineVeinTargets instanceof ArrayList<?>)) predictedMineVeinTargets = new ArrayList<>(predictedMineVeinTargets);
        predictedMineVeinTargets.addAll(additions);
        recordPredictedMineDebug("vein-rescan added=" + additions.size()
            + " total=" + predictedMineVeinTargets.size()
            + " first=" + formatBlockPos(additions.getFirst()));
        return true;
    }

    private List<BlockPos> findPickupTargets() {
        List<BlockPos> targets = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        BlockPos visibleDrop = nearestPredictedMineDrop();
        if (visibleDrop != null && seen.add(visibleDrop.asLong())) targets.add(visibleDrop);

        for (BlockPos pos : predictedMineMinedBlocks) {
            if (seen.add(pos.asLong())) targets.add(pos);
        }

        return List.copyOf(targets);
    }

    private BlockPos currentPickupTarget() {
        if (predictedMinePickupIndex < 0 || predictedMinePickupIndex >= predictedMinePickupTargets.size()) return null;
        return predictedMinePickupTargets.get(predictedMinePickupIndex);
    }

    private BlockPos nearestPredictedMineDrop() {
        if (predictedMineType == null || mc.level == null || mc.player == null || predictedMineMinedBlocks.isEmpty()) return null;

        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mined : predictedMineMinedBlocks) {
            AABB box = new AABB(mined).inflate(PICKUP_SEARCH_RADIUS);
            for (ItemEntity entity : mc.level.getEntitiesOfClass(ItemEntity.class, box, this::isPredictedMineDrop)) {
                double distance = mc.player.distanceToSqr(entity.position());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entity;
                }
            }
        }

        return best == null ? null : best.blockPosition();
    }

    private boolean isPredictedMineDrop(ItemEntity entity) {
        if (entity == null || entity.getItem().isEmpty() || predictedMineType == null) return false;
        return dropItemsFor(predictedMineType).contains(entity.getItem().getItem());
    }

    private Set<Item> dropItemsFor(OreType type) {
        return switch (type) {
            case DIAMOND -> Set.of(Items.DIAMOND, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE);
            case EMERALD -> Set.of(Items.EMERALD, Items.EMERALD_ORE, Items.DEEPSLATE_EMERALD_ORE);
            case COAL -> Set.of(Items.COAL, Items.COAL_ORE, Items.DEEPSLATE_COAL_ORE);
            case IRON -> Set.of(Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE);
            case COPPER -> Set.of(Items.RAW_COPPER, Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE);
            case GOLD -> Set.of(Items.RAW_GOLD, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE);
            case LAPIS -> Set.of(Items.LAPIS_LAZULI, Items.LAPIS_ORE, Items.DEEPSLATE_LAPIS_ORE);
            case REDSTONE -> Set.of(Items.REDSTONE, Items.REDSTONE_ORE, Items.DEEPSLATE_REDSTONE_ORE);
            case QUARTZ -> Set.of(Items.QUARTZ, Items.NETHER_QUARTZ_ORE);
            case NETHER_GOLD -> Set.of(Items.GOLD_NUGGET, Items.NETHER_GOLD_ORE);
            case ANCIENT_DEBRIS -> Set.of(Items.ANCIENT_DEBRIS);
        };
    }

    private void markCurrentPredictedOreCleared() {
        OrePatch target = currentPredictedMineTarget();
        if (target == null) return;

        int cleared = SeedManager.get().markClearedOrePatches(predictedMineDimension, List.of(target));
        if (cleared > 0) {
            info("Mined predicted %s at %d, %d, %d.",
                target.type.displayName,
                target.x,
                target.y,
                target.z);
        }
    }

    private void markOreCleared(BlockPos pos) {
        OrePatch patch = new OrePatch(pos.getX(), pos.getY(), pos.getZ(), predictedMineType, true);
        int cleared = SeedManager.get().markClearedOrePatches(predictedMineDimension, List.of(patch));
        if (cleared > 0) {
            info("Mined predicted %s at %d, %d, %d.",
                predictedMineType.displayName,
                pos.getX(),
                pos.getY(),
                pos.getZ());
        }
    }

    private void advancePredictedMineTarget() {
        predictedMineIndex++;
        predictedMineBreaking = false;
        predictedMineTargetTicks = 0;
        predictedMineVeinIndex = 0;
        predictedMinePickupTicks = 0;
        predictedMineProbeCloseTicks = 0;
        predictedMineBreakTicks = 0;
        predictedMineVeinRetryPasses = 0;
        predictedMineVeinTargets = List.of();
        predictedMinePickupPos = null;
        predictedMinePickupActive = false;
        predictedMineVeinDeferred.clear();

        if (predictedMineIndex >= predictedMineTargets.size()) {
            finishPredictedMineSession();
            return;
        }

        pathToCurrentPredictedOre();
    }

    private void finishPredictedMineSession() {
        int total = predictedMineTargets.size();
        OreType type = predictedMineType;
        clearPredictedMineSession();
        cancelBaritonePathing(false);
        if (type != null) info("Finished predicted %s mining queue: %d target%s.", type.displayName, total, total == 1 ? "" : "s");
    }

    private void stopPredictedMineSession(boolean announce) {
        OreType type = predictedMineType;
        int remaining = Math.max(0, predictedMineTargets.size() - predictedMineIndex);
        clearPredictedMineSession();
        cancelBaritonePathing(false);
        if (announce && type != null) info("Stopped predicted %s mining queue with %d target%s remaining.", type.displayName, remaining, remaining == 1 ? "" : "s");
        else if (announce) info("Stopped predicted mining search.");
    }

    private void clearPredictedMineSession() {
        predictedMineTargets = List.of();
        predictedMineType = null;
        predictedMineDimension = 0;
        predictedMineIndex = 0;
        predictedMinePathTargetKey = Long.MIN_VALUE;
        predictedMinePathRefreshTicks = 0;
        predictedMineTargetTicks = 0;
        predictedMineVeinIndex = 0;
        predictedMinePickupTicks = 0;
        predictedMineProbeCloseTicks = 0;
        predictedMineBreakTicks = 0;
        predictedMineVeinRetryPasses = 0;
        predictedMineVeinTargets = List.of();
        predictedMinePickupTargets = List.of();
        predictedMinePickupIndex = 0;
        predictedMineMinedBlocks.clear();
        predictedMineVeinDeferred.clear();
        predictedMinePickupPos = null;
        predictedMineBreakTargetKey = Long.MIN_VALUE;
        predictedMineActive = false;
        predictedMineBreaking = false;
        predictedMinePickupActive = false;
        predictedMineSearchActive = false;
    }

    private List<OrePatch> filterMineTargets(List<OrePatch> candidates, int dimension, int maxTargets) {
        List<OrePatch> targets = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (OrePatch patch : candidates) {
            if (SeedManager.get().isClearedOre(patch, dimension)) continue;

            long key = BlockPos.asLong(patch.x, patch.y, patch.z);
            if (!seen.add(key)) continue;

            targets.add(patch);
            if (targets.size() >= maxTargets) break;
        }
        return targets;
    }

    private OreType parseOreType(String oreName) {
        String normalized = oreName.toLowerCase(Locale.ROOT)
            .replace("minecraft:", "")
            .replace("-", "_")
            .replace(" ", "_");
        if (normalized.endsWith("_ore")) normalized = normalized.substring(0, normalized.length() - 4);
        if (normalized.endsWith("_ores")) normalized = normalized.substring(0, normalized.length() - 5);

        return switch (normalized) {
            case "diamond", "diamonds" -> OreType.DIAMOND;
            case "emerald", "emeralds" -> OreType.EMERALD;
            case "coal" -> OreType.COAL;
            case "iron" -> OreType.IRON;
            case "copper" -> OreType.COPPER;
            case "gold" -> OreType.GOLD;
            case "lapis", "lapis_lazuli" -> OreType.LAPIS;
            case "redstone" -> OreType.REDSTONE;
            case "quartz", "nether_quartz" -> OreType.QUARTZ;
            case "nether_gold", "nethergold" -> OreType.NETHER_GOLD;
            case "ancient_debris", "debris" -> OreType.ANCIENT_DEBRIS;
            default -> null;
        };
    }

    private BaritoneResult startBaritonePathing(List<OrePatch> targets) {
        return startBaritonePathing(targets, 0);
    }

    private BaritoneResult startBaritonePathing(List<OrePatch> targets, int goalRadius) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> getToBlockGoalClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
            Class<?> nearGoalClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Class<?> compositeGoalClass = Class.forName("baritone.api.pathing.goals.GoalComposite");
            Class<?> customGoalProcessClass = Class.forName("baritone.api.process.ICustomGoalProcess");

            Object provider = apiClass.getMethod("getProvider").invoke(null);
            if (provider == null) return new BaritoneResult(false, "Baritone is not available.");

            Object baritone = providerClass.getMethod("getPrimaryBaritone").invoke(provider);
            if (baritone == null) return new BaritoneResult(false, "Baritone is not available.");

            Object goal = createBaritoneGoal(targets, goalClass, getToBlockGoalClass, nearGoalClass, compositeGoalClass, goalRadius);
            Object customGoalProcess = baritoneClass.getMethod("getCustomGoalProcess").invoke(baritone);
            customGoalProcessClass.getMethod("setGoalAndPath", goalClass).invoke(customGoalProcess, goal);
            return new BaritoneResult(true, "started");
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return new BaritoneResult(false, "Baritone is not installed or not loaded.");
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return new BaritoneResult(false, "Could not start Baritone pathing: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Throwable throwable) {
            return new BaritoneResult(false, "Could not start Baritone pathing: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private Object createBaritoneGoal(List<OrePatch> targets, Class<?> goalClass, Class<?> getToBlockGoalClass, Class<?> nearGoalClass,
                                      Class<?> compositeGoalClass, int goalRadius)
        throws ReflectiveOperationException {
        if (targets.size() == 1) {
            OrePatch patch = targets.getFirst();
            return createSingleBaritoneGoal(patch, getToBlockGoalClass, nearGoalClass, goalRadius);
        }

        Object goalArray = Array.newInstance(goalClass, targets.size());
        for (int i = 0; i < targets.size(); i++) {
            OrePatch patch = targets.get(i);
            Object goal = createSingleBaritoneGoal(patch, getToBlockGoalClass, nearGoalClass, goalRadius);
            Array.set(goalArray, i, goal);
        }

        return compositeGoalClass.getConstructor(goalArray.getClass()).newInstance(new Object[] { goalArray });
    }

    private Object createSingleBaritoneGoal(OrePatch patch, Class<?> getToBlockGoalClass, Class<?> nearGoalClass, int goalRadius)
        throws ReflectiveOperationException {
        BlockPos pos = new BlockPos(patch.x, patch.y, patch.z);
        if (goalRadius > 0) return nearGoalClass.getConstructor(BlockPos.class, int.class).newInstance(pos, goalRadius);
        return getToBlockGoalClass.getConstructor(BlockPos.class).newInstance(pos);
    }

    private int stopBaritonePathing() {
        return stopBaritonePathing(true);
    }

    private int stopBaritonePathing(boolean announce) {
        minePredictionJobIds.incrementAndGet();
        boolean hadPredictedMineWork = predictedMineActive || predictedMineSearchActive;
        predictedMineSearchActive = false;
        if (hadPredictedMineWork) {
            stopPredictedMineSession(announce);
            return SINGLE_SUCCESS;
        }

        return cancelBaritonePathing(announce);
    }

    private int cancelBaritonePathing(boolean announce) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
            Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
            Class<?> pathingBehaviorClass = Class.forName("baritone.api.behavior.IPathingBehavior");
            Class<?> mineProcessClass = Class.forName("baritone.api.process.IMineProcess");
            Class<?> inputOverrideHandlerClass = Class.forName("baritone.api.utils.IInputOverrideHandler");

            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = providerClass.getMethod("getPrimaryBaritone").invoke(provider);
            Object pathingBehavior = baritoneClass.getMethod("getPathingBehavior").invoke(baritone);
            Object mineProcess = baritoneClass.getMethod("getMineProcess").invoke(baritone);
            Object inputOverrideHandler = baritoneClass.getMethod("getInputOverrideHandler").invoke(baritone);
            mineProcessClass.getMethod("cancel").invoke(mineProcess);
            pathingBehaviorClass.getMethod("cancelEverything").invoke(pathingBehavior);
            inputOverrideHandlerClass.getMethod("clearAllKeys").invoke(inputOverrideHandler);
            if (announce) info("Stopped Baritone pathing.");
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            if (announce) error("Baritone is not installed or not loaded.");
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (announce) error("Could not stop Baritone: %s: %s", cause.getClass().getSimpleName(), cause.getMessage());
        } catch (Throwable throwable) {
            if (announce) error("Could not stop Baritone: %s: %s", throwable.getClass().getSimpleName(), throwable.getMessage());
        }
        return SINGLE_SUCCESS;
    }

    private List<OrePatch> scanLoadedChunk(LevelChunk chunk, OreType type) {
        List<OrePatch> results = new ArrayList<>();
        int minY = Math.max(mc.level.getMinY(), type.minY);
        int maxY = Math.min(mc.level.getMaxY() - 1, type.maxY);
        if (minY > maxY) return results;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(baseX + x, y, baseZ + z);
                    if (type.matches(chunk.getBlockState(pos))) {
                        results.add(new OrePatch(pos.getX(), pos.getY(), pos.getZ(), type, true));
                    }
                }
            }
        }
        return results;
    }

    private boolean inChunkBounds(OrePatch patch, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        int chunkX = Math.floorDiv(patch.x, 16);
        int chunkZ = Math.floorDiv(patch.z, 16);
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    private MatchSummary compareOre(List<OrePatch> actual, List<OrePatch> predicted, int tolerance) {
        Set<Integer> usedPredicted = new HashSet<>();
        List<OrePatch> matchedPredicted = new ArrayList<>();
        List<OrePatch> missingActual = new ArrayList<>();
        int matchedActual = 0;

        for (OrePatch actualPatch : actual) {
            int bestIndex = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < predicted.size(); i++) {
                if (usedPredicted.contains(i)) continue;
                OrePatch predictedPatch = predicted.get(i);
                int distance = manhattan(actualPatch, predictedPatch);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0 && bestDistance <= tolerance) {
                usedPredicted.add(bestIndex);
                matchedPredicted.add(predicted.get(bestIndex));
                matchedActual++;
            } else {
                missingActual.add(actualPatch);
            }
        }

        List<OrePatch> extraPredicted = new ArrayList<>();
        for (int i = 0; i < predicted.size(); i++) {
            if (!usedPredicted.contains(i)) extraPredicted.add(predicted.get(i));
        }

        return new MatchSummary(matchedActual, matchedPredicted, missingActual, extraPredicted);
    }

    private int manhattan(OrePatch a, OrePatch b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
    }

    private void appendOreList(StringBuilder report, String label, List<OrePatch> patches, int limit) {
        appendLine(report, label + "_count=" + patches.size());
        int count = Math.min(limit, patches.size());
        for (int i = 0; i < count; i++) {
            OrePatch patch = patches.get(i);
            appendLine(report, "  " + patch.type.displayName + " " + patch.x + "," + patch.y + "," + patch.z
                + " chunk=" + Math.floorDiv(patch.x, 16) + "," + Math.floorDiv(patch.z, 16)
                + " source=" + patch.source);
        }
        if (patches.size() > limit) {
            appendLine(report, "  ... " + (patches.size() - limit) + " more");
        }
    }

    private void appendSourceSummary(StringBuilder report, String label, List<OrePatch> patches) {
        appendLine(report, label + "=");
        if (patches.isEmpty()) {
            appendLine(report, "  none");
            return;
        }

        patches.stream()
            .collect(java.util.stream.Collectors.groupingBy(patch -> patch.source, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
            .forEach((source, count) -> appendLine(report, "  " + source + "=" + count));
    }

    private void appendYHistogram(StringBuilder report, String label, List<OrePatch> patches) {
        appendLine(report, label + "=");
        if (patches.isEmpty()) {
            appendLine(report, "  none");
            return;
        }

        patches.stream()
            .collect(java.util.stream.Collectors.groupingBy(patch -> Math.floorDiv(patch.y, 16) * 16, java.util.TreeMap::new, java.util.stream.Collectors.counting()))
            .forEach((bucket, count) -> appendLine(report, "  " + bucket + ".." + (bucket + 15) + "=" + count));
    }

    private void appendBlockStateDiagnostics(StringBuilder report, String label, List<OrePatch> patches,
                                             boolean includeRuntime, long seed, int dimension, int sampleLimit) {
        appendLine(report, label + "=");
        if (patches.isEmpty()) {
            appendLine(report, "  none");
            return;
        }

        Map<String, Long> predictedBaseCounts = patches.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                patch -> blockStateId(WorldgenEngine.baseBlock(seed, dimension, patch.x, patch.y, patch.z)),
                java.util.TreeMap::new,
                java.util.stream.Collectors.counting()
            ));
        appendLine(report, "  predicted_base=");
        predictedBaseCounts.forEach((block, count) -> appendLine(report, "    " + block + "=" + count));

        if (includeRuntime) {
            Map<String, Long> runtimeCounts = patches.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    patch -> blockStateId(runtimeBlock(patch.x, patch.y, patch.z)),
                    java.util.TreeMap::new,
                    java.util.stream.Collectors.counting()
                ));
            appendLine(report, "  runtime=");
            runtimeCounts.forEach((block, count) -> appendLine(report, "    " + block + "=" + count));
        }

        appendLine(report, "  samples=");
        int count = Math.min(sampleLimit, patches.size());
        for (int i = 0; i < count; i++) {
            OrePatch patch = patches.get(i);
            String line = "    " + patch.x + "," + patch.y + "," + patch.z
                + " predicted_base=" + blockStateId(WorldgenEngine.baseBlock(seed, dimension, patch.x, patch.y, patch.z));
            if (includeRuntime) {
                line += " runtime=" + blockStateId(runtimeBlock(patch.x, patch.y, patch.z));
            }
            appendLine(report, line);
        }
        if (patches.size() > sampleLimit) appendLine(report, "    ... " + (patches.size() - sampleLimit) + " more");
    }

    private BlockState runtimeBlock(int x, int y, int z) {
        if (mc.level == null || y < mc.level.getMinY() || y >= mc.level.getMaxY()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return mc.level.getBlockState(new BlockPos(x, y, z));
    }

    private String blockStateId(BlockState state) {
        if (state == null) return "unknown";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private int runLocateBatch() {
        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }

        if (dimensionId() != 0) {
            error("Locate batch only supports the Overworld right now.");
            return SINGLE_SUCCESS;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> first.");
            return SINGLE_SUCCESS;
        }

        if (LocateBatchRunner.get().isRunning()) {
            warning("A locate batch is already running. Use .seed-explorer locate-batch stop first.");
            return SINGLE_SUCCESS;
        }

        LocateBatchRunner.get().start(seed, OVERWORLD_LOCATE_BATCH);
        return SINGLE_SUCCESS;
    }

    private int runValidateOverworld() {
        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }

        if (dimensionId() != 0) {
            error("Overworld validation only supports the Overworld right now.");
            return SINGLE_SUCCESS;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> first.");
            return SINGLE_SUCCESS;
        }

        if (LocateBatchRunner.get().isRunning()) {
            warning("A locate/validation batch is already running. Use .seed-explorer validate-overworld stop first.");
            return SINGLE_SUCCESS;
        }

        LocateBatchRunner.get().start(
            seed,
            "validate-overworld",
            "Seed Explorer Overworld validation scan",
            mc.player.blockPosition(),
            overworldValidationTargets()
        );
        return SINGLE_SUCCESS;
    }

    private List<LocateBatchRunner.Target> overworldValidationTargets() {
        List<LocateBatchRunner.Target> targets = new ArrayList<>();
        targets.add(target("Village Plains", "minecraft:village_plains", true, "Villages"));
        targets.add(target("Village Desert", "minecraft:village_desert", true, "Villages"));
        targets.add(target("Village Savanna", "minecraft:village_savanna", true, "Villages"));
        targets.add(target("Village Snowy", "minecraft:village_snowy", true, "Villages"));
        targets.add(target("Village Taiga", "minecraft:village_taiga", true, "Villages"));
        targets.add(target("Pillager Outpost", "minecraft:pillager_outpost", true, ""));
        targets.add(target("Woodland Mansion", "minecraft:mansion", true, ""));
        targets.add(target("Desert Pyramid", "minecraft:desert_pyramid", true, ""));
        targets.add(target("Jungle Pyramid", "minecraft:jungle_pyramid", true, ""));
        targets.add(target("Swamp Hut", "minecraft:swamp_hut", true, ""));
        targets.add(target("Igloo", "minecraft:igloo", true, ""));
        targets.add(target("Trial Chambers", "minecraft:trial_chambers", true, ""));
        targets.add(target("Ancient City", "minecraft:ancient_city", true, ""));
        targets.add(target("Stronghold", "minecraft:stronghold", true, "Strongholds"));
        targets.add(target("Mineshaft", "minecraft:mineshaft", true, "validation only: dense placement"));
        targets.add(target("Mineshaft Mesa", "minecraft:mineshaft_mesa", true, "validation only: dense placement"));
        targets.add(skip("Dungeon", "not locatable with /locate structure; needs monster-room feature scan"));
        targets.add(target("Ocean Monument", "minecraft:monument", true, ""));
        targets.add(target("Shipwreck", "minecraft:shipwreck", true, ""));
        targets.add(target("Shipwreck Beached", "minecraft:shipwreck_beached", true, ""));
        targets.add(target("Ocean Ruin Cold", "minecraft:ocean_ruin_cold", true, ""));
        targets.add(target("Ocean Ruin Warm", "minecraft:ocean_ruin_warm", true, ""));
        targets.add(target("Buried Treasure", "minecraft:buried_treasure", true, ""));
        targets.add(target("Trail Ruins", "minecraft:trail_ruins", true, ""));
        targets.add(target("Ruined Portal", "minecraft:ruined_portal", true, ""));
        targets.add(target("Ruined Portal Desert", "minecraft:ruined_portal_desert", true, ""));
        targets.add(target("Ruined Portal Jungle", "minecraft:ruined_portal_jungle", true, ""));
        targets.add(target("Ruined Portal Swamp", "minecraft:ruined_portal_swamp", true, ""));
        targets.add(target("Ruined Portal Mountain", "minecraft:ruined_portal_mountain", true, ""));
        targets.add(target("Ruined Portal Ocean", "minecraft:ruined_portal_ocean", true, ""));
        targets.add(skip("Amethyst Geode", "not locatable with /locate structure; needs configured-feature geode scan"));
        return targets;
    }

    private LocateBatchRunner.Target target(String name, String structureId, boolean comparePrediction, String note) {
        return new LocateBatchRunner.Target(name, structureId, comparePrediction, note);
    }

    private LocateBatchRunner.Target skip(String name, String note) {
        return new LocateBatchRunner.Target(name, "", false, note);
    }

    private int runLootPredictionTest(int level) {
        if (mc.player == null || mc.level == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> first.");
            return SINGLE_SUCCESS;
        }

        int dimension = dimensionId();
        BlockPos playerPos = mc.player.blockPosition();
        int playerChunkX = Math.floorDiv(playerPos.getX(), 16);
        int playerChunkZ = Math.floorDiv(playerPos.getZ(), 16);
        int radiusChunks = switch (level) {
            case 1 -> 96;
            case 2 -> 160;
            default -> 240;
        };
        int maxStructures = switch (level) {
            case 1 -> 24;
            case 2 -> 48;
            default -> 96;
        };

        info("Seed Explorer: starting loot prediction test level (highlight)%d(default), radius (highlight)%d(default) chunks.", level, radiusChunks);
        PredictionDebugLogger.append("event=loot_prediction_test_start seed=" + seed
            + " dimension=" + dimension
            + " level=" + level
            + " radiusChunks=" + radiusChunks
            + " player=" + playerPos.getX() + "," + playerPos.getY() + "," + playerPos.getZ()
            + "\n");

        WorkerManager.get().submit(() -> {
            int structuresChecked = 0;
            int chestsChecked = 0;
            int loadedChests = 0;
            int mismatches = 0;
            try {
                List<GeneratedStructure> structures = VanillaStructurePredictor.predictDimension(
                    seed,
                    dimension,
                    playerChunkX - radiusChunks,
                    playerChunkZ - radiusChunks,
                    playerChunkX + radiusChunks,
                    playerChunkZ + radiusChunks,
                    true,
                    true
                ).stream()
                    .filter(s -> ChestLootPredictor.canPredict(s.type))
                    .sorted(Comparator.comparingLong(s -> distanceSquared(playerPos.getX(), playerPos.getZ(), s.x, s.z)))
                    .limit(maxStructures)
                    .toList();

                for (GeneratedStructure structure : structures) {
                    try {
                        List<ChestLootOutput> predicted = ChestLootPredictor.predictForStructure(structure);
                        structuresChecked++;
                        int sampleCount = sampleChestCount(predicted.size());
                        PredictionDebugLogger.append("event=loot_prediction_test_structure"
                            + " seed=" + seed
                            + " dimension=" + dimension
                            + " type=" + structure.type
                            + " variant=\"" + structure.variant.replace("\"", "\\\"") + "\""
                            + " block=" + structure.x + "," + structure.z
                            + " startChunk=" + structure.startChunkX + "," + structure.startChunkZ
                            + " predictedChests=" + predicted.size()
                            + " sampledChests=" + sampleCount
                            + "\n");

                        for (int i = 0; i < sampleCount; i++) {
                            ChestLootOutput chest = predicted.get(sampleChestIndex(i, sampleCount, predicted.size()));
                            ChestCompareResult result = compareLoadedChest(chest);
                            chestsChecked++;
                            if (result.loaded()) loadedChests++;
                            if (result.mismatch()) mismatches++;
                            PredictionDebugLogger.append("event=loot_prediction_test_chest"
                                + " type=" + structure.type
                                + " chest=" + chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()
                                + " table=" + chest.lootTableId()
                                + " accuracy=" + (chest.exact() ? "exact" : "approx")
                                + " loaded=" + result.loaded()
                                + " mismatch=" + result.mismatch()
                                + " predicted=\"" + result.predictedSummary().replace("\"", "\\\"") + "\""
                                + " actual=\"" + result.actualSummary().replace("\"", "\\\"") + "\""
                                + "\n");
                        }
                    } catch (Throwable throwable) {
                        PredictionDebugLogger.append("event=loot_prediction_test_structure_error"
                            + " type=" + structure.type
                            + " variant=\"" + structure.variant.replace("\"", "\\\"") + "\""
                            + " block=" + structure.x + "," + structure.z
                            + " startChunk=" + structure.startChunkX + "," + structure.startChunkZ
                            + " error=\"" + throwable.getClass().getName() + ": "
                            + String.valueOf(throwable.getMessage()).replace("\"", "\\\"") + "\""
                            + "\n");
                    }
                }
            } catch (Throwable throwable) {
                PredictionDebugLogger.append("event=loot_prediction_test_error error=\""
                    + throwable.getClass().getName() + ": "
                    + String.valueOf(throwable.getMessage()).replace("\"", "\\\"")
                    + "\"\n");
            }

            int finalStructuresChecked = structuresChecked;
            int finalChestsChecked = chestsChecked;
            int finalLoadedChests = loadedChests;
            int finalMismatches = mismatches;
            PredictionDebugLogger.append("event=loot_prediction_test_complete"
                + " structures=" + finalStructuresChecked
                + " chests=" + finalChestsChecked
                + " loaded=" + finalLoadedChests
                + " mismatches=" + finalMismatches
                + "\n");
            MeteorClient.mc.execute(() -> info("Seed Explorer: loot test complete. Structures (highlight)%d(default), sampled chests (highlight)%d(default), loaded (highlight)%d(default), mismatches (highlight)%d(default). See prediction-debug.log.",
                finalStructuresChecked, finalChestsChecked, finalLoadedChests, finalMismatches));
        });

        return SINGLE_SUCCESS;
    }

    private int sampleChestCount(int predictedCount) {
        if (predictedCount <= 0) return 0;
        if (predictedCount <= 2) return predictedCount;
        return Math.max(1, Math.min(12, (predictedCount + 1) / 2));
    }

    private int sampleChestIndex(int sampleIndex, int sampleCount, int totalCount) {
        if (sampleCount >= totalCount) return sampleIndex;
        return Math.min(totalCount - 1, (int) Math.round(sampleIndex * (totalCount - 1) / (double) Math.max(1, sampleCount - 1)));
    }

    private ChestCompareResult compareLoadedChest(ChestLootOutput chest) {
        Map<String, Integer> predicted = predictedCounts(chest);
        String predictedSummary = countSummary(predicted);
        if (mc.level == null) return new ChestCompareResult(false, false, predictedSummary, "no-client-level");

        BlockPos pos = new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
        if (!mc.level.hasChunkAt(pos)) {
            return new ChestCompareResult(false, false, predictedSummary, "unloaded");
        }

        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            String block = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
            return new ChestCompareResult(false, false, predictedSummary, "not-container:" + block);
        }

        if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
            return new ChestCompareResult(false, false, predictedSummary,
                "unopened-loot-container:" + randomizable.getLootTable().identifier() + " seed=" + randomizable.getLootTableSeed());
        }

        Map<String, Integer> actual = actualCounts(container);
        if (actual.isEmpty() && !predicted.isEmpty()) {
            return new ChestCompareResult(false, false, predictedSummary, "empty-client-inventory-unverified");
        }
        boolean mismatch = !predicted.equals(actual);
        return new ChestCompareResult(true, mismatch, predictedSummary, countSummary(actual));
    }

    private Map<String, Integer> predictedCounts(ChestLootOutput chest) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (chest.predictedItems() == null) return counts;
        for (ItemLoot item : chest.predictedItems()) {
            int count = Math.max(0, item.maxCount());
            if (count == 0) continue;
            counts.merge(item.itemId(), count, Integer::sum);
        }
        return counts;
    }

    private Map<String, Integer> actualCounts(Container container) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private String countSummary(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        counts.forEach((item, count) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(count).append('x').append(item);
        });
        return sb.toString();
    }

    private record ChestCompareResult(boolean loaded, boolean mismatch, String predictedSummary, String actualSummary) {
    }

    private int runTeleportLootTest(int level) {
        return runTeleportLootTest(level, List.of(0, -1, 1), true, false, "start");
    }

    private int runTeleportLootTest(int level, List<Integer> dimensions, boolean endCityQuota, String modeLabel) {
        return runTeleportLootTest(level, dimensions, endCityQuota, false, modeLabel);
    }

    private int runTeleportLootTest(int level, List<Integer> dimensions, boolean endCityQuota, boolean fullCoverage, String modeLabel) {
        return runTeleportLootTest(level, dimensions, endCityQuota, fullCoverage, false, modeLabel);
    }

    private int runTeleportLootTest(int level, List<Integer> dimensions, boolean endCityQuota, boolean fullCoverage,
                                    boolean exhaustive, String modeLabel) {
        if (mc.player == null || mc.level == null || mc.player.connection == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> first.");
            return SINGLE_SUCCESS;
        }

        if (tpLootRunning || tpLootPreparing) {
            warning("A teleport loot test is already running. Use .seed-explorer test-tp stop first.");
            return SINGLE_SUCCESS;
        }

        int centerChunkX = Math.floorDiv(mc.player.blockPosition().getX(), 16);
        int centerChunkZ = Math.floorDiv(mc.player.blockPosition().getZ(), 16);
        int radiusChunks = switch (level) {
            case 1 -> 96;
            case 2 -> 160;
            default -> 240;
        };
        int maxStructuresPerDimension = switch (level) {
            case 1 -> 16;
            case 2 -> 32;
            default -> fullCoverage ? TP_LOOT_FULL_MAX_STRUCTURE_PROBES : 64;
        };

        info("Seed Explorer: building teleport loot test from Seed Explorer predictions (%s, %s coverage)...",
            modeLabel, exhaustive ? "exhaustive" : fullCoverage ? "full" : "nearby");
        info("Seed Explorer TP test: preparing targets. This can take a bit on full coverage; progress will be printed here.");
        PredictionDebugLogger.append("event=loot_tp_test_prepare seed=" + seed
            + " mode=\"" + modeLabel.replace("\"", "\\\"") + "\""
            + " fullCoverage=" + fullCoverage
            + " exhaustive=" + exhaustive
            + " dimensions=\"" + dimensions + "\""
            + " level=" + level
            + " radiusChunks=" + radiusChunks
            + " centerChunk=" + centerChunkX + "," + centerChunkZ
            + "\n");

        tpLootPreparing = true;
        tpLootTargetsComplete = false;
        tpLootStartRequested = false;
        tpLootPrepareStatus = "starting target build";
        tpLootPrepareLastNoticeMs = 0L;
        int prepareJobId = tpLootPrepareJobIds.incrementAndGet();
        tpLootTargets = new CopyOnWriteArrayList<>();
        tpLootReport = new StringBuilder();
        appendLine(tpLootReport, "Seed Explorer teleport loot full chest audit");
        appendLine(tpLootReport, "seed=" + seed);
        appendLine(tpLootReport, "mode=" + modeLabel + (exhaustive ? ":exhaustive" : fullCoverage ? ":full" : ":nearby"));
        appendLine(tpLootReport, "level=" + level);
        appendLine(tpLootReport, "dimensions=" + dimensions);
        appendLine(tpLootReport, "radiusChunks=" + radiusChunks);
        appendLine(tpLootReport, "exhaustive=" + exhaustive);
        appendLine(tpLootReport, "status=preparing");
        appendLine(tpLootReport, "");
        WorkerManager.get().submit(() -> {
            buildTeleportLootTargets(seed, centerChunkX, centerChunkZ, radiusChunks,
                maxStructuresPerDimension, dimensions, endCityQuota, fullCoverage, exhaustive, level, prepareJobId, modeLabel);
            MeteorClient.mc.execute(() -> {
                if (prepareJobId != tpLootPrepareJobIds.get() || !tpLootPreparing) return;
                tpLootPreparing = false;
                tpLootPrepareStatus = "";
                tpLootPrepareLastNoticeMs = 0L;
                tpLootTargetsComplete = true;
                if (tpLootTargets.isEmpty()) {
                    warning("Seed Explorer: no predicted loot chests found for teleport test.");
                    PredictionDebugLogger.append("event=loot_tp_test_empty\n");
                    tpLootTargets = List.of();
                    return;
                }
                if (!tpLootRunning) {
                    startTeleportLootRun(seed, modeLabel);
                }
            });
        });

        return SINGLE_SUCCESS;
    }

    private void buildTeleportLootTargets(long seed, int centerChunkX, int centerChunkZ,
                                          int radiusChunks, int maxStructuresPerDimension,
                                          List<Integer> dimensions, boolean endCityQuota,
                                          boolean fullCoverage, boolean exhaustive, int level,
                                          int prepareJobId, String modeLabel) {
        for (int dimension : dimensions) {
            int dimCenterChunkX = fullCoverage ? fullCoverageCenterChunkX(seed, dimension) : centerChunkX;
            int dimCenterChunkZ = fullCoverage ? fullCoverageCenterChunkZ(seed, dimension) : centerChunkZ;
            int dimRadius = fullCoverage ? fullCoverageRadiusChunks(level, dimension) : radiusChunks;
            updateTpLootPrepareStatus("scanning " + dimensionName(dimension)
                + " predictions, center chunk " + dimCenterChunkX + "," + dimCenterChunkZ
                + ", radius " + dimRadius + " chunks");
            int before = tpLootTargets.size();
            int added = addTeleportLootTargetsForDimension(seed, dimension, dimCenterChunkX, dimCenterChunkZ, dimRadius,
                maxStructuresPerDimension, endCityQuota && dimension == 1, fullCoverage, exhaustive, prepareJobId, modeLabel);
            updateTpLootPrepareStatus("finished " + dimensionName(dimension) + " target build, added "
                + added + " chest targets (" + tpLootTargets.size() + " total)");
        }
    }

    private int fullCoverageRadiusChunks(int level, int dimension) {
        return switch (level) {
            case 1 -> dimension == 1 ? 640 : 384;
            case 2 -> dimension == 1 ? 896 : 512;
            default -> dimension == 1 ? 1280 : 640;
        };
    }

    private int fullCoverageCenterChunkX(long seed, int dimension) {
        long mixed = mix64(seed ^ ((long) dimension * 0x9E3779B97F4A7C15L) ^ 0x632BE5AB9C35A1B5L);
        return (int) Math.floorMod(mixed, 768L) - 384;
    }

    private int fullCoverageCenterChunkZ(long seed, int dimension) {
        long mixed = mix64(seed ^ ((long) dimension * 0xC2B2AE3D27D4EB4FL) ^ 0x94D049BB133111EBL);
        return (int) Math.floorMod(mixed, 768L) - 384;
    }

    private long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private int addTeleportLootTargetsForDimension(long seed, int dimension,
                                                   int centerChunkX, int centerChunkZ, int radiusChunks,
                                                   int maxStructures, boolean endCityQuota, boolean fullCoverage,
                                                   boolean exhaustive, int prepareJobId, String modeLabel) {
        List<GeneratedStructure> structures = VanillaStructurePredictor.predictDimension(
            seed,
            dimension,
            centerChunkX - radiusChunks,
            centerChunkZ - radiusChunks,
            centerChunkX + radiusChunks,
            centerChunkZ + radiusChunks,
            true,
            true
        ).stream()
            .filter(s -> ChestLootPredictor.canPredict(s.type))
            .sorted(Comparator.comparingLong(s -> distanceSquared(centerChunkX * 16, centerChunkZ * 16, s.x, s.z)))
            .toList();

        updateTpLootPrepareStatus("found " + structures.size() + " predicted loot-capable structures in "
            + dimensionName(dimension) + "; checking up to "
            + (endCityQuota ? "4 End Cities (2 ship, 2 no ship)"
            : exhaustive ? "one of each structure variant"
            : fullCoverage ? "1 targetable structure"
            : maxStructures + " structures"));

        if (endCityQuota && !exhaustive) {
            int ships = 0;
            int normal = 0;
            int checked = 0;
            int added = 0;
            for (GeneratedStructure structure : structures) {
                if (structure.type != me.seedexplorer.addon.structures.StructureType.END_CITY) continue;
                checked++;
                if (structure.hasShip && ships >= TP_LOOT_END_CITY_SHIP_TARGETS) continue;
                if (!structure.hasShip && normal >= TP_LOOT_END_CITY_NO_SHIP_TARGETS) continue;
                updateTpLootPrepareStatus("predicting End City " + checked + ": "
                    + (structure.hasShip ? "ship" : "no ship")
                    + " at " + structure.x + "," + structure.z);
                int structureAdded = addAllPredictedChests(seed, dimension, structure, prepareJobId, modeLabel);
                added += structureAdded;
                if (structureAdded > 0) {
                    if (structure.hasShip) ships++;
                    else normal++;
                }
                if (ships >= TP_LOOT_END_CITY_SHIP_TARGETS && normal >= TP_LOOT_END_CITY_NO_SHIP_TARGETS) break;
            }
            return added;
        }

        if (exhaustive) {
            if (dimension == 1) {
                int ships = 0;
                int normal = 0;
                int added = 0;
                int totalChecked = 0;
                // Hard cap: stop after checking this many End Cities so the build phase never
                // runs indefinitely when predictions return empty or one variant is rare.
                final int MAX_END_CITY_CHECKS = 30;
                for (GeneratedStructure structure : structures) {
                    if (totalChecked >= MAX_END_CITY_CHECKS) break;
                    if (structure.type != me.seedexplorer.addon.structures.StructureType.END_CITY) continue;
                    if (structure.hasShip && ships >= 3) continue;
                    if (!structure.hasShip && normal >= 2) continue;
                    totalChecked++;
                    updateTpLootPrepareStatus("predicting End City "
                        + (structure.hasShip ? "ship" : "no ship")
                        + " at " + structure.x + "," + structure.z
                        + " (" + totalChecked + "/" + MAX_END_CITY_CHECKS + ")");
                    int structureAdded = addAllPredictedChests(seed, dimension, structure, prepareJobId, modeLabel);
                    added += structureAdded;
                    if (structureAdded > 0) {
                        if (structure.hasShip) ships++;
                        else normal++;
                    }
                    if (ships >= 3 && normal >= 2) break;
                }
                return added;
            } else {
                Map<String, GeneratedStructure> selected = new LinkedHashMap<>();
                for (GeneratedStructure structure : structures) {
                    selected.putIfAbsent(exhaustiveStructureKey(structure), structure);
                }
                updateTpLootPrepareStatus("selected " + selected.size() + " unique structure variants in "
                    + dimensionName(dimension));
                int checked = 0;
                int added = 0;
                for (GeneratedStructure structure : selected.values()) {
                    checked++;
                    updateTpLootPrepareStatus("predicting loot for " + dimensionName(dimension)
                        + " variant " + checked + "/" + selected.size()
                        + ": " + structure.displayName() + " at " + structure.x + "," + structure.z);
                    added += addAllPredictedChests(seed, dimension, structure, prepareJobId, modeLabel);
                }
                return added;
            }
        }

        int checked = 0;
        int added = 0;
        for (GeneratedStructure structure : structures) {
            if (checked >= maxStructures) break;
            checked++;
            if (checked == 1 || checked % 10 == 0) {
                updateTpLootPrepareStatus("predicting loot for " + dimensionName(dimension)
                    + " structure " + checked + "/" + Math.min(maxStructures, structures.size())
                    + ": " + structure.displayName() + " at " + structure.x + "," + structure.z);
            }
            int structureAdded = addAllPredictedChests(seed, dimension, structure, prepareJobId, modeLabel);
            added += structureAdded;
            if (fullCoverage && structureAdded > 0) {
                updateTpLootPrepareStatus("selected " + dimensionName(dimension) + " "
                    + structure.displayName() + " and stopped this dimension after finding "
                    + structureAdded + " targetable chests");
                break;
            }
        }
        return added;
    }

    private void announceTpLootBuild(String message) {
        MeteorClient.mc.execute(() -> {
            if (tpLootPreparing) info("Seed Explorer TP test: " + message + ".");
        });
    }

    private void updateTpLootPrepareStatus(String message) {
        tpLootPrepareStatus = message;
        tpLootPrepareLastNoticeMs = System.currentTimeMillis();
        announceTpLootBuild(message);
    }

    private int addAllPredictedChests(long seed, int dimension, GeneratedStructure structure, int prepareJobId, String modeLabel) {
        long startNanos = System.nanoTime();
        try {
            updateTpLootPrepareStatus("predicting loot for " + dimensionName(dimension)
                + " " + structure.displayName() + " at " + structure.x + "," + structure.z);
            boolean sampledAncientCity = structure.type == me.seedexplorer.addon.structures.StructureType.ANCIENT_CITY;
            List<ChestLootOutput> chests = sampledAncientCity
                ? ChestLootPredictor.predictTemplateContainerSample(structure, TP_LOOT_ANCIENT_CITY_SAMPLE_CHESTS, 64)
                : ChestLootPredictor.predictForStructure(structure);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int targetable = 0;
            for (ChestLootOutput chest : chests) {
                if (!isTargetableLootChest(chest)) continue;
                targetable++;
            }
            announceTpLootBuild("predicted " + targetable + " targetable chests for "
                + dimensionName(dimension) + " " + structure.displayName()
                + " at " + structure.x + "," + structure.z + " in " + elapsedMs + "ms"
                + (sampledAncientCity ? " (Ancient City sample cap " + TP_LOOT_ANCIENT_CITY_SAMPLE_CHESTS + ")" : ""));
            if (!chests.isEmpty()) {
                PredictionDebugLogger.append("event=loot_tp_test_structure"
                    + " dimension=" + dimension
                    + " dimensionName=\"" + dimensionName(dimension) + "\""
                    + " type=" + structure.type
                    + " variant=\"" + structure.variant.replace("\"", "\\\"") + "\""
                    + " hasShip=" + structure.hasShip
                    + " block=" + structure.x + "," + structure.z
                    + " startChunk=" + structure.startChunkX + "," + structure.startChunkZ
                    + " chestCount=" + chests.size()
                    + " targetableChests=" + targetable
                    + " sampledAncientCity=" + sampledAncientCity
                    + " elapsedMs=" + elapsedMs
                    + "\n");
            }
            int structureChestIndex = 0;
            int added = 0;
            for (ChestLootOutput chest : chests) {
                if (!isTargetableLootChest(chest)) continue;
                tpLootTargets.add(new TpLootTarget(dimension, structure, chest, structureChestIndex++, targetable));
                added++;
                maybeRequestTeleportLootStart(seed, modeLabel, prepareJobId);
            }
            return added;
        } catch (Throwable throwable) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            announceTpLootBuild("prediction failed for " + dimensionName(dimension) + " "
                + structure.displayName() + " at " + structure.x + "," + structure.z
                + " after " + elapsedMs + "ms: " + throwable.getClass().getSimpleName());
            PredictionDebugLogger.append("event=loot_tp_test_prediction_error"
                + " dimension=" + dimension
                + " type=" + structure.type
                + " block=" + structure.x + "," + structure.z
                + " startChunk=" + structure.startChunkX + "," + structure.startChunkZ
                + " elapsedMs=" + elapsedMs
                + " error=\"" + throwable.getClass().getName() + ": "
                + String.valueOf(throwable.getMessage()).replace("\"", "\\\"") + "\""
                + "\n");
            return 0;
        }
    }

    private void maybeRequestTeleportLootStart(long seed, String modeLabel, int prepareJobId) {
        if (tpLootStartRequested || tpLootRunning || tpLootTargets.isEmpty()) return;
        tpLootStartRequested = true;
        MeteorClient.mc.execute(() -> {
            if (prepareJobId != tpLootPrepareJobIds.get() || !tpLootPreparing || tpLootRunning || tpLootTargets.isEmpty()) return;
            startTeleportLootRun(seed, modeLabel);
        });
    }

    private void startTeleportLootRun(long seed, String modeLabel) {
        if (tpLootRunning || tpLootTargets.isEmpty()) return;
        tpLootIndex = 0;
        tpLootWaitTicks = 0;
        tpLootOpenTicks = 0;
        tpLootOpenAttempts = 0;
        tpLootMismatches = 0;
        tpLootVerified = 0;
        tpLootOpenedCount = 0;
        tpLootSkipped = 0;
        tpLootUnverified = 0;
        tpLootOpened = false;
        tpLootClearedAbove = false;
        tpLootRetryNudgeAway = false;
        tpLootRunning = true;
        tpLootStartSeed = seed;
        tpLootMode = modeLabel + ":streaming";
        appendLine(tpLootReport, "status=running");
        appendLine(tpLootReport, "");
        PredictionDebugLogger.append("event=loot_tp_test_start targets=" + tpLootTargets.size()
            + " structures=" + tpLootTargets.stream().map(t -> structureRunKey(t.dimension(), t.structure())).distinct().count()
            + " mode=\"" + tpLootMode.replace("\"", "\\\"") + "\""
            + "\n");
        info("Seed Explorer TP test: started with (highlight)%d(default) chest targets across %d structures. Overworld %d, Nether %d, End %d.",
            tpLootTargets.size(),
            tpLootTargets.stream().map(t -> structureRunKey(t.dimension(), t.structure())).distinct().count(),
            tpLootTargets.stream().filter(t -> t.dimension() == 0).count(),
            tpLootTargets.stream().filter(t -> t.dimension() == -1).count(),
            tpLootTargets.stream().filter(t -> t.dimension() == 1).count());
        info("Seed Explorer TP test: progress messages are enabled. Use .seed-explorer test-tp status for totals, or stop to cancel.");
        teleportToCurrentLootTarget();
    }

    private boolean isTargetableLootChest(ChestLootOutput chest) {
        String table = chest.lootTableId();
        if (table == null || table.isBlank()) return false;
        return !table.startsWith("minecraft:dispensers/")
            && !table.startsWith("minecraft:pots/")
            && !"minecraft:chests/trial_chambers/intersection_barrel".equals(table)
            && !"minecraft:chests/trial_chambers/corridor".equals(table)
            && !table.contains("barrel");
    }

    private void tickTeleportLootTest() {
        if (!tpLootRunning) return;
        if (mc.player == null || mc.level == null || mc.player.connection == null) {
            stopTeleportLootTest(false);
            return;
        }
        if (tpLootIndex < 0 || tpLootIndex >= tpLootTargets.size()) {
            if (!tpLootTargetsComplete) {
                if (shouldAnnounceTpLootWait()) {
                    info("Seed Explorer TP test: waiting for more predicted targets to finish building. Opened %d, verified %d, skipped %d, mismatches %d.",
                        tpLootOpenedCount, tpLootVerified, tpLootSkipped, tpLootMismatches);
                }
                return;
            }
            finishTeleportLootTest();
            return;
        }

        TpLootTarget target = tpLootTargets.get(tpLootIndex);
        ChestLootOutput chest = target.chest();
        BlockPos pos = new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
        tpLootWaitTicks++;
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        int waitLimit = dimensionId() != target.dimension()
            ? TP_LOOT_DIMENSION_WAIT_TICKS
            : TP_LOOT_WAIT_TICKS;

        if (dimensionId() != target.dimension()) {
            if (shouldAnnounceTpLootWait()) {
                info("Seed Explorer TP test: switching dimension for target (highlight)%d/%d(default), waiting %d/%d ticks. Chunk %d,%d. Current dim %s, target %s.",
                    tpLootIndex + 1, tpLootTargets.size(), tpLootWaitTicks, waitLimit,
                    chunkX, chunkZ,
                    dimensionName(dimensionId()), dimensionName(target.dimension()));
                PredictionDebugLogger.append("event=loot_tp_test_wait_state"
                    + " index=" + tpLootIndex + "/" + tpLootTargets.size()
                    + " state=wrong_dimension"
                    + " waitTicks=" + tpLootWaitTicks + "/" + waitLimit
                    + " currentDim=" + dimensionId()
                    + " targetDim=" + target.dimension()
                    + " chest=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + "\n");
            }
            maybeResendTeleport(target, pos, "wrong-dimension");
            if (tpLootWaitTicks >= waitLimit) {
                info("Seed Explorer TP test: skipped target (highlight)%d/%d(default), still in wrong dimension (%s).",
                    tpLootIndex + 1, tpLootTargets.size(), dimensionName(dimensionId()));
                logTeleportLootChest(target, false, false, "wrong-dimension:" + dimensionId() + " " + playerDebugPosition(), false);
                tpLootSkipped++;
                advanceTeleportLootTarget();
            }
            return;
        }

        if (!isBlockLoaded(pos)) {
            if (shouldAnnounceTpLootWait()) {
                info("Seed Explorer TP test: loading chunk for target (highlight)%d/%d(default), waited %d/%d ticks. Chunk %d,%d chest %d,%d,%d.",
                    tpLootIndex + 1, tpLootTargets.size(), tpLootWaitTicks, waitLimit,
                    chunkX, chunkZ,
                    pos.getX(), pos.getY(), pos.getZ());
                info("Seed Explorer TP test: " + playerDebugPosition() + ", distance %.1f blocks from target.",
                    mc.player == null ? -1.0 : Math.sqrt(mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
                PredictionDebugLogger.append("event=loot_tp_test_wait_state"
                    + " index=" + tpLootIndex + "/" + tpLootTargets.size()
                    + " state=chunk_unloaded"
                    + " waitTicks=" + tpLootWaitTicks + "/" + waitLimit
                    + " chunk=" + chunkX + "," + chunkZ
                    + " chest=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + " " + playerDebugPosition()
                    + "\n");
            }
            maybeResendTeleport(target, pos, "chunk-unloaded");
            if (tpLootWaitTicks >= waitLimit) {
                info("Seed Explorer TP test: skipped target (highlight)%d/%d(default), chunk did not load at %d,%d,%d.",
                    tpLootIndex + 1, tpLootTargets.size(), pos.getX(), pos.getY(), pos.getZ());
                logTeleportLootChest(target, false, false, "unloaded-after-tp " + playerDebugPosition(), false);
                tpLootSkipped++;
                advanceTeleportLootTarget();
            }
            return;
        }

        if (mc.player != null && mc.player.containerMenu != null && mc.player.containerMenu.containerId != 0) {
            ChestCompareResult result = compareOpenContainerMenu(target);
            tpLootOpenedCount++;
            if (result.loaded()) tpLootVerified++;
            else tpLootUnverified++;
            if (result.mismatch()) tpLootMismatches++;
            logTeleportLootChest(target, result.loaded(), result.mismatch(), result.actualSummary(), result.predictedSummary(), true);
            if (result.loaded() && !result.mismatch()) {
                info("Seed Explorer TP test: target (highlight)%d/%d(default) verified. Opened %d, verified %d, mismatches %d.",
                    tpLootIndex + 1, tpLootTargets.size(), tpLootOpenedCount, tpLootVerified, tpLootMismatches);
            } else if (result.mismatch()) {
                warning("Seed Explorer TP test: mismatch at target %d/%d. Predicted [%s], actual [%s].",
                    tpLootIndex + 1, tpLootTargets.size(), result.predictedSummary(), result.actualSummary());
            } else {
                warning("Seed Explorer TP test: opened target %d/%d but could not verify loot: %s.",
                    tpLootIndex + 1, tpLootTargets.size(), result.actualSummary());
            }
            mc.player.closeContainer();
            if (isLastPredictedChest(target)) scanUnexpectedContainersForStructure(target);
            advanceTeleportLootTarget();
            return;
        }

        if (!tpLootOpened || tpLootOpenTicks >= TP_LOOT_OPEN_WAIT_TICKS) {
            BlockState state = mc.level.getBlockState(pos);
            String block = blockStateId(state);
            if (!isChestLootBlock(state)) {
                // Distinguish two cases:
                // 1. void_air — the chunk section hasn't generated on the client yet (genuinely
                //    unloaded). Wait the full budget and keep nudging.
                // 2. A real terrain block (stone, netherrack, grass, etc.) — the chunk IS loaded
                //    but the predicted position is wrong. Fail fast (≤1 re-teleport, ~5 s) so
                //    the player doesn't get trapped re-teleporting to a wrong location for 60 s.
                boolean sectionUnloaded = state.is(Blocks.VOID_AIR);
                int settleLimit = sectionUnloaded ? waitLimit : Math.min(waitLimit, TP_LOOT_RETP_INTERVAL_TICKS + 5);
                if (!isChestBlockPresent(pos) && tpLootWaitTicks < settleLimit) {
                    if (shouldAnnounceTpLootWait()) {
                        info("Seed Explorer TP test: chunk loaded but chest block not settled for target (highlight)%d/%d(default), waited %d/%d ticks. Found %s at %d,%d,%d.",
                            tpLootIndex + 1, tpLootTargets.size(), tpLootWaitTicks, settleLimit,
                            block, pos.getX(), pos.getY(), pos.getZ());
                        PredictionDebugLogger.append("event=loot_tp_test_wait_state"
                            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
                            + " state=block_not_settled"
                            + " sectionUnloaded=" + sectionUnloaded
                            + " block=\"" + block + "\""
                            + " waitTicks=" + tpLootWaitTicks + "/" + settleLimit
                            + " settleLimit=" + settleLimit
                            + " chest=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                            + " " + playerDebugPosition()
                            + "\n");
                    }
                    maybeResendTeleport(target, pos, "block-not-settled");
                    return;
                }
                warning("Seed Explorer TP test: target %d/%d is not a container. Found %s at %d,%d,%d.",
                    tpLootIndex + 1, tpLootTargets.size(), block, pos.getX(), pos.getY(), pos.getZ());
                logTeleportLootChest(target, false, false, "not-container:" + block, false);
                tpLootSkipped++;
                if (isLastPredictedChest(target)) scanUnexpectedContainersForStructure(target);
                advanceTeleportLootTarget();
                return;
            }
            if (isContainerBlockedAbove(pos) && !tpLootClearedAbove) {
                clearContainerAbove(pos, target.dimension());
                tpLootClearedAbove = true;
                tpLootOpenTicks = 0;
                info("Seed Explorer TP test: cleared block above target (highlight)%d/%d(default) so the container can open.",
                    tpLootIndex + 1, tpLootTargets.size());
                return;
            }
            if (tpLootOpenAttempts >= TP_LOOT_MAX_OPEN_ATTEMPTS) {
                warning("Seed Explorer TP test: target %d/%d open timed out after %d attempts at %d,%d,%d.",
                    tpLootIndex + 1, tpLootTargets.size(), tpLootOpenAttempts, pos.getX(), pos.getY(), pos.getZ());
                logTeleportLootChest(target, false, false, "open-timeout:" + block, false);
                tpLootUnverified++;
                if (isLastPredictedChest(target)) scanUnexpectedContainersForStructure(target);
                advanceTeleportLootTarget();
                return;
            }
            info("Seed Explorer TP test: opening target (highlight)%d/%d(default), attempt %d/%d. Block %s.",
                tpLootIndex + 1, tpLootTargets.size(), tpLootOpenAttempts + 1, TP_LOOT_MAX_OPEN_ATTEMPTS, block);
            logPreOpenContainerState(target, pos, block);
            openPredictedChest(pos);
            tpLootOpened = true;
            tpLootOpenAttempts++;
            tpLootOpenTicks = 0;
            return;
        }

        if (tpLootOpenTicks > 0 && tpLootOpenTicks % TP_LOOT_CHAT_INTERVAL_TICKS == 0) {
            info("Seed Explorer TP test: waiting for chest GUI target (highlight)%d/%d(default), %d/%d ticks after open attempt.",
                tpLootIndex + 1, tpLootTargets.size(), tpLootOpenTicks, TP_LOOT_OPEN_WAIT_TICKS);
        }
        tpLootOpenTicks++;
    }

    private boolean shouldAnnounceTpLootWait() {
        return tpLootWaitTicks == 1 || tpLootWaitTicks % TP_LOOT_CHAT_INTERVAL_TICKS == 0;
    }

    private String playerDebugPosition() {
        if (mc.player == null) return "player=missing";
        BlockPos playerPos = mc.player.blockPosition();
        return "playerDim=" + dimensionId()
            + " player=" + playerPos.getX() + "," + playerPos.getY() + "," + playerPos.getZ();
    }

    private void teleportToCurrentLootTarget() {
        if (!tpLootRunning || tpLootIndex < 0 || tpLootIndex >= tpLootTargets.size()) return;
        TpLootTarget target = tpLootTargets.get(tpLootIndex);
        ChestLootOutput chest = target.chest();
        double x = chest.blockX() + 0.5;
        double y = chest.blockY() + 1.2;
        double z = chest.blockZ() + 0.5;
        String command = "execute in " + dimensionCommandId(target.dimension())
            + " run tp @s " + x + " " + y + " " + z;
        forceLoadLootTarget(target);
        mc.player.connection.sendCommand(command);
        tpLootWaitTicks = 0;
        tpLootOpenTicks = 0;
        tpLootOpenAttempts = 0;
        tpLootOpened = false;
        tpLootClearedAbove = false;
        tpLootRetryNudgeAway = false;
        if (mc.player != null) mc.player.closeContainer();
        info("Seed Explorer TP test: teleporting to target (highlight)%d/%d(default): %s %s chest %d/%d at %d,%d,%d.",
            tpLootIndex + 1, tpLootTargets.size(),
            dimensionName(target.dimension()),
            target.structure().displayName(),
            target.structureChestIndex() + 1,
            target.structureChestCount(),
            chest.blockX(), chest.blockY(), chest.blockZ());
        PredictionDebugLogger.append("event=loot_tp_test_tp"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " dimension=" + target.dimension()
            + " dimensionName=\"" + dimensionName(target.dimension()) + "\""
            + " type=" + target.structure().type
            + " variant=\"" + target.structure().variant.replace("\"", "\\\"") + "\""
            + " hasShip=" + target.structure().hasShip
            + " structure=" + target.structure().x + "," + target.structure().z
            + " startChunk=" + target.structure().startChunkX + "," + target.structure().startChunkZ
            + " structureChest=" + (target.structureChestIndex() + 1) + "/" + target.structureChestCount()
            + " chest=" + chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()
            + " table=" + chest.lootTableId()
            + " accuracy=" + (chest.exact() ? "exact" : "approx")
            + " command=\"" + command + "\""
            + "\n");
        appendLine(tpLootReport, "TP " + (tpLootIndex + 1) + "/" + tpLootTargets.size()
            + " " + dimensionName(target.dimension())
            + " " + target.structure().displayName()
            + " chest " + (target.structureChestIndex() + 1) + "/" + target.structureChestCount()
            + " pos=" + chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()
            + " table=" + chest.lootTableId()
            + " accuracy=" + (chest.exact() ? "exact" : "approx"));
    }

    private void maybeResendTeleport(TpLootTarget target, BlockPos pos, String reason) {
        if (mc.player == null || mc.player.connection == null) return;
        if (tpLootWaitTicks <= 0 || tpLootWaitTicks % TP_LOOT_RETP_INTERVAL_TICKS != 0) return;

        forceLoadLootTarget(target);
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.2;
        double z = pos.getZ() + 0.5;
        boolean nudged = false;
        if (!reason.startsWith("wrong-dimension")) {
            nudged = tpLootRetryNudgeAway;
            if (nudged) x += 16.0;
            tpLootRetryNudgeAway = !tpLootRetryNudgeAway;
        }
        String command = "execute in " + dimensionCommandId(target.dimension())
            + " run tp @s " + x + " " + y + " " + z;
        mc.player.connection.sendCommand(command);
        info("Seed Explorer TP test: re-sent teleport for target (highlight)%d/%d(default) because %s%s. Chunk %d,%d.",
            tpLootIndex + 1, tpLootTargets.size(), reason,
            nudged ? " with chunk nudge" : "",
            Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
        PredictionDebugLogger.append("event=loot_tp_test_retp"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " reason=\"" + reason.replace("\"", "\\\"") + "\""
            + " nudged=" + nudged
            + " dimension=" + target.dimension()
            + " chest=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
            + " command=\"" + command + "\""
            + "\n");
    }

    private void forceLoadLootTarget(TpLootTarget target) {
        if (mc.player == null || mc.player.connection == null) return;
        ChestLootOutput chest = target.chest();
        int chunkX = Math.floorDiv(chest.blockX(), 16);
        int chunkZ = Math.floorDiv(chest.blockZ(), 16);
        if (tpLootForcedDimension == target.dimension()
            && tpLootForcedChunkX == chunkX
            && tpLootForcedChunkZ == chunkZ) {
            return;
        }

        releaseForcedLootChunk();
        String command = "execute in " + dimensionCommandId(target.dimension())
            + " run forceload add " + chest.blockX() + " " + chest.blockZ();
        mc.player.connection.sendCommand(command);
        tpLootForcedDimension = target.dimension();
        tpLootForcedChunkX = chunkX;
        tpLootForcedChunkZ = chunkZ;
        PredictionDebugLogger.append("event=loot_tp_test_forceload_add"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " dimension=" + target.dimension()
            + " chunk=" + chunkX + "," + chunkZ
            + " block=" + chest.blockX() + "," + chest.blockZ()
            + " command=\"" + command + "\""
            + "\n");
    }

    private void releaseForcedLootChunk() {
        if (mc.player == null || mc.player.connection == null) {
            clearForcedLootChunkState();
            return;
        }
        if (tpLootForcedDimension == Integer.MIN_VALUE) return;
        int blockX = tpLootForcedChunkX * 16;
        int blockZ = tpLootForcedChunkZ * 16;
        String command = "execute in " + dimensionCommandId(tpLootForcedDimension)
            + " run forceload remove " + blockX + " " + blockZ;
        mc.player.connection.sendCommand(command);
        PredictionDebugLogger.append("event=loot_tp_test_forceload_remove"
            + " dimension=" + tpLootForcedDimension
            + " chunk=" + tpLootForcedChunkX + "," + tpLootForcedChunkZ
            + " block=" + blockX + "," + blockZ
            + " command=\"" + command + "\""
            + "\n");
        clearForcedLootChunkState();
    }

    private void clearForcedLootChunkState() {
        tpLootForcedDimension = Integer.MIN_VALUE;
        tpLootForcedChunkX = Integer.MIN_VALUE;
        tpLootForcedChunkZ = Integer.MIN_VALUE;
    }

    private void openPredictedChest(BlockPos pos) {
        if (mc.gameMode == null || mc.player == null) return;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        PredictionDebugLogger.append("event=loot_tp_test_open_attempt"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
            + " attempt=" + (tpLootOpenAttempts + 1)
            + "\n");
    }

    private void logPreOpenContainerState(TpLootTarget target, BlockPos pos, String block) {
        if (mc.level == null) return;
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        String beType = blockEntity == null ? "null" : blockEntity.getClass().getSimpleName();
        String actualTable = "";
        long actualSeed = Long.MIN_VALUE;
        if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
            actualTable = randomizable.getLootTable().identifier().toString();
            actualSeed = randomizable.getLootTableSeed();
        }
        PredictionDebugLogger.append("event=loot_tp_test_preopen"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " dimension=" + target.dimension()
            + " type=" + target.structure().type
            + " variant=\"" + target.structure().variant.replace("\"", "\\\"") + "\""
            + " chest=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
            + " block=" + block
            + " blockEntity=\"" + beType + "\""
            + " predictedTable=" + target.chest().lootTableId()
            + " predictedSeed=" + target.chest().seed()
            + " actualTable=" + actualTable
            + " actualSeed=" + actualSeed
            + "\n");
        appendLine(tpLootReport, "PREOPEN " + (tpLootIndex + 1) + "/" + tpLootTargets.size()
            + " block=" + block
            + " blockEntity=" + beType
            + " actualTable=" + (actualTable.isBlank() ? "unknown" : actualTable)
            + " actualSeed=" + (actualSeed == Long.MIN_VALUE ? "unknown" : String.valueOf(actualSeed)));
    }

    private ChestCompareResult compareOpenContainerMenu(TpLootTarget target) {
        Map<String, Integer> predicted = predictedCountsForOpenedContainer(target);
        String predictedSummary = countSummary(predicted);
        if (mc.player == null || mc.player.containerMenu == null || mc.player.containerMenu.containerId == 0) {
            return new ChestCompareResult(false, false, predictedSummary, "no-open-container-menu");
        }
        Map<String, Integer> actual = actualCountsFromOpenMenu();
        if (actual.isEmpty() && !predicted.isEmpty()) {
            return new ChestCompareResult(false, false, predictedSummary, "open-menu-empty-unverified");
        }
        boolean mismatch = !predicted.equals(actual);
        return new ChestCompareResult(true, mismatch, predictedSummary, countSummary(actual));
    }

    private Map<String, Integer> predictedCountsForOpenedContainer(TpLootTarget target) {
        Map<String, Integer> counts = new LinkedHashMap<>(predictedCounts(target.chest()));
        if (mc.level == null) return counts;

        BlockPos pos = new BlockPos(target.chest().blockX(), target.chest().blockY(), target.chest().blockZ());
        BlockState state = mc.level.getBlockState(pos);
        String block = blockStateId(state);
        if (!block.contains("chest")) return counts;

        String structureKey = structureRunKey(target.dimension(), target.structure());
        for (TpLootTarget candidate : tpLootTargets) {
            if (candidate == target) continue;
            if (!structureRunKey(candidate.dimension(), candidate.structure()).equals(structureKey)) continue;
            ChestLootOutput other = candidate.chest();
            if (!sameLikelyDoubleChest(pos, other)) continue;
            mergeCounts(counts, predictedCounts(other));
        }
        return counts;
    }

    private boolean sameLikelyDoubleChest(BlockPos pos, ChestLootOutput other) {
        if (other.blockY() != pos.getY()) return false;
        int dx = Math.abs(other.blockX() - pos.getX());
        int dz = Math.abs(other.blockZ() - pos.getZ());
        return dx + dz == 1;
    }

    private void mergeCounts(Map<String, Integer> into, Map<String, Integer> extra) {
        extra.forEach((item, count) -> into.merge(item, count, Integer::sum));
    }

    private Map<String, Integer> actualCountsFromOpenMenu() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (mc.player == null || mc.player.containerMenu == null) return counts;
        var inventory = mc.player.getInventory();
        for (var slot : mc.player.containerMenu.slots) {
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private boolean isChestLootBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.contains("chest")
            || id.contains("shulker_box");
    }

    private boolean isContainerBlockedAbove(BlockPos pos) {
        if (mc.level == null) return false;
        BlockPos above = pos.above();
        BlockState aboveState = mc.level.getBlockState(above);
        if (aboveState == null || aboveState.isAir()) return false;
        return !aboveState.getCollisionShape(mc.level, above).isEmpty();
    }

    private void clearContainerAbove(BlockPos pos, int dimension) {
        if (mc.player == null || mc.player.connection == null || mc.level == null) return;
        BlockPos above = pos.above();
        String aboveBlock = blockStateId(mc.level.getBlockState(above));
        String command = "execute in " + dimensionCommandId(dimension)
            + " run setblock " + above.getX() + " " + above.getY() + " " + above.getZ() + " air";
        mc.player.connection.sendCommand(command);
        PredictionDebugLogger.append("event=loot_tp_test_clear_above"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " chest=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
            + " cleared=" + above.getX() + "," + above.getY() + "," + above.getZ()
            + " block=" + aboveBlock
            + " command=\"" + command + "\""
            + "\n");
        appendLine(tpLootReport, "CLEAR_ABOVE " + (tpLootIndex + 1) + "/" + tpLootTargets.size()
            + " cleared=" + above.getX() + "," + above.getY() + "," + above.getZ()
            + " block=" + aboveBlock);
    }

    private void logTeleportLootChest(TpLootTarget target, boolean loaded, boolean mismatch, String actualSummary, boolean openedMenu) {
        logTeleportLootChest(target, loaded, mismatch, actualSummary, countSummary(predictedCounts(target.chest())), openedMenu);
    }

    private void logTeleportLootChest(TpLootTarget target, boolean loaded, boolean mismatch, String actualSummary,
                                      String predictedSummary, boolean openedMenu) {
        ChestLootOutput chest = target.chest();
        PredictionDebugLogger.append("event=loot_tp_test_chest"
            + " index=" + tpLootIndex + "/" + tpLootTargets.size()
            + " dimension=" + target.dimension()
            + " dimensionName=\"" + dimensionName(target.dimension()) + "\""
            + " type=" + target.structure().type
            + " variant=\"" + target.structure().variant.replace("\"", "\\\"") + "\""
            + " hasShip=" + target.structure().hasShip
            + " structure=" + target.structure().x + "," + target.structure().z
            + " startChunk=" + target.structure().startChunkX + "," + target.structure().startChunkZ
            + " structureChest=" + (target.structureChestIndex() + 1) + "/" + target.structureChestCount()
            + " chest=" + chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()
            + " table=" + chest.lootTableId()
            + " accuracy=" + (chest.exact() ? "exact" : "approx")
            + " openAttempts=" + tpLootOpenAttempts
            + " openedMenu=" + openedMenu
            + " clearedAbove=" + tpLootClearedAbove
            + " loaded=" + loaded
            + " mismatch=" + mismatch
            + " predicted=\"" + predictedSummary.replace("\"", "\\\"") + "\""
            + " actual=\"" + actualSummary.replace("\"", "\\\"") + "\""
            + "\n");
        appendLine(tpLootReport, "RESULT " + (tpLootIndex + 1) + "/" + tpLootTargets.size()
            + " openedMenu=" + openedMenu
            + " loaded=" + loaded
            + " mismatch=" + mismatch
            + " attempts=" + tpLootOpenAttempts
            + " clearedAbove=" + tpLootClearedAbove
            + " actual=\"" + actualSummary + "\""
            + " predicted=\"" + predictedSummary + "\"");
    }

    private void advanceTeleportLootTarget() {
        releaseForcedLootChunk();
        tpLootIndex++;
        if (tpLootIndex >= tpLootTargets.size()) {
            finishTeleportLootTest();
            return;
        }
        if (tpLootIndex % 10 == 0 || tpLootIndex == 1) {
            int percent = (int) Math.round(tpLootIndex * 100.0 / Math.max(1, tpLootTargets.size()));
            info("Seed Explorer TP test: progress (highlight)%d/%d(default) (%d%%), opened %d, verified %d, skipped %d, mismatches %d.",
                tpLootIndex, tpLootTargets.size(), percent, tpLootOpenedCount, tpLootVerified, tpLootSkipped, tpLootMismatches);
        }
        teleportToCurrentLootTarget();
    }

    private int stopTeleportLootTest(boolean announce) {
        if (tpLootPreparing && !tpLootRunning) {
            tpLootPreparing = false;
            tpLootPrepareJobIds.incrementAndGet();
            tpLootPrepareStatus = "";
            tpLootPrepareLastNoticeMs = 0L;
            PredictionDebugLogger.append("event=loot_tp_test_prepare_stop\n");
            if (announce) info("Stopped teleport loot test preparation. Any already queued background scan may finish silently.");
            return SINGLE_SUCCESS;
        }
        if (!tpLootRunning) {
            if (announce) info("No teleport loot test is running.");
            return SINGLE_SUCCESS;
        }
        PredictionDebugLogger.append("event=loot_tp_test_stop index=" + tpLootIndex
            + " targets=" + tpLootTargets.size()
            + " verified=" + tpLootVerified
            + " opened=" + tpLootOpenedCount
            + " unverified=" + tpLootUnverified
            + " skipped=" + tpLootSkipped
            + " mismatches=" + tpLootMismatches
            + "\n");
        lastTpLootSummary = teleportLootSummary("stopped");
        DebugReportWriter.copyAndSave("loot-tp-stopped", lastTpLootSummary);
        releaseForcedLootChunk();
        tpLootRunning = false;
        tpLootTargets = List.of();
        tpLootOpened = false;
        tpLootRetryNudgeAway = false;
        tpLootPrepareStatus = "";
        tpLootPrepareLastNoticeMs = 0L;
        if (mc.player != null) mc.player.closeContainer();
        if (announce) info("Stopped teleport loot test.");
        return SINGLE_SUCCESS;
    }

    private void finishTeleportLootTest() {
        lastTpLootSummary = teleportLootSummary("complete");
        PredictionDebugLogger.append("event=loot_tp_test_complete targets=" + tpLootTargets.size()
            + " structures=" + tpLootTargets.stream().map(t -> structureRunKey(t.dimension(), t.structure())).distinct().count()
            + " seed=" + tpLootStartSeed
            + " mode=\"" + tpLootMode.replace("\"", "\\\"") + "\""
            + " opened=" + tpLootOpenedCount
            + " verified=" + tpLootVerified
            + " unverified=" + tpLootUnverified
            + " skipped=" + tpLootSkipped
            + " mismatches=" + tpLootMismatches
            + "\n");
        DebugReportWriter.copyAndSave("loot-tp-complete", lastTpLootSummary);
        info("Seed Explorer: teleport loot test complete. Targets (highlight)%d(default), opened (highlight)%d(default), verified (highlight)%d(default), skipped/unverified (highlight)%d(default), mismatches (highlight)%d(default).",
            tpLootTargets.size(), tpLootOpenedCount, tpLootVerified, tpLootSkipped + tpLootUnverified, tpLootMismatches);
        releaseForcedLootChunk();
        tpLootRunning = false;
        tpLootTargets = List.of();
        tpLootOpened = false;
        tpLootRetryNudgeAway = false;
        tpLootPrepareStatus = "";
        tpLootPrepareLastNoticeMs = 0L;
        if (mc.player != null) mc.player.closeContainer();
    }

    private int teleportLootTestStatus() {
        if (tpLootPreparing && !tpLootRunning) {
            info("TP loot test: preparing targets from Seed Explorer predictions. Teleporting has not started yet.");
            return SINGLE_SUCCESS;
        }
        if (!tpLootRunning) {
            info("No teleport loot test is running.");
            if (!lastTpLootSummary.isBlank()) info("Last: " + oneLine(lastTpLootSummary));
            return SINGLE_SUCCESS;
        }
        TpLootTarget current = tpLootIndex >= 0 && tpLootIndex < tpLootTargets.size() ? tpLootTargets.get(tpLootIndex) : null;
        info("TP loot test: (highlight)%d(default)/(highlight)%d(default), opened %d, verified %d, unverified %d, skipped %d, mismatches %d.",
            tpLootIndex + 1, tpLootTargets.size(), tpLootOpenedCount, tpLootVerified, tpLootUnverified, tpLootSkipped, tpLootMismatches);
        if (current != null) {
            ChestLootOutput chest = current.chest();
            info("Current: (highlight)%s(default) dim %d chest %d,%d,%d table %s.",
                current.structure().displayName(), current.dimension(), chest.blockX(), chest.blockY(), chest.blockZ(), chest.lootTableId());
        }
        return SINGLE_SUCCESS;
    }

    private int writeLastTeleportLootSummary() {
        if (lastTpLootSummary.isBlank()) {
            info("No teleport loot test summary is available yet.");
            return SINGLE_SUCCESS;
        }
        DebugReportWriter.copyAndSave("loot-tp-summary", lastTpLootSummary);
        info("Copied and saved the last teleport loot test summary.");
        return SINGLE_SUCCESS;
    }

    private String teleportLootSummary(String status) {
        StringBuilder report = new StringBuilder();
        appendLine(report, "Seed Explorer teleport loot test");
        appendLine(report, "status=" + status);
        appendLine(report, "seed=" + tpLootStartSeed);
        appendLine(report, "mode=" + tpLootMode);
        appendLine(report, "targets=" + tpLootTargets.size());
        appendLine(report, "structures=" + tpLootTargets.stream().map(t -> structureRunKey(t.dimension(), t.structure())).distinct().count());
        appendLine(report, "index=" + tpLootIndex);
        appendLine(report, "opened=" + tpLootOpenedCount);
        appendLine(report, "verified=" + tpLootVerified);
        appendLine(report, "unverified=" + tpLootUnverified);
        appendLine(report, "skipped=" + tpLootSkipped);
        appendLine(report, "mismatches=" + tpLootMismatches);
        appendLine(report, "");
        appendLine(report, "Order: Overworld -> Nether -> End. Each structure's predicted chests are tested before moving to the next structure.");
        appendLine(report, "Raw per-chest details are also in seed-explorer-reports/prediction-debug.log as loot_tp_test_* events.");
        appendLine(report, "");
        appendLine(report, "Detailed run:");
        if (tpLootReport == null || tpLootReport.isEmpty()) {
            appendLine(report, "  no detailed rows recorded");
        } else {
            report.append(tpLootReport);
            if (report.length() > 0 && report.charAt(report.length() - 1) != '\n') report.append('\n');
        }
        return report.toString();
    }

    private String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String dimensionCommandId(int dimension) {
        return switch (dimension) {
            case -1 -> "minecraft:the_nether";
            case 1 -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }

    private String structureRunKey(int dimension, GeneratedStructure structure) {
        return dimension + ":" + structure.type + ":" + structure.startChunkX + "," + structure.startChunkZ;
    }

    private String exhaustiveStructureKey(GeneratedStructure structure) {
        String variant = structure.variant == null ? "" : structure.variant.trim();
        if (variant.isBlank()) variant = "-";
        return structure.type.name() + "|" + variant + "|" + structure.hasShip;
    }

    private boolean exhaustiveMode() {
        return tpLootMode != null && tpLootMode.startsWith("all-structures");
    }

    private boolean isLastPredictedChest(TpLootTarget target) {
        return target.structureChestIndex() + 1 >= target.structureChestCount();
    }

    private void scanUnexpectedContainersForStructure(TpLootTarget target) {
        if (!exhaustiveMode() || mc.level == null) return;
        try {
            ResourceKey<Structure> key = structureKeyFor(target.structure());
            if (key == null) return;

            StructureStart start = WorldgenEngine.generateSelectedStructureStart(
                tpLootStartSeed,
                target.dimension(),
                key,
                new ChunkPos(target.structure().startChunkX, target.structure().startChunkZ));
            if (start == null || !start.isValid()) return;

            Set<Long> predicted = new HashSet<>();
            String structureKey = structureRunKey(target.dimension(), target.structure());
            for (TpLootTarget candidate : tpLootTargets) {
                if (!structureRunKey(candidate.dimension(), candidate.structure()).equals(structureKey)) continue;
                predicted.add(BlockPos.asLong(candidate.chest().blockX(), candidate.chest().blockY(), candidate.chest().blockZ()));
            }

            Set<Long> unexpected = new HashSet<>();
            for (var piece : start.getPieces()) {
                for (ChunkPos chunkPos : piece.getBoundingBox().intersectingChunks().toList()) {
                    LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
                    if (chunk == null) continue;
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        BlockPos pos = blockEntity.getBlockPos();
                        if (!piece.getBoundingBox().isInside(pos)) continue;
                        if (!isChestLootBlock(mc.level.getBlockState(pos))) continue;
                        long packed = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
                        if (!predicted.contains(packed)) unexpected.add(packed);
                    }
                }
            }

            if (unexpected.isEmpty()) return;

            StringBuilder positions = new StringBuilder();
            for (long packed : unexpected) {
                BlockPos pos = BlockPos.of(packed);
                if (!positions.isEmpty()) positions.append("; ");
                positions.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
            }
            warning("Seed Explorer TP test: found %d unpredicted container%s in %s.",
                unexpected.size(), unexpected.size() == 1 ? "" : "s", target.structure().displayName());
            PredictionDebugLogger.append("event=loot_tp_test_unpredicted_containers"
                + " dimension=" + target.dimension()
                + " type=" + target.structure().type
                + " variant=\"" + target.structure().variant.replace("\"", "\\\"") + "\""
                + " hasShip=" + target.structure().hasShip
                + " structure=" + target.structure().x + "," + target.structure().z
                + " positions=\"" + positions.toString().replace("\"", "\\\"") + "\""
                + "\n");
            appendLine(tpLootReport, "UNPREDICTED " + target.structure().displayName()
                + " count=" + unexpected.size()
                + " positions=" + positions);
        } catch (Throwable throwable) {
            PredictionDebugLogger.append("event=loot_tp_test_unpredicted_error"
                + " dimension=" + target.dimension()
                + " type=" + target.structure().type
                + " error=\"" + throwable.getClass().getName() + ": "
                + String.valueOf(throwable.getMessage()).replace("\"", "\\\"") + "\""
                + "\n");
        }
    }

    private ResourceKey<Structure> structureKeyFor(GeneratedStructure structure) {
        return switch (structure.type) {
            case TRIAL_CHAMBER -> BuiltinStructures.TRIAL_CHAMBERS;
            case IGLOO -> BuiltinStructures.IGLOO;
            case MINESHAFT -> BuiltinStructures.MINESHAFT;
            case OCEAN_RUIN -> BuiltinStructures.OCEAN_RUIN_COLD;
            case RUINED_PORTAL -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            case DESERT_PYRAMID -> BuiltinStructures.DESERT_PYRAMID;
            case OUTPOST -> BuiltinStructures.PILLAGER_OUTPOST;
            case END_CITY -> BuiltinStructures.END_CITY;
            case ANCIENT_CITY -> BuiltinStructures.ANCIENT_CITY;
            case BASTION -> BuiltinStructures.BASTION_REMNANT;
            case FORTRESS -> BuiltinStructures.FORTRESS;
            case MANSION -> BuiltinStructures.WOODLAND_MANSION;
            case SHIPWRECK -> structure.variant != null && structure.variant.contains("beached")
                ? BuiltinStructures.SHIPWRECK_BEACHED
                : BuiltinStructures.SHIPWRECK;
            case TREASURE -> BuiltinStructures.BURIED_TREASURE;
            case JUNGLE_TEMPLE -> BuiltinStructures.JUNGLE_TEMPLE;
            case WITCH_HUT -> BuiltinStructures.SWAMP_HUT;
            case NETHER_RUINED_PORTAL -> BuiltinStructures.RUINED_PORTAL_NETHER;
            case NETHER_FOSSIL -> BuiltinStructures.NETHER_FOSSIL;
            default -> null;
        };
    }

    private record TpLootTarget(int dimension, GeneratedStructure structure, ChestLootOutput chest,
                                int structureChestIndex, int structureChestCount) {
    }

    private int runStructureCheck(int x, int z) {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            error("No seed set. Use .seed set <seed> first.");
            return SINGLE_SUCCESS;
        }

        if (dimensionId() != 0) {
            error("Overworld structure check only supports the Overworld right now.");
            return SINGLE_SUCCESS;
        }

        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        StringBuilder report = new StringBuilder();
        appendLine(report, "Seed Explorer structure-check");
        appendLine(report, "seed=" + seed);
        appendLine(report, "target=" + x + "," + z);
        appendLine(report, "chunk=" + chunkX + "," + chunkZ);
        info("Checking structures near (highlight)%d, %d(default) using seed (highlight)%d(default).", x, z, seed);

        List<GeneratedStructure> structures = VanillaStructurePredictor.predictOverworld(seed, chunkX - 32, chunkZ - 32, chunkX + 32, chunkZ + 32);
        if (!structures.isEmpty()) {
            structures.stream()
                .sorted(Comparator.comparingLong(s -> distanceSquared(x, z, s.x, s.z)))
                .limit(5)
                .forEach(s -> {
                    int distance = (int) Math.round(Math.sqrt(distanceSquared(x, z, s.x, s.z)));
                    info("(highlight)%s(default) at (highlight)%d, %d(default), distance %d, biome %s.",
                        s.displayName(), s.x, s.z, distance, VanillaStructurePredictor.biomeAt(seed, s.x, s.z));
                    appendLine(report, "prediction=" + s.displayName()
                        + " x=" + s.x
                        + " z=" + s.z
                        + " distance=" + distance
                        + " biome=" + VanillaStructurePredictor.biomeAt(seed, s.x, s.z));
                });
            DebugReportWriter.copyAndSave("structure-check", report.toString());
            return SINGLE_SUCCESS;
        }

        warning("No valid supported structure prediction within 32 chunks.");
        appendLine(report, "result=no valid supported structure prediction within 32 chunks");
        List<VanillaStructurePredictor.DebugCandidate> candidates = VanillaStructurePredictor.debugOverworld(seed, chunkX - 32, chunkZ - 32, chunkX + 32, chunkZ + 32);
        candidates.stream()
            .sorted(Comparator.comparingLong(s -> distanceSquared(x, z, s.x(), s.z())))
            .limit(5)
            .forEach(s -> {
                int distance = (int) Math.round(Math.sqrt(distanceSquared(x, z, s.x(), s.z())));
                info("Raw (highlight)%s(default) at (highlight)%d, %d(default), distance %d, biome %s, valid=%s.",
                    s.structureId(), s.x(), s.z(), distance, s.biomeId(), s.validBiome());
                appendLine(report, "raw=" + s.structureId()
                    + " x=" + s.x()
                    + " z=" + s.z()
                    + " distance=" + distance
                    + " biome=" + s.biomeId()
                    + " valid=" + s.validBiome());
            });
        DebugReportWriter.copyAndSave("structure-check", report.toString());
        return SINGLE_SUCCESS;
    }

    private void appendLine(StringBuilder report, String line) {
        report.append(line).append(System.lineSeparator());
    }

    private long distanceSquared(int x1, int z1, int x2, int z2) {
        long dx = x2 - x1;
        long dz = z2 - z1;
        return dx * dx + dz * dz;
    }

    private int dimensionId() {
        if (mc.level == null) return 0;
        if (mc.level.dimension() == Level.NETHER) return -1;
        if (mc.level.dimension() == Level.END) return 1;
        return 0;
    }

    private String dimensionName(int dimension) {
        return switch (dimension) {
            case -1 -> "nether";
            case 1 -> "end";
            default -> "overworld";
        };
    }

    private record MatchSummary(int matchedActual, List<OrePatch> matchedPredicted, List<OrePatch> missingActual, List<OrePatch> extraPredicted) {
    }

    private record BaritoneResult(boolean success, String message) {
    }
}
