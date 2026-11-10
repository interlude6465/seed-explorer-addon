package me.seedexplorer.standalone;

import me.seedexplorer.addon.enchant.EnchantingTablePredictor;
import me.seedexplorer.addon.enchant.EnchantingTargetPlanner;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Meteor-free command behavior; a Fabric command or chat hook only needs to forward text here. */
public final class StandaloneCommandRouter {
    private static final int DEFAULT_MINE_RADIUS = 8;
    private static final int DEFAULT_MINE_TARGETS = 24;
    private final StandaloneSeedExplorerConfig config;
    private final StandaloneNavigationCapability navigation;
    private final StandalonePredictedMineService predictedMine;
    private final StandaloneEnchantingRng enchantingRng;
    private final StandaloneLootSearchService lootSearch;

    public StandaloneCommandRouter(StandaloneSeedExplorerConfig config,
                                   StandaloneNavigationCapability navigation,
                                   StandalonePredictedMineService predictedMine,
                                   StandaloneEnchantingRng enchantingRng,
                                   StandaloneLootSearchService lootSearch) {
        this.config = config;
        this.navigation = navigation == null ? StandaloneNavigationCapability.unavailable() : navigation;
        this.predictedMine = predictedMine == null
            ? new StandalonePredictedMineService(this.navigation) : predictedMine;
        this.enchantingRng = enchantingRng == null ? new StandaloneEnchantingRng() : enchantingRng;
        this.lootSearch = lootSearch == null ? new StandaloneLootSearchService() : lootSearch;
    }

