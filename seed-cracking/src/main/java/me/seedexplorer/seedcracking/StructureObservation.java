package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * One observed structure placement.
 *
 * <p>Use the structure's <em>start chunk</em>, not the block returned by
 * {@code /locate} (which may include the placement's locate offset). For a
 * random-spread structure the start chunk is the potential chunk produced by
 * {@code getPotentialStructureChunk}; it is normally the minimum chunk of the
 * structure bounding box. If the structure's bounding box is ambiguous, verify
 * the start chunk in a generated world of the target version before using the
 * observation.</p>
 *
 * <p>Negative coordinates are handled with {@link Math#floorDiv(int, int)}, just
 * like vanilla.</p>
 */
public record StructureObservation(
    PlacementSpec placement,
    int regionX,
    int regionZ,
    int chunkX,
    int chunkZ,
    String structureId
) {
    public StructureObservation {
        Objects.requireNonNull(placement, "placement");
        if (structureId == null) {
            structureId = "";
        }
        int offsetX = chunkX - regionX * placement.spacing();
        int offsetZ = chunkZ - regionZ * placement.spacing();
        int range = placement.range();
        if (offsetX < 0 || offsetX >= range || offsetZ < 0 || offsetZ >= range) {
            throw new IllegalArgumentException(
                "Chunk (" + chunkX + ", " + chunkZ + ") is outside region ("
                    + regionX + ", " + regionZ + ") for " + placement.id()
                    + ": offset=(" + offsetX + ", " + offsetZ + "), range=" + range);
        }
    }

    public static StructureObservation at(PlacementSpec placement, int startChunkX, int startChunkZ) {
        return at(placement, "", startChunkX, startChunkZ);
    }

    public static StructureObservation at(PlacementSpec placement, String structureId,
                                          int startChunkX, int startChunkZ) {
        Objects.requireNonNull(placement, "placement");
        return new StructureObservation(
            placement,
            placement.regionX(startChunkX),
            placement.regionZ(startChunkZ),
            startChunkX,
            startChunkZ,
            structureId
        );
    }

    public int offsetX() {
        return chunkX - regionX * placement.spacing();
    }

    public int offsetZ() {
        return chunkZ - regionZ * placement.spacing();
    }

    public boolean placementMatches(long structureSeed) {
        return placement.matches(structureSeed, regionX, regionZ, chunkX, chunkZ, new Lcg48());
    }

    public boolean placementMatches(long structureSeed, Lcg48 random) {
        return placement.matches(structureSeed, regionX, regionZ, chunkX, chunkZ, random);
    }

    public boolean lowBitsMatch(long partialStructureSeed, Lcg48 random) {
        return placement.matchesLowBits(
            partialStructureSeed, regionX, regionZ, offsetX(), offsetZ(), random);
    }

    public StructureObservation withStructureId(String newStructureId) {
        return new StructureObservation(placement, regionX, regionZ, chunkX, chunkZ, newStructureId);
    }

    public String describe() {
        return (structureId == null || structureId.isBlank() ? placement.id() : structureId)
            + " region=(" + regionX + ", " + regionZ + ")"
            + " chunk=(" + chunkX + ", " + chunkZ + ")"
            + " offset=(" + offsetX() + ", " + offsetZ() + ")";
    }
}
