package me.seedexplorer.standalone;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** Blue feedback for independently recognized structures; never queries prediction. */
public final class StandaloneSeedCrackingOutlineRenderer {
    static final int MAX_BOXES = 64;
    static final double MAX_DISTANCE = 256.0;
    static final int BLUE = 0xFF3388FF;
    private static final GizmoStyle STYLE = GizmoStyle.stroke(BLUE, 2.0f);

    private StandaloneSeedCrackingOutlineRenderer() {}

    /** Invoked while the level renderer's native per-frame gizmo collector is active. */
    public static void renderGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) return;
        int dimension = client.level.dimension() == Level.OVERWORLD ? 0
            : client.level.dimension() == Level.NETHER ? -1
            : client.level.dimension() == Level.END ? 1 : Integer.MIN_VALUE;
        // Runtime snapshots are already scoped to the exact current world session
        // and enabled setting; this method performs no chunk/block/worldgen reads.
        List<StandaloneSeedCrackingRuntime.Outline> observed =
            StandaloneSeedCrackingRuntime.visibleOutlines(client);
        if (observed.isEmpty()) return;
        Vec3 camera = client.gameRenderer.mainCamera().position();
        for (StandaloneSeedCrackingRuntime.Outline outline : select(observed, dimension, camera)) {
            // Visible through water/terrain so buried or submerged observations can
            // still be recognized while flying. No opaque fill obscures the world.
            Gizmos.cuboid(bounds(outline), STYLE).setAlwaysOnTop();
        }
    }

    /** Defensive, deterministic draw budget over the bounded immutable observation snapshot. */
    static List<StandaloneSeedCrackingRuntime.Outline> select(
            List<StandaloneSeedCrackingRuntime.Outline> observed, int dimension, Vec3 camera) {
        if (dimension < -1 || dimension > 1 || camera == null
            || !Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)) return List.of();
        return observed.stream()
            .filter(outline -> outline != null && outline.dimension() == dimension && valid(outline))
            .filter(outline -> bounds(outline).distanceToSqr(camera) <= MAX_DISTANCE * MAX_DISTANCE)
            .sorted(Comparator.comparingDouble((StandaloneSeedCrackingRuntime.Outline outline) -> bounds(outline).distanceToSqr(camera))
                .thenComparingInt(StandaloneSeedCrackingRuntime.Outline::minX)
                .thenComparingInt(StandaloneSeedCrackingRuntime.Outline::minY)
                .thenComparingInt(StandaloneSeedCrackingRuntime.Outline::minZ)
                .thenComparing(StandaloneSeedCrackingRuntime.Outline::family))
            .limit(MAX_BOXES).toList();
    }

    private static boolean valid(StandaloneSeedCrackingRuntime.Outline outline) {
        return outline.family() != null && !outline.family().isBlank()
            && axis(outline.minX(), outline.maxX(), 30_000_000)
            && axis(outline.minY(), outline.maxY(), 2048)
            && axis(outline.minZ(), outline.maxZ(), 30_000_000);
    }

    private static boolean axis(int minimum, int maximum, int coordinateLimit) {
        return minimum >= -coordinateLimit && maximum <= coordinateLimit
            && maximum >= minimum && (long) maximum - minimum < 512;
    }

    static AABB bounds(StandaloneSeedCrackingRuntime.Outline outline) {
        // Recognition stores inclusive block bounds. The outer face of the final
        // block is +1, including at negative coordinates and chunk boundaries.
        return new AABB(outline.minX(), outline.minY(), outline.minZ(),
            (double) outline.maxX() + 1, (double) outline.maxY() + 1, (double) outline.maxZ() + 1);
    }

    static GizmoStyle style() { return STYLE; }
}
