/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs vanilla /locate commands one at a time and compares results with local predictions. */
final class LocateBatchRunner {
    private static final Pattern COORDS = Pattern.compile("\\[(-?\\d+),\\s*~,\\s*(-?\\d+)]|\\[(-?\\d+),\\s*(-?\\d+)]");
    private static final int TIMEOUT_TICKS = 20 * 30;
    private static final int DEFAULT_COMPARE_RADIUS_CHUNKS = 160;
    private static final int STRONGHOLD_COMPARE_RADIUS_CHUNKS = 1024;
    private static final LocateBatchRunner INSTANCE = new LocateBatchRunner();

    private final Deque<Target> pending = new ArrayDeque<>();
    private final Map<String, LocateResult> results = new LinkedHashMap<>();
    private Target activeTarget;
    private int ticksUntilNext;
    private int activeTicks;
    private long seed;
    private String reportName = "locate-batch";
    private String reportTitle = "Seed Explorer locate-batch";
    private BlockPos startPos;

    private LocateBatchRunner() {
    }

    static LocateBatchRunner get() {
        return INSTANCE;
    }

    void start(long seed, List<String> structures) {
        List<Target> targets = structures.stream()
            .map(id -> new Target(id, id, true, ""))
            .toList();
        start(seed, "locate-batch", "Seed Explorer locate-batch", null, targets);
    }

    void start(long seed, String reportName, String reportTitle, BlockPos startPos, List<Target> targets) {
        stop();
        this.seed = seed;
        this.reportName = reportName;
        this.reportTitle = reportTitle;
        this.startPos = startPos;
        for (Target target : targets) {
            if (target.structureId() == null || target.structureId().isBlank()) {
                results.put(target.name(), LocateResult.skipped(target));
            } else {
                pending.addLast(target);
            }
        }
        MeteorClient.EVENT_BUS.subscribe(this);
        ChatUtils.info("Seed Explorer: Starting " + reportName + " for (highlight)" + targets.size() + "(default) targets.");
        ticksUntilNext = 1;
    }

    void stop() {
        pending.clear();
        activeTarget = null;
        ticksUntilNext = 0;
        activeTicks = 0;
        results.clear();
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    boolean isRunning() {
        return activeTarget != null || !pending.isEmpty();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            stop();
            return;
        }

        if (ticksUntilNext > 0) {
            ticksUntilNext--;
            return;
        }

        if (activeTarget != null) {
            activeTicks++;
            if (activeTicks >= TIMEOUT_TICKS) {
                results.put(activeTarget.name(), LocateResult.timeout(activeTarget));
                ChatUtils.warning("Seed Explorer: " + activeTarget.name() + " timed out.");
                activeTarget = null;
                activeTicks = 0;
                ticksUntilNext = 20;
            }
            return;
        }

        activeTarget = pending.pollFirst();
        if (activeTarget == null) {
            printSummary();
            stop();
            return;
        }

        mc.player.connection.sendCommand("locate structure " + activeTarget.structureId());
        activeTicks = 0;
        ticksUntilNext = 20;
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (activeTarget == null) return;

        String message = event.getMessage().getString();
        Matcher matcher = COORDS.matcher(message);
        if (matcher.find()) {
            int x;
            int z;
            if (matcher.group(1) != null) {
                x = Integer.parseInt(matcher.group(1));
                z = Integer.parseInt(matcher.group(2));
            } else {
                x = Integer.parseInt(matcher.group(3));
                z = Integer.parseInt(matcher.group(4));
            }

            LocateResult result = compare(activeTarget, x, z);
            results.put(activeTarget.name(), result);
            ChatUtils.info("Seed Explorer: " + activeTarget.name() + " locate " + x + ", " + z + " | predicted "
                + result.predictedText() + " | diff " + result.distance() + " blocks.");
            activeTarget = null;
            activeTicks = 0;
            ticksUntilNext = 20;
        } else if (isNotFoundMessage(message)) {
            results.put(activeTarget.name(), LocateResult.notFound(activeTarget));
            ChatUtils.info("Seed Explorer: " + activeTarget.name() + " was not found by /locate.");
            activeTarget = null;
            activeTicks = 0;
            ticksUntilNext = 20;
        }
    }

    private boolean isNotFoundMessage(String message) {
        String lower = message.toLowerCase();
        return lower.contains("could not find")
            || lower.contains("no structure")
            || lower.contains("failed to locate");
    }