    public CommandResult execute(String input, CommandContext context) {
        List<String> tokens;
        try { tokens = tokenize(input); }
        catch (IllegalArgumentException invalid) { return error(invalid.getMessage()); }
        if (tokens.isEmpty()) return CommandResult.unhandled();
        String root = normalizeCommand(tokens.getFirst());
        if (root.equals("seedexplorer") || root.equals("seed-explorer") || root.equals("se")) tokens = tokens.subList(1, tokens.size());
        if (tokens.isEmpty()) return ok(help());

        try {
            return switch (normalizeCommand(tokens.getFirst())) {
                case "help" -> ok(help());
                case "open" -> open(context);
                case "seed" -> seed(tokens);
                case "version" -> version(tokens);
                case "status" -> status(context);
                case "waypoint", "wp" -> waypoint(tokens, context);
                case "goto", "navigate" -> navigate(tokens);
                case "stop" -> stopAll();
                case "mine", "mine-predicted" -> mine(tokens, context);
                case "enchant", "enchanting" -> enchanting(tokens, context);
                case "loot", "search" -> loot(tokens, context);
                case "prediction-status", "cache-status" -> predictionStatus(context);
                default -> CommandResult.unhandled();
            };
        } catch (NumberFormatException exception) {
            return error("Invalid number: " + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
    }

    private CommandResult seed(List<String> tokens) {
        if (tokens.size() == 1) return ok("Seed: " + config.seed());
        int valueIndex = normalizeCommand(tokens.get(1)).equals("set") ? 2 : 1;
        if (tokens.size() <= valueIndex) return error("Usage: seed [set] <world-seed>");
        long seed = Long.parseLong(tokens.get(valueIndex));
        config.setSeed(seed);
        config.save();
        return ok("Seed set to " + seed);
    }

    private CommandResult open(CommandContext context) {
        return context != null && context.openMap() ? ok("Opened Seed Explorer")
            : error("Join a world first");
    }

    private CommandResult version(List<String> tokens) {
        if (tokens.size() == 1) return ok("Prediction version: " + config.predictionVersion());
        config.setPredictionVersion(tokens.get(1));
        config.save();
        return ok("Prediction version set to " + config.predictionVersion());
    }

    private CommandResult status(CommandContext context) {
        BlockPos position = context == null ? null : context.playerPosition();
        return ok(List.of(
            "Seed " + config.seed() + ", prediction version " + config.predictionVersion(),
            "Position " + (position == null ? "unavailable" : position.toShortString()),
            "Navigation " + navigation.status(),
            mineStatus(),
            enchantStatus()
        ));
    }

    private CommandResult waypoint(List<String> tokens, CommandContext context) {
        PredictionContext prediction = prediction(context);
        String action = tokens.size() > 1 ? normalizeCommand(tokens.get(1)) : "list";
        if (action.equals("list")) {
            List<StandaloneWaypoint> points = config.waypoints(prediction.worldSeed(), prediction.dimension());
            if (points.isEmpty()) return ok("No waypoints for this seed and dimension");
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                StandaloneWaypoint point = points.get(i);
                lines.add((i + 1) + ". " + point.name() + " at " + point.x() + ", " + point.z()
                    + " [" + point.id() + "]");
            }
            return ok(lines);
        }
        if (action.equals("add")) {
            BlockPos player = requirePosition(context);
            int x = player.getX();
            int z = player.getZ();
            int nameIndex = 2;
            if (tokens.size() >= 4 && isInteger(tokens.get(2)) && isInteger(tokens.get(3))) {
                x = Integer.parseInt(tokens.get(2));
                z = Integer.parseInt(tokens.get(3));
                nameIndex = 4;
            }
            String name = nameIndex < tokens.size() ? String.join(" ", tokens.subList(nameIndex, tokens.size())) : null;
            StandaloneWaypoint point = config.addWaypoint(prediction.worldSeed(), prediction.dimension(),
                x, z, name, "");
            config.save();
            return ok("Waypoint " + point.name() + " at " + x + ", " + z + " [" + point.id() + "]");
        }
        if (action.equals("remove") || action.equals("delete")) {
            if (tokens.size() < 3) return error("Usage: waypoint remove <id|number>");
            List<StandaloneWaypoint> points = config.waypoints(prediction.worldSeed(), prediction.dimension());
            String requested = tokens.get(2);
            String id = requested;
            if (isInteger(requested)) {
                int index = Integer.parseInt(requested) - 1;
                if (index < 0 || index >= points.size()) return error("Waypoint number is out of range");
                id = points.get(index).id();
            }
            if (!config.removeWaypoint(id)) return error("Waypoint not found: " + requested);
            config.save();
            return ok("Waypoint removed");
        }
        return error("Usage: waypoint <list|add|remove>");
    }

    private CommandResult navigate(List<String> tokens) {
        if (tokens.size() != 4) return error("Usage: goto <x> <y> <z>");
        BlockPos target = new BlockPos(Integer.parseInt(tokens.get(1)), Integer.parseInt(tokens.get(2)),
            Integer.parseInt(tokens.get(3)));
        StandaloneNavigationCapability.Result result = navigation.navigate(target, 2);
        return result.accepted() ? ok(result.message()) : error(result.message());
    }

    private CommandResult stopAll() {
        predictedMine.stop();
        navigation.stop();
        return ok("Navigation and predicted mining stopped");
    }

    private CommandResult mine(List<String> tokens, CommandContext context) {
        String action = tokens.size() > 1 ? normalizeCommand(tokens.get(1)) : "status";
        if (action.equals("stop")) {
            predictedMine.stop();
            return ok("Predicted mining stopped");
        }
        if (action.equals("status")) return ok(mineStatus());
        if (action.equals("next")) {
            predictedMine.advance();
            return ok(mineStatus());
        }
        int radius = tokens.size() > 2 ? Integer.parseInt(tokens.get(2)) : DEFAULT_MINE_RADIUS;
        int targets = tokens.size() > 3 ? Integer.parseInt(tokens.get(3)) : DEFAULT_MINE_TARGETS;
        StandalonePredictedMineService.StartResult result = predictedMine.start(
            prediction(context), requirePosition(context), tokens.get(1), radius, targets);
        return result.started()
            ? ok(result.message() + " (" + result.targetCount() + " targets)")
            : error(result.message());
    }

    private CommandResult enchanting(List<String> tokens, CommandContext context) {
        if (tokens.size() > 1 && normalizeCommand(tokens.get(1)).equals("reset")) {
            enchantingRng.reset();
            return ok("Enchanting RNG state reset");
        }
        if (tokens.size() > 1 && normalizeCommand(tokens.get(1)).equals("find")) {
            EnchantingTablePredictor.Report report = context == null ? null : context.enchantingReport();
            if (report == null) return error("Open an enchanting table and insert an item first");
            String requestText = tokens.size() > 2 ? String.join(" ", tokens.subList(2, tokens.size())) : "";
            return enchantReport(report, requestText, context.registryAccess());
        }
        if (tokens.size() > 1 && normalizeCommand(tokens.get(1)).equals("item")) {
            if (tokens.size() < 3) return error("Usage: enchant item <inventory-slot> [enchantments]");
            EnchantingTablePredictor.Report active = context == null ? null : context.enchantingReport();
            if (active == null || !active.exact()) return error("Open an enchanting table and wait for exact seed recovery");
            int slot = Integer.parseInt(tokens.get(2));
            ItemStack item = context.inventoryItem(slot);
            if (item == null || item.isEmpty()) return error("Inventory slot " + slot + " is empty");
            EnchantingTablePredictor.Report report = EnchantingTablePredictor.predictForItem(item,
                active.enchantmentSeed(), active.bookshelfCount(), context.registryAccess());
            String requestText = tokens.size() > 3 ? String.join(" ", tokens.subList(3, tokens.size())) : "";
            return enchantReport(report, requestText, context.registryAccess());
        }
        return ok(enchantStatus());
    }

    private CommandResult enchantReport(EnchantingTablePredictor.Report report, String requestText,
                                        RegistryAccess registries) {
        if (report == null) return error("No enchanting prediction is available");
        List<EnchantingTablePredictor.RequestedEnchantment> requested =
            EnchantingTablePredictor.parseRequested(requestText);
        if (!requested.isEmpty() && !EnchantingTablePredictor.requestsAreCompatible(requested, registries)) {
            return error("The requested enchantments are incompatible");
        }
        List<String> lines = new ArrayList<>();
        lines.add((report.exact() ? "Exact" : "Masked") + " seed " + report.enchantmentSeed()
            + ", bookshelves " + report.bookshelfCount());
        for (EnchantingTablePredictor.Offer offer : report.offers()) {
            String match = !requested.isEmpty() && offer.hasAll(requested) ? " MATCH" : "";
            lines.add((offer.slot() + 1) + ": cost " + offer.cost() + " - " + offer.summary() + match);
        }
        if (!requested.isEmpty() && report.exact()) {
            EnchantingTargetPlanner.find(report.item(), report.enchantmentSeed(), report.bookshelfCount(),
                requested, report, registries).ifPresentOrElse(
                    plan -> lines.add("Plan: " + plan.instruction()),
                    () -> lines.add("No current-seed bookshelf/offer match"));
        }
        return ok(lines);
    }

    private CommandResult predictionStatus(CommandContext context) {
        int chests = context == null || context.chestLoot() == null ? 0 : context.chestLoot().size();
        return ok("Prediction context: seed " + config.seed() + ", version "
            + config.predictionVersion() + ", remembered containers " + chests);
    }

    private CommandResult loot(List<String> tokens, CommandContext context) {
        int start = tokens.size() > 1 && normalizeCommand(tokens.get(1)).equals("search") ? 2 : 1;
        if (tokens.size() <= start) return error("Usage: loot search [--min=N] <item or enchantment>");
        int minimum = 1;
        List<String> queryParts = new ArrayList<>();
        for (int i = start; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--min=")) minimum = Integer.parseInt(token.substring(6));
            else queryParts.add(token);
        }
        if (queryParts.isEmpty()) return error("A loot search term is required");
        List<ChestLootOutput> chests = context == null ? List.of() : context.chestLoot();
        List<StandaloneLootSearchService.Match> matches = lootSearch.search(chests,
            String.join(" ", queryParts), minimum, context == null ? null : context.playerPosition(), 32);
        if (matches.isEmpty()) return ok("No predicted chest loot matched");
        List<String> lines = new ArrayList<>();
        for (StandaloneLootSearchService.Match match : matches) {
            ChestLootOutput chest = match.chest();
            lines.add((chest.exact() ? "Exact: " : "Approximate: ")
                + match.quantity() + " minimum matching item(s) at " + chest.blockX() + ", "
                + chest.blockY() + ", " + chest.blockZ() + ": " + chest.summary());
        }
        return ok(lines);
    }

    private String mineStatus() {
        StandalonePredictedMineService.Session session = predictedMine.session();
        if (!session.active()) {
            return session.oreType() == null ? "Predicted mining idle"
                : "Predicted mining " + session.oreType().displayName + ": " + session.navigationStatus();
        }
        return "Mining " + session.oreType().displayName + " target " + (session.targetIndex() + 1)
            + "/" + session.targetCount() + " at " + session.currentTarget().x + ", "
            + session.currentTarget().y + ", " + session.currentTarget().z;
    }

    private String enchantStatus() {
        StandaloneEnchantingRng.Snapshot snapshot = enchantingRng.snapshot();
        return "Enchanting RNG " + snapshot.state().name().toLowerCase(Locale.ROOT)
            + (snapshot.playerSeed() == null ? "" : ", seed " + snapshot.playerSeed());
    }

    private PredictionContext prediction(CommandContext context) {
        PredictionContext supplied = context == null ? null : context.predictionContext();
        if (supplied != null) return supplied;
        int dimension = context == null ? 0 : context.dimension();
        return new PredictionContext(config.seed(), config.predictionVersion(), dimension);
    }

    private static BlockPos requirePosition(CommandContext context) {
        BlockPos position = context == null ? null : context.playerPosition();
        if (position == null) throw new IllegalArgumentException("Join a world first");
        return position;
    }

    static List<String> tokenize(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char value : input.trim().toCharArray()) {
            if (escaped) {
                current.append(value);
                escaped = false;
            } else if (value == '\\' && quote != 0) {
                escaped = true;
            } else if ((value == '\'' || value == '"')) {
                if (quote == value) quote = 0;
                else if (quote == 0) quote = value;
                else current.append(value);
            } else if (Character.isWhitespace(value) && quote == 0) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else current.append(value);
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote");
        if (!current.isEmpty()) tokens.add(current.toString());
        return List.copyOf(tokens);
    }

    private static String normalizeCommand(String token) {
        String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("/") || value.startsWith(".")) value = value.substring(1);
        return value;
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static List<String> help() {
        return List.of("open", "seed [value]", "version [value]", "status", "waypoint <list|add|remove>",
            "goto <x> <y> <z>", "mine <ore|status|stop|next> [radius] [targets]",
            "enchant <status|reset|find|item>", "loot search [--min=N] <item>",
            "prediction-status", "stop");
    }

    private static CommandResult ok(String line) {
        return ok(List.of(line));
    }

    private static CommandResult ok(List<String> lines) {
        return new CommandResult(true, true, List.copyOf(lines));
    }

    private static CommandResult error(String line) {
        return new CommandResult(true, false, List.of(line == null ? "Command failed" : line));
    }

    public interface CommandContext {
        BlockPos playerPosition();
        int dimension();
        PredictionContext predictionContext();
        List<ChestLootOutput> chestLoot();
        default boolean openMap() { return false; }
        default EnchantingTablePredictor.Report enchantingReport() { return null; }
        default RegistryAccess registryAccess() { return null; }
        default ItemStack inventoryItem(int slot) { return ItemStack.EMPTY; }
    }

    public record CommandResult(boolean handled, boolean success, List<String> lines) {
        static CommandResult unhandled() {
            return new CommandResult(false, false, List.of());
        }
    }
}
