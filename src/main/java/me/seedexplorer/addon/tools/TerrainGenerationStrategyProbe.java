package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/** Compares the renderer's accelerated vanilla generation mode with the diagnostic path. */
public final class TerrainGenerationStrategyProbe {
    private static final long SEED = 42L;

    private TerrainGenerationStrategyProbe() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable throwable) {
            printFailure(throwable, 0);
            System.exit(1);
        }
    }

    private static void run() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Warm shared registries and generator caches before measuring either strategy.
        GeneratedTerrainHeightmap warmup =
            new GeneratedTerrainHeightmap(SEED, 0, List.of(), false);
        warmup.getChunk(new ChunkPos(-1, -1));

        for (int dimension : new int[]{0, -1, 1}) {
            compareDimension(dimension);
        }
        Bootstrap.realStdoutPrintln("Terrain generation strategy probe: PASS");
    }

    private static void printFailure(Throwable throwable, int depth) {
        if (throwable == null || depth > 8) return;
        Bootstrap.realStdoutPrintln("failure[" + depth + "]=" + throwable.getClass().getName()
            + " message=" + throwable.getMessage());
        for (StackTraceElement frame : throwable.getStackTrace()) {
            Bootstrap.realStdoutPrintln("  at " + frame.getClassName() + "." + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")");
        }
        printFailure(throwable.getCause(), depth + 1);
    }

    private static void compareDimension(int dimension) {
        ChunkPos[] positions = {new ChunkPos(0, 0), new ChunkPos(1, -1)};

        long diagnosticStarted = System.nanoTime();
        GeneratedTerrainHeightmap diagnostic =
            new GeneratedTerrainHeightmap(SEED, dimension, List.of(), true);
        ProtoChunk[] expected = generate(diagnostic, positions);
        long diagnosticNanos = System.nanoTime() - diagnosticStarted;

        long fastStarted = System.nanoTime();
        GeneratedTerrainHeightmap fast =
            new GeneratedTerrainHeightmap(SEED, dimension, List.of(), false);
        ProtoChunk[] actual = generate(fast, positions);
        long fastNanos = System.nanoTime() - fastStarted;

        for (int i = 0; i < positions.length; i++) {
            compareChunks(dimension, positions[i], expected[i], actual[i]);
        }
        Bootstrap.realStdoutPrintln(String.format(
            "dimension=%d diagnosticMs=%.3f rendererMs=%.3f speedup=%.2fx",
            dimension, diagnosticNanos / 1_000_000.0, fastNanos / 1_000_000.0,
            diagnosticNanos / (double) Math.max(1L, fastNanos)));
    }

    private static ProtoChunk[] generate(GeneratedTerrainHeightmap generator,
                                         ChunkPos[] positions) {
        ProtoChunk[] chunks = new ProtoChunk[positions.length];
        for (int i = 0; i < positions.length; i++) chunks[i] = generator.getChunk(positions[i]);
        return chunks;
    }

    private static void compareChunks(int dimension, ChunkPos pos,
                                      ProtoChunk expected, ProtoChunk actual) {
        require(expected.getMinY() == actual.getMinY()
                && expected.getMaxY() == actual.getMaxY(),
            "build height mismatch in dimension " + dimension);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                for (Heightmap.Types type : new Heightmap.Types[]{
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Heightmap.Types.WORLD_SURFACE_WG}) {
                    require(expected.getHeight(type, localX, localZ)
                            == actual.getHeight(type, localX, localZ),
                        "heightmap mismatch dimension=" + dimension + " chunk=" + pos
                            + " column=" + localX + "," + localZ + " type=" + type);
                }
                for (int y = expected.getMinY(); y < expected.getMaxY(); y++) {
                    cursor.set(pos.getMinBlockX() + localX, y,
                        pos.getMinBlockZ() + localZ);
                    require(expected.getBlockState(cursor).equals(actual.getBlockState(cursor)),
                        "block mismatch dimension=" + dimension + " pos=" + cursor);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
