package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** User-selected predicted containers, scoped to the current client world instance. */
public final class StandaloneChestHighlightRenderer {
    private static final double RANGE_SQUARED = 299.0 * 299.0;
    private static final GizmoStyle STYLE = GizmoStyle.stroke(0xFFFFBB33, 2.5f);
    private record Selection(Level world, StandaloneSeedExplorerConfig config,
                             PredictionContext context, List<ChestLootOutput> chests) { }
    private static Selection selection;

    private StandaloneChestHighlightRenderer() { }

    public static boolean toggle(StandaloneSeedExplorerConfig config, PredictionContext context,
                                 List<ChestLootOutput> chests) {
        if (selected(context, chests)) { clear(); return false; }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null || chests.isEmpty()
            || eligible(chests, context.dimension(), dimension(client.level), client.player.position()).isEmpty()) {
            clear();
            return false;
        }
        selection = new Selection(client.level, config, context, List.copyOf(chests));
        return true;
    }

    public static boolean selected(PredictionContext context, List<ChestLootOutput> chests) {
        Selection active = validSelection();
        return active != null && active.context().equals(context) && active.chests().equals(chests);
    }

    public static void clear() { selection = null; }

    static List<ChestLootOutput> eligible(List<ChestLootOutput> chests, int targetDimension,
                                         int playerDimension, Vec3 player) {
        if (player == null || targetDimension != playerDimension) return List.of();
        return chests.stream().filter(chest -> player.distanceToSqr(
                chest.blockX() + 0.5, chest.blockY() + 0.5, chest.blockZ() + 0.5) <= RANGE_SQUARED)
            .distinct().limit(256).toList();
    }

    private static Selection validSelection() {
        if (selection == null) return null;
        Minecraft client = Minecraft.getInstance();
        Selection active = selection;
        if (client == null || client.level != active.world() || client.player == null
            || active.config().seed() != active.context().worldSeed()
            || !active.config().predictionVersion().equals(active.context().minecraftVersion())) {
            clear();
            return null;
        }
        return active;
    }

    /** The current frame's world- and range-checked containers, shared by drawing and inspection. */
    public static List<ChestLootOutput> visibleChests() {
        Selection active = validSelection();
        if (active == null) return List.of();
        Minecraft client = Minecraft.getInstance();
        return eligible(active.chests(), active.context().dimension(),
            dimension(client.level), client.player.position());
    }

    public static void renderGizmos() {
        for (ChestLootOutput chest : visibleChests()) {
            Gizmos.cuboid(new AABB(chest.blockX(), chest.blockY(), chest.blockZ(),
                chest.blockX() + 1.0, chest.blockY() + 1.0, chest.blockZ() + 1.0), STYLE).setAlwaysOnTop();
        }
    }

    private static int dimension(Level level) {
        return level.dimension() == Level.OVERWORLD ? 0 : level.dimension() == Level.NETHER ? -1
            : level.dimension() == Level.END ? 1 : Integer.MIN_VALUE;
    }
}
