package me.seedexplorer.standalone;

import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executes the packaged explicit-seed ore path without any frontend singleton. */
public final class StandaloneOreProbe {
    private StandaloneOreProbe() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<OrePatch> first = OrePredictor.predictOreTypeInChunkRangeFast(
            0, 0, 0, 0, 123456789L, 0, OreType.DIAMOND);
        List<OrePatch> second = OrePredictor.predictOreTypeInChunkRangeFast(
            0, 0, 0, 0, 123456789L, 0, OreType.DIAMOND);
        if (!same(first, second)) {
            throw new AssertionError("ore prediction is not deterministic");
        }
        for (OrePatch patch : first) {
            if (patch.type != OreType.DIAMOND || Math.floorDiv(patch.x, 16) != 0
                || Math.floorDiv(patch.z, 16) != 0) {
                throw new AssertionError("ore prediction escaped its requested chunk");
            }
        }

        List<OrePatch> zeroRange = OrePredictor.predictOreTypeInChunkRangeFast(
            -2, -2, 2, 2, 0L, 0, OreType.DIAMOND);
        List<OrePatch> zeroRepeat = OrePredictor.predictOreTypeInChunkRangeFast(
            -2, -2, 2, 2, 0L, 0, OreType.DIAMOND);
        List<OrePatch> zeroRadius = OrePredictor.predictInChunkRadius(
            0, 0, 2, 0, OreType.DIAMOND, Integer.MAX_VALUE,
            0, 0, 0, 0L);
        if (zeroRange.isEmpty() || !same(zeroRange, zeroRepeat)
            || !positions(zeroRange).equals(positions(zeroRadius))) {
            throw new AssertionError("seed-zero ore prediction is missing or inconsistent");
        }
        System.out.println("standalone ore probe: " + first.size()
            + " deterministic diamond blocks; seed zero=" + zeroRange.size());
    }

    private static boolean same(List<OrePatch> first, List<OrePatch> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            OrePatch left = first.get(i);
            OrePatch right = second.get(i);
            if (left.x != right.x || left.y != right.y || left.z != right.z
                || left.type != right.type || left.exact != right.exact) return false;
        }
        return true;
    }

    private static Set<String> positions(List<OrePatch> patches) {
        Set<String> positions = new HashSet<>();
        for (OrePatch patch : patches) {
            positions.add(patch.x + ":" + patch.y + ":" + patch.z + ":" + patch.type);
        }
        return positions;
    }
}
