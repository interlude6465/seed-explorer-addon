package me.seedexplorer.standalone;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.util.Locale;

/** Resolves profile identity and integrated-world seed on the client thread. */
final class StandaloneWorldContext {
    private Object level;
    void tick(Minecraft client, StandaloneSeedExplorerConfig config) {
        if (client.level == level) return;
        level = client.level;
        if (level == null) { config.save(); return; }
        String identity = "local";
        var serverInfo = client.getCurrentServer();
        var server = client.getSingleplayerServer();
        if (serverInfo != null) identity = "server:" + serverInfo.ip.toLowerCase(Locale.ROOT);
        else if (server != null) identity = "singleplayer:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        config.selectProfile(identity);
        if (server != null && !config.hasSeed()) {
            config.setSeed(server.overworld().getSeed());
        }
        config.save();
    }
}
