package me.seedexplorer.addon.debug;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.structures.GeneratedStructure;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Append-only diagnostics for structure/chest prediction mismatches reported from in-game testing. */
public final class PredictionDebugLogger {
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private PredictionDebugLogger() {
    }

    public static void structurePrediction(long seed, int dimension, GeneratedStructure structure, String source, List<ChestLootOutput> chests) {
        StringBuilder sb = new StringBuilder();
        sb.append("event=structure_prediction")
            .append(" seed=").append(seed)
            .append(" dimension=").append(dimension)
            .append(" type=").append(structure.type)
            .append(" variant=").append(quote(structure.variant))
            .append(" hasShip=").append(structure.hasShip)
            .append(" block=").append(structure.x).append(',').append(structure.z)
            .append(" startChunk=").append(structure.startChunkX).append(',').append(structure.startChunkZ)
            .append(" source=").append(source)
            .append(" chestCount=").append(chests == null ? 0 : chests.size())
            .append('\n');
        if (chests != null) {
            for (int i = 0; i < chests.size(); i++) {
                ChestLootOutput chest = chests.get(i);
                sb.append("  chest[").append(i).append("]=")
                    .append(chest.blockX()).append(',').append(chest.blockY()).append(',').append(chest.blockZ())
                    .append(" table=").append(chest.lootTableId())
                    .append(" lootSeed=").append(chest.seed())
                    .append(" accuracy=").append(chest.exact() ? "exact" : "approx")
                    .append(" items=").append(chest.predictedItems() == null ? 0 : chest.predictedItems().size())
                    .append('\n');
                if (chest.predictedItems() != null) {
                    for (ItemLoot item : chest.predictedItems()) {
                        sb.append("    item=")
                            .append(item.minCount()).append('-').append(item.maxCount())
                            .append('x').append(item.itemId());
                        if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank()) {
                            sb.append(" ench=\"").append(item.enchantmentSummary().replace("\"", "\\\"")).append('"');
                        } else if (item.enchantmentId() != null) {
                            sb.append(" ench=").append(item.enchantmentId()).append(':').append(item.enchantmentLevel());
                        }
                        sb.append('\n');
                    }
                }
            }
        }
        append(sb.toString());
    }

    public static void chestTeleport(GeneratedStructure structure, ChestLootOutput chest) {
        append("event=chest_tp"
            + " type=" + structure.type
            + " variant=" + quote(structure.variant)
            + " chest=" + chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()
            + " table=" + chest.lootTableId()
            + " lootSeed=" + chest.seed()
            + " accuracy=" + (chest.exact() ? "exact" : "approx")
            + "\n");
    }

    public static void chestOpened(int x, int y, int z, String screenTitle) {
        append("event=actual_chest_opened"
            + " pos=" + x + "," + y + "," + z
            + " title=" + quote(screenTitle)
            + "\n");
    }

    public static void append(String line) {
        synchronized (LOCK) {
            try {
                Path path = logPath();
                Files.createDirectories(path.getParent());
                String text = "[" + LocalDateTime.now().format(TIME) + "] " + line;
                Files.writeString(path, text, StandardCharsets.UTF_8,
                    Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
            } catch (IOException ignored) {
            }
        }
    }

    private static Path logPath() {
        Minecraft mc = Minecraft.getInstance();
        Path base = mc != null && mc.gameDirectory != null
            ? mc.gameDirectory.toPath()
            : Path.of(".");
        return base.resolve("seed-explorer-reports").resolve("prediction-debug.log");
    }

    private static String quote(String text) {
        if (text == null) return "\"\"";
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
