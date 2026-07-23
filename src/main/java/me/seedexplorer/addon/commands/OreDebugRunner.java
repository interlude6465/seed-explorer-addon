/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs ore debug over multiple ticks so the client does not freeze. */
final class OreDebugRunner {
    private static final OreDebugRunner INSTANCE = new OreDebugRunner();
    private static final int SCAN_Y_LAYERS_PER_TICK = 64;
    private static final int REPORT_LIST_LIMIT = 80;
    private static final int SAMPLE_LIMIT = 40;

    private final Minecraft mc = Minecraft.getInstance();
    private final List<ChunkCoord> chunks = new ArrayList<>();
    private final Map<OreType, List<OrePatch>> actualByType = new EnumMap<>(OreType.class);

    private boolean running;
    private boolean predicting;
    private long seed;
    private int dimension;
    private BlockPos playerPos;
    private int centerChunkX;
    private int centerChunkZ;
    private int radius;
    private int compareMinChunkX;
    private int compareMaxChunkX;
    private int compareMinChunkZ;
    private int compareMaxChunkZ;
    private int nextChunkIndex;
    private int loadedChunks;
    private int progressTicks;
    private LevelChunk activeChunk;
    private int activeScanY;
    private int activeMaxY;
    private List<OrePatch> predictedRange;

    private OreDebugRunner() {
    }

    static OreDebugRunner get() {
        return INSTANCE;
    }

    boolean isRunning() {
        return running || predicting;
    }

    void stop() {
        running = false;
        predicting = false;
        chunks.clear();
        actualByType.clear();
        nextChunkIndex = 0;
        loadedChunks = 0;
        progressTicks = 0;
        activeChunk = null;
        predictedRange = null;
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    void start(int radiusChunks) {
        if (mc.player == null || mc.level == null) {
            ChatUtils.error("Seed Explorer: Join a world first.");
            return;
        }

        long worldSeed = SeedManager.get().getWorldSeed();
        if (worldSeed == 0L) {
            ChatUtils.error("Seed Explorer: No seed set. Use .seed set <seed> or the Seed Explorer seed box first.");
            return;
        }

        if (isRunning()) {
            ChatUtils.warning("Seed Explorer: ore-debug is already running. Use .seed-explorer ore-debug stop first.");
            return;
        }

        seed = worldSeed;
        dimension = dimensionId();
        playerPos = mc.player.blockPosition();
        centerChunkX = Math.floorDiv(playerPos.getX(), 16);
        centerChunkZ = Math.floorDiv(playerPos.getZ(), 16);
        radius = Math.max(0, Math.min(radiusChunks, 8));
        compareMinChunkX = centerChunkX - Math.max(0, radius - 1);
        compareMaxChunkX = centerChunkX + Math.max(0, radius - 1);
        compareMinChunkZ = centerChunkZ - Math.max(0, radius - 1);
        compareMaxChunkZ = centerChunkZ + Math.max(0, radius - 1);

        chunks.clear();
        actualByType.clear();
        for (OreType type : OreType.values()) {
            if (type.dimension == dimension) actualByType.put(type, new ArrayList<>());
        }

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                chunks.add(new ChunkCoord(centerChunkX + dx, centerChunkZ + dz));
            }
        }

        nextChunkIndex = 0;
        loadedChunks = 0;
        progressTicks = 0;
        activeChunk = null;
        predictedRange = null;
        running = true;
        predicting = false;
        MeteorClient.EVENT_BUS.subscribe(this);

