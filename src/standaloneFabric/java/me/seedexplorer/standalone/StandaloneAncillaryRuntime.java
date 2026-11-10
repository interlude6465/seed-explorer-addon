package me.seedexplorer.standalone;

import me.seedexplorer.addon.enchant.EnchantingTablePredictor;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Installed standalone runtime for commands, mining, enchanting, navigation, and chest overlays. */
public final class StandaloneAncillaryRuntime {
    private static volatile StandaloneAncillaryRuntime instance;
    private final StandaloneSeedExplorerConfig config;
    private final StandaloneNavigationCapability navigation = new ReflectiveBaritoneNavigation();
    private final StandalonePredictedMineService predictedMine = new StandalonePredictedMineService(navigation);
    private final StandaloneEnchantingRng enchantingRng = new StandaloneEnchantingRng();
    private final StandaloneEnchantingSeedCracker seedCracker = new StandaloneEnchantingSeedCracker(enchantingRng);
    private final StandaloneLootSearchService lootSearch = new StandaloneLootSearchService();
    private final StandaloneCommandRouter commands;
    private final CopyOnWriteArrayList<ChestLootOutput> rememberedLoot = new CopyOnWriteArrayList<>();
    private boolean connected;
    private Object activeLevel;
    private Object activeDimension;
    private volatile long activeSeed = Long.MIN_VALUE;
    private volatile String activeVersion = "";
    private volatile int activeDimensionId = Integer.MIN_VALUE;

    private StandaloneAncillaryRuntime(StandaloneSeedExplorerConfig config) {
        this.config = config;
        commands = new StandaloneCommandRouter(config, navigation, predictedMine, enchantingRng, lootSearch);
    }

    public static synchronized StandaloneAncillaryRuntime initialize(StandaloneSeedExplorerConfig config) {
        if (instance == null) instance = new StandaloneAncillaryRuntime(config);
        return instance;
    }

    public static StandaloneAncillaryRuntime get() {
        return instance;
    }

    public void tick(Minecraft client) {
        boolean nowConnected = client != null && client.player != null && client.level != null;
        Object level = nowConnected ? client.level : null;
        Object dimension = nowConnected ? client.level.dimension() : null;
        long seed = config.seed();
        String version = config.predictionVersion();
        int dimensionId = dimension(client == null ? null : client.level);
        if (!nowConnected && connected
            || nowConnected && connected && (level != activeLevel || !dimension.equals(activeDimension)
                || seed != activeSeed || !version.equals(activeVersion) || dimensionId != activeDimensionId)) {
            resetSession();
        }
        connected = nowConnected;
        activeLevel = level;
        activeDimension = dimension;
        activeSeed = seed;
        activeVersion = version;
        activeDimensionId = nowConnected ? dimensionId : Integer.MIN_VALUE;
        if (nowConnected) predictedMine.tick(client);
    }

    public boolean handleChat(String raw) {
        String routed = route(raw);
        if (routed == null) return false;
        Minecraft client = Minecraft.getInstance();
        StandaloneCommandRouter.CommandResult result = commands.execute(routed, context(client));
        if (!result.handled()) {
            message(client, false, "Unknown Seed Explorer command. Use .se help");
            return true;
        }
        for (String line : result.lines()) message(client, result.success(), line);
        return true;
    }

    public void rememberLoot(PredictionContext context, List<ChestLootOutput> chests) {
        if (chests == null || chests.isEmpty()) return;
        if (context == null || context.worldSeed() != activeSeed
            || !context.minecraftVersion().equals(activeVersion)
            || context.dimension() != activeDimensionId) return;
        rememberedLoot.addAll(chests);
        while (rememberedLoot.size() > 512) rememberedLoot.removeFirst();
        StandalonePredictedChestOverlay.get().remember(chests);
    }

    public void rememberLoot(PredictionContext context, ChestLootOutput chest) {
        if (chest != null) rememberLoot(context, List.of(chest));
    }

    public void onContainerInteraction(net.minecraft.core.BlockPos position) {
        StandalonePredictedChestOverlay.get().bind(position);
    }

    public StandaloneEnchantingRng enchantingRng() {
        return enchantingRng;
    }

    public StandaloneEnchantingSeedCracker seedCracker() {
        return seedCracker;
    }

    public void resetSession() {
        predictedMine.stop();
        enchantingRng.reset();
        seedCracker.reset();
        rememberedLoot.clear();
        StandalonePredictedChestOverlay.get().clear();
    }

    private StandaloneCommandRouter.CommandContext context(Minecraft client) {
        int dimension = dimension(client == null ? null : client.level);
        return new StandaloneCommandRouter.CommandContext() {
            @Override public net.minecraft.core.BlockPos playerPosition() {
                return client == null || client.player == null ? null : client.player.blockPosition();
            }
            @Override public int dimension() { return dimension; }
            @Override public PredictionContext predictionContext() {
                return new PredictionContext(config.seed(), config.predictionVersion(), dimension);
            }
            @Override public List<ChestLootOutput> chestLoot() { return List.copyOf(rememberedLoot); }
            @Override public boolean openMap() {
                if (client == null || client.player == null) return false;
                client.gui.setScreen(new StandaloneSeedExplorerScreen(null, config));
                return true;
            }
            @Override public EnchantingTablePredictor.Report enchantingReport() {
                if (client == null || client.player == null || client.level == null
                    || !(client.player.containerMenu instanceof EnchantmentMenu menu)) return null;
                Integer exact = seedCracker.resolvedSeed(menu.getEnchantmentSeed());
                return EnchantingTablePredictor.current(client.level, client.player,
                    exact == null ? menu.getEnchantmentSeed() : exact, exact != null);
            }
            @Override public net.minecraft.core.RegistryAccess registryAccess() {
                return client == null || client.level == null ? null : client.level.registryAccess();
            }
            @Override public net.minecraft.world.item.ItemStack inventoryItem(int slot) {
                if (client == null || client.player == null || slot < 0
                    || slot >= client.player.getInventory().getContainerSize()) {
                    return net.minecraft.world.item.ItemStack.EMPTY;
                }
                return client.player.getInventory().getItem(slot);
            }
        };
    }

    private static String route(String raw) {
        if (raw == null) return null;
        String input = raw.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.equals(".seed-explorer") || lower.startsWith(".seed-explorer ")
            || lower.equals("/seed-explorer") || lower.startsWith("/seed-explorer ")
            || lower.startsWith(".seedexplorer") || lower.startsWith(".se ") || lower.equals(".se")
            || lower.startsWith("/seedexplorer") || lower.startsWith("/se ") || lower.equals("/se")) {
            return input;
        }
        String[] aliases = {"seed", "waypoint", "wp", "loot", "mine-predicted", "enchanting"};
        for (String alias : aliases) {
            String prefix = "." + alias;
            if (lower.equals(prefix) || lower.startsWith(prefix + " ")) {
                return ".se " + alias + input.substring(prefix.length());
            }
        }
        if (lower.startsWith("#mine ")) return ".se mine " + input.substring(6).trim();
        if (lower.equals("#mine")) return ".se mine status";
        return null;
    }

    private static void message(Minecraft client, boolean success, String text) {
        if (client == null || client.player == null) return;
        client.player.sendSystemMessage(Component.literal("[Seed Explorer] " + text)
            .withColor(success ? 0x55DD88 : 0xFF6666));
    }

    private static int dimension(Level level) {
        if (level == null) return 0;
        if (level.dimension() == Level.NETHER) return -1;
        if (level.dimension() == Level.END) return 1;
        return 0;
    }
}
