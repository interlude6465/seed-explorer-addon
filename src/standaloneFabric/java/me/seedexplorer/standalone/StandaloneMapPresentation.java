package me.seedexplorer.standalone;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Stable world-space groups split as the map zooms in; underlying predictions stay intact. */
final class StandaloneMapPresentation {
    record Marker(GeneratedStructure structure, int count) { }

    static int fossilCellSize(double blocksPerPixel) {
        if (blocksPerPixel <= 1) return 0;
        int desired = Math.max(32, (int) Math.ceil(blocksPerPixel * 32));
        return Integer.highestOneBit(desired - 1) << 1;
    }

    static List<Marker> markers(List<GeneratedStructure> structures, double blocksPerPixel) {
        int cell = fossilCellSize(blocksPerPixel);
        List<Marker> result = new ArrayList<>();
        var groups = new LinkedHashMap<Long, FossilGroup>();
        for (GeneratedStructure structure : structures) {
            if (cell == 0 || structure.type != StructureType.NETHER_FOSSIL) {
                result.add(new Marker(structure, 1));
                continue;
            }
            long key = ((long) Math.floorDiv(structure.x, cell) << 32)
                ^ (Math.floorDiv(structure.z, cell) & 0xffffffffL);
            FossilGroup group = groups.get(key);
            if (group == null) groups.put(key, new FossilGroup(structure));
            else {
                group.count++;
                if (structure.x < group.structure.x
                    || structure.x == group.structure.x && structure.z < group.structure.z) group.structure = structure;
            }
        }
        for (FossilGroup group : groups.values()) result.add(new Marker(group.structure, group.count));
        return List.copyOf(result);
    }

    static int paddingCells(int visibleCells) { return Math.max(8, Math.min(32, (visibleCells + 1) / 2)); }

    static List<GeneratedStructure> retainWindow(List<GeneratedStructure> structures,
                                                int minX, int minZ, int maxX, int maxZ) {
        return structures.stream().filter(structure -> structure.startChunkX >= minX
            && structure.startChunkX <= maxX && structure.startChunkZ >= minZ
            && structure.startChunkZ <= maxZ).toList();
    }

    private static final class FossilGroup {
        GeneratedStructure structure;
        int count = 1;
        FossilGroup(GeneratedStructure structure) { this.structure = structure; }
    }

    private StandaloneMapPresentation() { }
}