        ChatUtils.info("Seed Explorer: ore-debug started. Scanning loaded chunks over time so Minecraft stays responsive.");
        ChatUtils.info("Seed Explorer: radius (highlight)" + radius + "(default), chunks to inspect (highlight)" + chunks.size() + "(default).");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!running) return;
        if (mc.player == null || mc.level == null) {
            ChatUtils.warning("Seed Explorer: ore-debug stopped because the world closed.");
            stop();
            return;
        }

        int processedLayers = 0;
        while (processedLayers < SCAN_Y_LAYERS_PER_TICK && nextChunkIndex < chunks.size()) {
            if (activeChunk == null) {
                ChunkCoord coord = chunks.get(nextChunkIndex);
                activeChunk = mc.level.getChunkSource().getChunkNow(coord.x(), coord.z());
                if (activeChunk == null) {
                    nextChunkIndex++;
                    continue;
                }

                loadedChunks++;
                activeScanY = mc.level.getMinY();
                activeMaxY = mc.level.getMaxY() - 1;
            }

            int layers = Math.min(SCAN_Y_LAYERS_PER_TICK - processedLayers, activeMaxY - activeScanY + 1);
            if (layers <= 0) {
                activeChunk = null;
                nextChunkIndex++;
                continue;
            }

            scanLoadedChunkSection(activeChunk, activeScanY, activeScanY + layers - 1);
            activeScanY += layers;
            processedLayers += layers;

            if (activeScanY > activeMaxY) {
                activeChunk = null;
                nextChunkIndex++;
            }
        }

        if (nextChunkIndex < chunks.size()) {
            progressTicks++;
            if (progressTicks >= 40) {
                progressTicks = 0;
                int inspected = nextChunkIndex + (activeChunk == null ? 0 : 1);
                ChatUtils.info("Seed Explorer: ore-debug scanned (highlight)" + inspected + "(default)/(highlight)" + chunks.size() + "(default) chunk slots.");
            }
            return;
        }

        running = false;
        predicting = true;
        MeteorClient.EVENT_BUS.unsubscribe(this);
        ChatUtils.info("Seed Explorer: loaded chunk scan done. Comparing predictions in the background.");

        Thread worker = new Thread(this::predictAndWriteReport, "Seed Explorer Ore Debug");
        worker.setDaemon(true);
        worker.start();
    }

    private void scanLoadedChunkSection(LevelChunk chunk, int minY, int maxY) {
        if (mc.level == null) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int startY = Math.max(mc.level.getMinY(), minY);
        int endY = Math.min(mc.level.getMaxY() - 1, maxY);
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;

        for (int y = startY; y <= endY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(baseX + x, y, baseZ + z);
                    BlockState state = chunk.getBlockState(pos);
                    OreType type = OreType.fromBlockState(state, dimension);
                    if (type == null || y < type.minY || y > type.maxY) continue;

                    OrePatch patch = new OrePatch(pos.getX(), pos.getY(), pos.getZ(), type, true);
                    if (inChunkBounds(patch, compareMinChunkX, compareMinChunkZ, compareMaxChunkX, compareMaxChunkZ)) {
                        actualByType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(patch);
                    }
                }
            }
        }
    }

    private void predictAndWriteReport() {
        StringBuilder report = new StringBuilder();
        appendHeader(report);

        int totalActual = 0;
        int totalPredicted = 0;
        int totalMatched = 0;
        int totalMissing = 0;
        int totalExtra = 0;

        try {
            infoAsync("Seed Explorer: generating predicted ore positions for the whole debug area.");
            predictedRange = OrePredictor.predictInChunkRange(compareMinChunkX, compareMinChunkZ, compareMaxChunkX, compareMaxChunkZ, seed, dimension);
            infoAsync("Seed Explorer: generated " + predictedRange.size() + " predicted ore blocks. Matching report now.");
        } catch (Throwable throwable) {
            appendLine(report, "prediction_error=" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            finishReport(report);
            return;
        }

        for (OreType type : OreType.values()) {
            if (type.dimension != dimension) continue;

            List<OrePatch> actual = actualByType.getOrDefault(type, List.of());
            List<OrePatch> predicted = predictType(type);
            MatchSummary match = compareOre(actual, predicted, 2);
            MatchSummary looseMatch = compareOre(actual, predicted, 6);

            totalActual += actual.size();
            totalPredicted += predicted.size();
            totalMatched += match.matchedActual();
            totalMissing += match.missingActual().size();
            totalExtra += match.extraPredicted().size();

            appendLine(report, "ORE " + type.displayName);
            appendLine(report, "actual=" + actual.size()
                + " predicted=" + predicted.size()
                + " matched_actual=" + match.matchedActual()
                + " missing_actual=" + match.missingActual().size()
                + " extra_predicted=" + match.extraPredicted().size());
            appendLine(report, "nearest_tolerance_blocks=2");
            appendLine(report, "loose_tolerance_blocks=6 loose_matched_actual=" + looseMatch.matchedActual()
                + " loose_missing_actual=" + looseMatch.missingActual().size()
                + " loose_extra_predicted=" + looseMatch.extraPredicted().size());
            appendSourceSummary(report, "actual_sources", actual);
            appendSourceSummary(report, "predicted_sources", predicted);
            appendSourceSummary(report, "matched_predicted_sources", match.matchedPredicted());
            appendSourceSummary(report, "extra_predicted_sources", match.extraPredicted());
            appendYHistogram(report, "missing_y", match.missingActual());
            appendYHistogram(report, "extra_y", match.extraPredicted());
            appendBlockStateDiagnostics(report, "missing_actual_predicted_base_blocks", match.missingActual(), seed, dimension, SAMPLE_LIMIT);
            appendOreList(report, "missing_actual", match.missingActual(), REPORT_LIST_LIMIT);
            appendOreList(report, "extra_predicted", match.extraPredicted(), REPORT_LIST_LIMIT);
            appendOreList(report, "actual_sample", actual, SAMPLE_LIMIT);
            appendOreList(report, "predicted_sample", predicted, SAMPLE_LIMIT);
            appendLine(report, "");

            infoAsync("Seed Explorer: " + type.displayName + " ore-debug compared. Actual "
                + actual.size() + ", predicted " + predicted.size() + ", matched " + match.matchedActual() + ".");
        }

        appendLine(report, "TOTAL");
        appendLine(report, "loaded_chunk_visits=" + loadedChunks);
        appendLine(report, "actual=" + totalActual);
        appendLine(report, "predicted=" + totalPredicted);
        appendLine(report, "matched_actual=" + totalMatched);
        appendLine(report, "missing_actual=" + totalMissing);
        appendLine(report, "extra_predicted=" + totalExtra);

        finishReport(report);
    }

    private void finishReport(StringBuilder report) {
        String text = report.toString();
        mc.execute(() -> {
            DebugReportWriter.copyAndSave("ore-debug", text);
            ChatUtils.info("Seed Explorer: ore-debug finished.");
            predicting = false;
            predictedRange = null;
        });
    }

    private List<OrePatch> predictType(OreType type) {
        List<OrePatch> predicted = new ArrayList<>();
        List<OrePatch> source = predictedRange != null ? predictedRange : OrePredictor.predictInChunkRange(compareMinChunkX, compareMinChunkZ, compareMaxChunkX, compareMaxChunkZ, seed, dimension);
        for (OrePatch patch : source) {
            if (patch.type == type && inChunkBounds(patch, compareMinChunkX, compareMinChunkZ, compareMaxChunkX, compareMaxChunkZ)) {
                predicted.add(patch);
            }
        }

        predicted.sort(Comparator
            .comparingInt((OrePatch patch) -> patch.x)
            .thenComparingInt(patch -> patch.z)
            .thenComparingInt(patch -> patch.y)
        );
        return predicted;
    }

    private void appendHeader(StringBuilder report) {
        appendLine(report, "Seed Explorer ore-debug");
        appendLine(report, "seed=" + seed);
        appendLine(report, "version=" + SeedManager.get().getMcVersion());
        appendLine(report, "dimension=" + dimensionName(dimension));
        appendLine(report, "player=" + playerPos.getX() + "," + playerPos.getY() + "," + playerPos.getZ());
        appendLine(report, "center_chunk=" + centerChunkX + "," + centerChunkZ);
        appendLine(report, "radius_chunks=" + radius);
        appendLine(report, "compare_inner_chunks=" + compareMinChunkX + "," + compareMinChunkZ + " to " + compareMaxChunkX + "," + compareMaxChunkZ);
        appendLine(report, "loaded_chunk_visits=" + loadedChunks);
        appendLine(report, "scan_y_layers_per_tick=" + SCAN_Y_LAYERS_PER_TICK);
        appendLine(report, "predictor=full_ore_step_v3_chunk_biome_features");
        appendLine(report, "note=actual positions are loaded client chunks only; predictions are seed-only; command scans loaded chunks over ticks then compares predictions in a background thread");
        appendLine(report, "ore_feature_summary=");
        for (String line : OrePredictor.debugFeatureSummary(seed, dimension)) {
            appendLine(report, "  " + line);
        }
        appendLine(report, "");
    }

    private MatchSummary compareOre(List<OrePatch> actual, List<OrePatch> predicted, int tolerance) {
        Map<Long, List<Integer>> predictedByPosition = new HashMap<>();
        for (int i = 0; i < predicted.size(); i++) {
            OrePatch patch = predicted.get(i);
            predictedByPosition.computeIfAbsent(positionKey(patch.x, patch.y, patch.z), ignored -> new ArrayList<>()).add(i);
        }

        Set<Integer> usedPredicted = new HashSet<>();
        List<OrePatch> matchedPredicted = new ArrayList<>();
        List<OrePatch> missingActual = new ArrayList<>();
        int matchedActual = 0;

        for (OrePatch actualPatch : actual) {
            int bestIndex = -1;
            int bestDistance = Integer.MAX_VALUE;

            for (int dy = -tolerance; dy <= tolerance; dy++) {
                int remainingAfterY = tolerance - Math.abs(dy);
                for (int dx = -remainingAfterY; dx <= remainingAfterY; dx++) {
                    int remainingAfterX = remainingAfterY - Math.abs(dx);
                    for (int dz = -remainingAfterX; dz <= remainingAfterX; dz++) {
                        List<Integer> candidates = predictedByPosition.get(positionKey(actualPatch.x + dx, actualPatch.y + dy, actualPatch.z + dz));
                        if (candidates == null) continue;

                        for (int index : candidates) {
                            if (usedPredicted.contains(index)) continue;
                            int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                bestIndex = index;
                            }
                        }
                    }
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

    private long positionKey(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private boolean inChunkBounds(OrePatch patch, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        int chunkX = Math.floorDiv(patch.x, 16);
        int chunkZ = Math.floorDiv(patch.z, 16);
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
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

    private void appendBlockStateDiagnostics(StringBuilder report, String label, List<OrePatch> patches, long seed, int dimension, int sampleLimit) {
        appendLine(report, label + "=");
        if (patches.isEmpty()) {
            appendLine(report, "  none");
            return;
        }

        Map<String, Long> predictedBaseCounts = new HashMap<>();
        int count = Math.min(sampleLimit, patches.size());
        for (int i = 0; i < count; i++) {
            OrePatch patch = patches.get(i);
            predictedBaseCounts.merge(blockStateId(WorldgenEngine.baseBlock(seed, dimension, patch.x, patch.y, patch.z)), 1L, Long::sum);
        }

        appendLine(report, "  predicted_base_sample=");
        predictedBaseCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> appendLine(report, "    " + entry.getKey() + "=" + entry.getValue()));

        appendLine(report, "  samples=");
        for (int i = 0; i < count; i++) {
            OrePatch patch = patches.get(i);
            appendLine(report, "    " + patch.x + "," + patch.y + "," + patch.z
                + " predicted_base=" + blockStateId(WorldgenEngine.baseBlock(seed, dimension, patch.x, patch.y, patch.z)));
        }
        if (patches.size() > sampleLimit) appendLine(report, "    ... " + (patches.size() - sampleLimit) + " more");
    }

    private String blockStateId(BlockState state) {
        if (state == null) return "unknown";
        if (state.is(Blocks.AIR)) return "minecraft:air";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
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

    private void appendLine(StringBuilder report, String line) {
        report.append(line).append(System.lineSeparator());
    }

    private void infoAsync(String message) {
        mc.execute(() -> ChatUtils.info(message));
    }

    private record ChunkCoord(int x, int z) {
    }

    private record MatchSummary(int matchedActual, List<OrePatch> matchedPredicted, List<OrePatch> missingActual, List<OrePatch> extraPredicted) {
    }
}
