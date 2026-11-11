package me.seedexplorer.seedcracking;

/** A dependency-free chunk coordinate. */
public record ChunkCoordinate(int x, int z) {
    public long distanceSquared(ChunkCoordinate other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return dx * dx + dz * dz;
    }
}
