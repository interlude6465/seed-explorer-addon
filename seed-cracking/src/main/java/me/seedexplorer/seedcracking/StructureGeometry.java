package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * Pure geometry helpers shared by structure-observation and screenshot
 * pipelines.
 *
 * <p>Structure placement happens in chunk space. A block position must first be
 * converted with a floor division (or an arithmetic shift for ints), then
 * checked against {@link PlacementSpec#potentialChunk}. Do not use the block
 * returned by {@code /locate} as the start chunk: it may include the placement's
 * locate offset or a point inside the structure.</p>
 */
public final class StructureGeometry {
    public static final int CHUNK_SIZE = 16;

    private StructureGeometry() {
    }

    /** Floor-divides a block coordinate into a chunk coordinate. */
    public static int blockToChunk(int blockCoordinate) {
        return blockCoordinate >> 4;
    }

    public static ChunkCoordinate chunkOfBlock(int blockX, int blockZ) {
        return new ChunkCoordinate(blockToChunk(blockX), blockToChunk(blockZ));
    }

    /** First block coordinate of a chunk. */
    public static int chunkToBlock(int chunkCoordinate) {
        return chunkCoordinate << 4;
    }

    /** Chunk containing the minimum corner of an observed structure bounding box. */
    public static ChunkCoordinate startChunkFromMinBlock(int minBlockX, int minBlockZ) {
        return chunkOfBlock(minBlockX, minBlockZ);
    }

    /** Exact potential chunk for a structure seed and region. */
    public static ChunkCoordinate potentialChunk(PlacementSpec placement, long structureSeed,
                                                 int regionX, int regionZ) {
        Objects.requireNonNull(placement, "placement");
        return placement.potentialChunk(structureSeed, regionX, regionZ);
    }

    /** Exact potential-chunk predicate for a known chunk. */
    public static boolean isPotentialChunk(PlacementSpec placement, long structureSeed,
                                           int chunkX, int chunkZ) {
        Objects.requireNonNull(placement, "placement");
        int regionX = placement.regionX(chunkX);
        int regionZ = placement.regionZ(chunkZ);
        return placement.matches(structureSeed, regionX, regionZ, chunkX, chunkZ, new Lcg48());
    }

    /** Builds an observation from the structure's start block corner. */
    public static StructureObservation observeStartBlock(PlacementSpec placement, String structureId,
                                                          int startBlockX, int startBlockZ) {
        ChunkCoordinate chunk = chunkOfBlock(startBlockX, startBlockZ);
        return placement.observe(structureId, chunk.x(), chunk.z());
    }

    /** Builds an observation directly from a known start chunk. */
    public static StructureObservation observeStartChunk(PlacementSpec placement, String structureId,
                                                          int startChunkX, int startChunkZ) {
        return placement.observe(structureId, startChunkX, startChunkZ);
    }

    /** Checks that the chunk is one of the placement's possible offsets. */
    public static boolean isOffsetInRange(PlacementSpec placement, int chunkX, int chunkZ) {
        Objects.requireNonNull(placement, "placement");
        int offsetX = chunkX - placement.regionX(chunkX) * placement.spacing();
        int offsetZ = chunkZ - placement.regionZ(chunkZ) * placement.spacing();
        return offsetX >= 0 && offsetX < placement.range()
            && offsetZ >= 0 && offsetZ < placement.range();
    }

    /** Compass orientation used by screenshot and structure-variant observations. */
    public enum CompassFacing {
        NORTH, EAST, SOUTH, WEST;

        public static CompassFacing fromString(String value) {
            Objects.requireNonNull(value, "value");
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "north", "n" -> NORTH;
                case "east", "e" -> EAST;
                case "south", "s" -> SOUTH;
                case "west", "w" -> WEST;
                default -> throw new IllegalArgumentException("Unknown compass facing: " + value);
            };
        }

        /** Quarter turns clockwise from north, in [0, 3]. */
        public int quarterTurnsFromNorth() {
            return ordinal();
        }
    }

    /** Structure mirror mode, as used by vanilla structure piece placement. */
    public enum Mirror {
        NONE, LEFT_RIGHT, FRONT_BACK;

        public static Mirror fromString(String value) {
            Objects.requireNonNull(value, "value");
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "none" -> NONE;
                case "left_right", "leftright" -> LEFT_RIGHT;
                case "front_back", "frontback" -> FRONT_BACK;
                default -> throw new IllegalArgumentException("Unknown mirror: " + value);
            };
        }
    }

    /**
     * Observed orientation. The bit estimate is a rough upper bound; rotation
     * and mirror are not independent for every structure piece.
     */
    public record Orientation(CompassFacing facing, Mirror mirror) {
        public static final double APPROX_BITS = Math.log(4.0 * 3.0) / Math.log(2.0);

        public Orientation {
            Objects.requireNonNull(facing, "facing");
            Objects.requireNonNull(mirror, "mirror");
        }

        public static Orientation from(String facing, String mirror) {
            return new Orientation(CompassFacing.fromString(facing), Mirror.fromString(mirror));
        }

        public int rotationIndex() {
            return facing.quarterTurnsFromNorth();
        }
    }

    /** Squared chunk distance, using a long to avoid overflow. */
    public static long distanceSquared(ChunkCoordinate first, ChunkCoordinate second) {
        return first.distanceSquared(second);
    }
}
