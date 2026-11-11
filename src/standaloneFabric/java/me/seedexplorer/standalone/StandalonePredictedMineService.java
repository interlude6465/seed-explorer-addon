package me.seedexplorer.standalone;

import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Predicts exact ore targets and advances an optional navigator through them. */
public final class StandalonePredictedMineService {
    private static final ExecutorService PREDICTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer ore prediction");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final StandaloneNavigationCapability navigation;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile List<OrePatch> targets = List.of();
    private volatile int targetIndex;
    private volatile OreType oreType;
    private volatile String state = "idle";
    private volatile Future<?> prediction;
    private int pathRefreshTicks;
    private long breakingTarget = Long.MIN_VALUE;

    public StandalonePredictedMineService(StandaloneNavigationCapability navigation) {
        this.navigation = navigation == null ? StandaloneNavigationCapability.unavailable() : navigation;
    }

    public StartResult start(PredictionContext context, BlockPos origin, String requestedOre,
                             int radiusChunks, int maximumTargets) {
        if (context == null || origin == null) return new StartResult(false, "Missing world context", 0);
        OreType type = parseOre(requestedOre);
        if (type == null) return new StartResult(false, "Unknown ore: " + requestedOre, 0);
        if (type.dimension != context.dimension()) {
            return new StartResult(false, type.displayName + " is not generated in this dimension", 0);
        }

        int radius = Math.max(1, Math.min(12, radiusChunks));
        int limit = Math.max(1, Math.min(256, maximumTargets));
        stop();
        targetIndex = 0;
        oreType = type;
        state = "predicting " + type.displayName;
        int request = generation.incrementAndGet();
        prediction = PREDICTOR.submit(() -> {
            List<OrePatch> predicted = OrePredictor.predictInChunkRadius(context,
                Math.floorDiv(origin.getX(), 16), Math.floorDiv(origin.getZ(), 16),
                radius, type, limit, origin.getX(), origin.getY(), origin.getZ());
            if (generation.get() != request) return;
            targets = List.copyOf(predicted);
            targetIndex = 0;
            if (predicted.isEmpty()) {
                state = "no predicted targets found";
                oreType = null;
                return;
            }
            state = "targets ready";
            pathRefreshTicks = 40;
        });
        return new StartResult(true, "Predicting targets in background", 0);
    }

    public void tick(Minecraft client) {
        OrePatch current = currentTarget();
        if (current == null || client == null || client.player == null || client.level == null
            || client.gameMode == null) return;
        BlockPos target = new BlockPos(current.x, current.y, current.z);

        if (!StandaloneClientChunks.loaded(client.level, target)) {
            refreshPath();
            return;
        }
        BlockState block = client.level.getBlockState(target);
        if (!current.type.matches(block)) {
            state = block.isAir() ? "target mined" : "target was " + block.getBlock().getName().getString();
            advance();
            return;
        }
        if (!client.player.isWithinBlockInteractionRange(target, 0.0)) {
            state = "pathing to " + target.toShortString();
            refreshPath();
            return;
        }

        BlockHitResult sight = client.level.clip(new ClipContext(client.player.getEyePosition(),
            Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        BlockPos breakPosition = sight.getBlockPos();
        if (!client.player.isWithinBlockInteractionRange(breakPosition, 0.0)) {
            state = "pathing for line of sight to " + target.toShortString();
            refreshPath();
            return;
        }
        BlockState breakState = client.level.getBlockState(breakPosition);
        if (breakState.isAir() || breakState.getDestroySpeed(client.level, breakPosition) < 0) {
            state = "waiting for reachable path to " + target.toShortString();
            refreshPath();
            return;
        }
        navigation.stop();
        chooseBestHotbarTool(client, breakState);
        Direction face = sight.getDirection();
        long key = breakPosition.asLong();
        if (breakingTarget != key || !client.gameMode.isDestroying()) {
            client.gameMode.startDestroyBlock(breakPosition, face);
            breakingTarget = key;
        } else {
            client.gameMode.continueDestroyBlock(breakPosition, face);
        }
        client.player.swing(InteractionHand.MAIN_HAND,
            client.player.getMainHandItem().getAttackAnimation(), true);
        state = (breakPosition.equals(target) ? "mining ore " : "clearing blocker ")
            + breakPosition.toShortString();
    }

    /** Compatibility overload used by service-level probes; it never advances merely for proximity. */
    public void tick(BlockPos playerPosition) {
    }

    public void advance() {
        if (!active()) return;
        targetIndex++;
        if (targetIndex >= targets.size()) {
            navigation.stop();
            state = "complete";
            oreType = null;
            return;
        }
        breakingTarget = Long.MIN_VALUE;
        pathRefreshTicks = 40;
        state = "next target";
    }

    public void stop() {
        navigation.stop();
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gameMode != null && client.gameMode.isDestroying()) {
            client.gameMode.stopDestroyBlock();
        }
        generation.incrementAndGet();
        Future<?> activePrediction = prediction;
        // Generation may be reading shared vanilla resources. The generation
        // token rejects stale results without interrupting those file reads.
        if (activePrediction != null) activePrediction.cancel(false);
        prediction = null;
        targets = List.of();
        targetIndex = 0;
        oreType = null;
        state = "idle";
        breakingTarget = Long.MIN_VALUE;
    }

    public boolean active() {
        return targetIndex >= 0 && targetIndex < targets.size();
    }

    public OrePatch currentTarget() {
        return active() ? targets.get(targetIndex) : null;
    }

    public Session session() {
        return new Session(active(), oreType, targetIndex, targets.size(), currentTarget(),
            state + "; navigation " + navigation.status());
    }

    private StandaloneNavigationCapability.Result navigateCurrent() {
        OrePatch target = currentTarget();
        return target == null ? new StandaloneNavigationCapability.Result(false, "No target")
            : navigation.navigate(new BlockPos(target.x, target.y, target.z), 2);
    }

    private void refreshPath() {
        if (++pathRefreshTicks < 40) return;
        pathRefreshTicks = 0;
        navigateCurrent();
    }

    private static void chooseBestHotbarTool(Minecraft client, BlockState state) {
        int bestSlot = client.player.getInventory().getSelectedSlot();
        float bestSpeed = client.player.getInventory().getSelectedItem().getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            float speed = client.player.getInventory().getItem(slot).getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        client.player.getInventory().setSelectedSlot(bestSlot);
    }

    static OreType parseOre(String requested) {
        String normalized = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT)
            .replace("MINECRAFT:", "").replace(' ', '_').replace('-', '_');
        if (normalized.equals("DEBRIS")) normalized = "ANCIENT_DEBRIS";
        if (normalized.endsWith("_ORE")) normalized = normalized.substring(0, normalized.length() - 4);
        try {
            return OreType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record StartResult(boolean started, String message, int targetCount) {
    }

    public record Session(boolean active, OreType oreType, int targetIndex, int targetCount,
                          OrePatch currentTarget, String navigationStatus) {
    }
}
