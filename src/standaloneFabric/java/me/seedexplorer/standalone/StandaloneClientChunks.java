package me.seedexplorer.standalone;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** ClientLevel.hasChunkAt is not a loaded-data check in 26.3. */
public final class StandaloneClientChunks {
    private StandaloneClientChunks() { }

    public static boolean loaded(ClientLevel level, BlockPos position) {
        return level != null && level.getChunkSource().getChunkNow(
            Math.floorDiv(position.getX(), 16), Math.floorDiv(position.getZ(), 16)) != null;
    }
}
