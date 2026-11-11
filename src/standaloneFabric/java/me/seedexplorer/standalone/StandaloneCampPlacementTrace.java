package me.seedexplorer.standalone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Temporary, opt-in trace for the seed-1 camp phantom barrel. */
public final class StandaloneCampPlacementTrace {
    public static final int TARGET_X = -11211;
    public static final int TARGET_Y = 101;
    public static final int TARGET_Z = 10430;

    private StandaloneCampPlacementTrace() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("seedexplorer.campPlacementTrace");
    }

    public static void log(String line) {
        if (!enabled()) return;
        String file = System.getProperty("seedexplorer.campPlacementTracePath",
            "build/reports/camp-placement-trace.log");
        try {
            Path path = Path.of(file).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    public static boolean ownsTarget(StructureStart start) {
        BlockPos target = new BlockPos(TARGET_X, TARGET_Y, TARGET_Z);
        for (StructurePiece piece : start.getPieces()) {
            if (piece.getBoundingBox().isInside(target)) return true;
        }
        return false;
    }
}