    private LocateResult compare(Target target, int locateX, int locateZ) {
        String structureId = target.structureId();
        if (!target.comparePrediction() || VanillaStructurePredictor.typeForStructureId(structureId) == null) {
            return LocateResult.noPredictor(target, locateX, locateZ);
        }

        int chunkX = Math.floorDiv(locateX, 16);
        int chunkZ = Math.floorDiv(locateZ, 16);
        int radius = structureId.equals("minecraft:stronghold")
            ? STRONGHOLD_COMPARE_RADIUS_CHUNKS
            : DEFAULT_COMPARE_RADIUS_CHUNKS;
        List<GeneratedStructure> predictions = VanillaStructurePredictor.predictOverworldStructure(
            seed,
            structureId,
            chunkX - radius,
            chunkZ - radius,
            chunkX + radius,
            chunkZ + radius
        );

        GeneratedStructure closest = predictions.stream()
            .min(Comparator.comparingLong(s -> distanceSquared(locateX, locateZ, s.x, s.z)))
            .orElse(null);
        if (closest == null) return LocateResult.missingPrediction(target, locateX, locateZ);

        int distance = (int) Math.round(Math.sqrt(distanceSquared(locateX, locateZ, closest.x, closest.z)));
        return LocateResult.compared(target, locateX, locateZ, closest, distance);
    }

    private void printSummary() {
        long matched = results.values().stream().filter(r -> r.status().equals("matched")).count();
        long mismatched = results.values().stream().filter(r -> r.status().equals("mismatch")).count();
        long missing = results.values().stream().filter(r -> r.status().equals("missing_prediction")).count();
        long noPredictor = results.values().stream().filter(r -> r.status().equals("no_predictor")).count();
        ChatUtils.info("Seed Explorer: Locate batch complete. Close matches: (highlight)" + matched
            + "(default), mismatches: (highlight)" + mismatched
            + "(default), missing predictions: (highlight)" + missing
            + "(default), no predictor: (highlight)" + noPredictor + "(default).");
        DebugReportWriter.copyAndSave(reportName, buildReport(matched, mismatched, missing, noPredictor));
    }

    private String buildReport(long matched, long mismatched, long missing, long noPredictor) {
        StringBuilder report = new StringBuilder();
        appendLine(report, reportTitle);
        appendLine(report, "seed=" + seed);
        if (startPos != null) {
            appendLine(report, "start=" + startPos.getX() + "," + startPos.getY() + "," + startPos.getZ());
        }
        appendLine(report, "targets=" + results.size());
        appendLine(report, "close_matches=" + matched);
        appendLine(report, "mismatches=" + mismatched);
        appendLine(report, "missing_predictions=" + missing);
        appendLine(report, "no_predictor=" + noPredictor);
        appendLine(report, "");
        appendLine(report, "target | structure_id | locate | predicted | diff_blocks | status | note");

        for (LocateResult result : results.values()) {
            appendLine(report, result.targetName()
                + " | " + result.structureText()
                + " | " + result.locateText()
                + " | " + result.predictedText()
                + " | " + result.distanceText()
                + " | " + result.status()
                + " | " + result.noteText());
        }

        return report.toString();
    }

    private void appendLine(StringBuilder report, String line) {
        report.append(line).append(System.lineSeparator());
    }

    private long distanceSquared(int x1, int z1, int x2, int z2) {
        long dx = x2 - x1;
        long dz = z2 - z1;
        return dx * dx + dz * dz;
    }

    record Target(String name, String structureId, boolean comparePrediction, String note) {
    }

    private record LocateResult(Target target, int locateX, int locateZ, GeneratedStructure prediction, int distance, String status, String note) {
        static LocateResult compared(Target target, int locateX, int locateZ, GeneratedStructure prediction, int distance) {
            String status = distance <= 32 ? "matched" : "mismatch";
            return new LocateResult(target, locateX, locateZ, prediction, distance, status, target.note());
        }

        static LocateResult missingPrediction(Target target, int locateX, int locateZ) {
            return new LocateResult(target, locateX, locateZ, null, -1, "missing_prediction", target.note());
        }

        static LocateResult noPredictor(Target target, int locateX, int locateZ) {
            return new LocateResult(target, locateX, locateZ, null, -1, "no_predictor", target.note());
        }

        static LocateResult notFound(Target target) {
            return new LocateResult(target, 0, 0, null, -1, "not_found", target.note());
        }

        static LocateResult skipped(Target target) {
            return new LocateResult(target, 0, 0, null, -1, "skipped", target.note());
        }

        static LocateResult timeout(Target target) {
            return new LocateResult(target, 0, 0, null, -1, "timeout", target.note());
        }

        String targetName() {
            return target.name();
        }

        String structureText() {
            return target.structureId() == null || target.structureId().isBlank() ? "n/a" : target.structureId();
        }

        String locateText() {
            if ((status.equals("not_found") || status.equals("skipped") || status.equals("timeout")) && locateX == 0 && locateZ == 0) {
                return status;
            }
            return locateX + ", " + locateZ;
        }

        String predictedText() {
            return prediction == null ? "none" : prediction.x + ", " + prediction.z;
        }

        String distanceText() {
            return distance < 0 ? "n/a" : Integer.toString(distance);
        }

        String noteText() {
            return note == null ? "" : note;
        }
    }
}
