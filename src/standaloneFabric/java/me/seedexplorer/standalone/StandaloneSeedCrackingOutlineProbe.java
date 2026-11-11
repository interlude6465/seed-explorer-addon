package me.seedexplorer.standalone;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Headless rendering-policy checks; actual draw/lifecycle checks belong to the client smoke. */
final class StandaloneSeedCrackingOutlineProbe {
    static void verify() {
        var crossing = box("shipwreck", 0, -17, 40, -1, 16, 59, 20);
        var bounds = StandaloneSeedCrackingOutlineRenderer.bounds(crossing);
        require(bounds.minX == -17 && bounds.maxX == 17 && bounds.minZ == -1 && bounds.maxZ == 21,
            "Observed inclusive bounds were truncated at a negative/chunk boundary");
        require(bounds.getXsize() == 34 && bounds.getYsize() == 20 && bounds.getZsize() == 22,
            "Outline dimensions do not enclose the last observed block");

        Vec3 camera = new Vec3(0, 64, 0);
        var sameCoordinatesNether = box("pyramid", -1, -17, 40, -1, 16, 59, 20);
        var farAway = box("shipwreck", 0, 900, 40, 0, 920, 59, 20);
        var malformed = box("shipwreck", 0, 20, 40, 0, 10, 59, 20);
        var enormous = box("shipwreck", 0, -1000, 40, -1000, 1000, 59, 1000);
        var overflow = box("shipwreck", 0, Integer.MAX_VALUE, 40, 0, Integer.MAX_VALUE, 59, 20);
        require(StandaloneSeedCrackingOutlineRenderer.select(
            List.of(crossing, sameCoordinatesNether, farAway, malformed, enormous, overflow), 0, camera).equals(List.of(crossing)),
            "Outline culling admitted another dimension, distant or invalid bounds");
        require(StandaloneSeedCrackingOutlineRenderer.select(List.of(crossing), Integer.MIN_VALUE, camera).isEmpty(),
            "Unknown dimension inherited Overworld outlines");
        require(StandaloneSeedCrackingOutlineRenderer.select(List.of(crossing), 0, new Vec3(Double.NaN, 0, 0)).isEmpty(),
            "Invalid camera coordinates admitted outlines");
        require(StandaloneSeedCrackingOutlineRenderer.select(List.of(), 0, camera).isEmpty(),
            "Empty/disabled runtime snapshot fabricated render geometry");

        List<StandaloneSeedCrackingRuntime.Outline> crowded = new ArrayList<>();
        for (int x = 100; x >= 0; x--) crowded.add(box("shipwreck", 0, x, 64, 0, x, 64, 0));
        var nearest = StandaloneSeedCrackingOutlineRenderer.select(crowded, 0, camera);
        require(nearest.size() == 64 && nearest.getFirst().minX() == 0 && nearest.getLast().minX() == 63,
            "Per-frame draw cap did not retain the nearest 64 observations");
        var style = StandaloneSeedCrackingOutlineRenderer.style();
        require(style.hasStroke() && !style.hasFill() && style.stroke() == 0xFF3388FF && style.strokeWidth() == 2.0f,
            "Recognized structure feedback must use a visible blue outline without obscuring fill");
        System.out.println("Cracking outline policy: PASS (inclusive bounds, negative/chunk edges, dimension/distance/invalid culling, nearest-64 budget, blue stroke)");
    }

    private static StandaloneSeedCrackingRuntime.Outline box(String family, int dimension,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new StandaloneSeedCrackingRuntime.Outline(family, dimension, minX, minY, minZ, maxX, maxY, maxZ);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
