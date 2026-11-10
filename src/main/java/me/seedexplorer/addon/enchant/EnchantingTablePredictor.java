package me.seedexplorer.addon.enchant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Comparator;

/** Exact read-only prediction of the offers in the currently open enchanting table. */
public final class EnchantingTablePredictor {
    private EnchantingTablePredictor() {
    }

    public record EnchantmentResult(String id, int level) {
    }

    /** An enchantment that can be offered for the current item and its vanilla maximum level. */
    public record EnchantmentOption(String id, String displayName, int maxLevel) {
    }

    /** A command request. A level of zero means that any level is acceptable. */
    public record RequestedEnchantment(String id, int level) {
        public RequestedEnchantment {
            id = normalizeId(id);
            if (id.isBlank()) throw new IllegalArgumentException("enchantment name is empty");
            if (level < 0) throw new IllegalArgumentException("enchantment level cannot be negative");
        }

        public boolean matches(EnchantmentResult result) {
            return sameId(result.id(), id) && (level == 0 || result.level() == level);
        }

        public String display() {
            String shortName = id.substring(id.indexOf(':') + 1);
            return level == 0 ? shortName : shortName + " " + level;
        }
    }

    public record Offer(int slot, int cost, int clueId, int clueLevel,
                        List<EnchantmentResult> enchantments) {
        public boolean hasAll(List<RequestedEnchantment> requested) {
            if (requested == null || requested.isEmpty()) return false;
            return requested.stream().allMatch(request -> enchantments.stream()
                .anyMatch(request::matches));
        }

        public String summary() {
            if (cost <= 0 || enchantments.isEmpty()) return "unavailable";
            StringBuilder result = new StringBuilder();
            for (EnchantmentResult enchantment : enchantments) {
                if (!result.isEmpty()) result.append(", ");
                result.append(shortId(enchantment.id())).append(" ").append(enchantment.level());
            }
            return result.toString();
        }
    }

    public record Report(ItemStack item, int enchantmentSeed, int bookshelfCount,
                         List<Offer> offers, boolean exact) {
        public boolean hasRequested(List<RequestedEnchantment> requested) {
            return offers.stream().anyMatch(offer -> offer.hasAll(requested));
        }
    }

    /** Returns null unless the player currently has an enchanting table menu open. */
    public static Report current(Level level, Player player) {
        if (level == null || player == null || !(player.containerMenu instanceof EnchantmentMenu menu)) {
            return null;
        }

        // The shared predictor does not own seed recovery. Frontends with a cracker should
        // call the explicit-seed overload once they have resolved the masked menu value.
        return current(level, player, menu.getEnchantmentSeed(), false);
    }

    /** Reads the active menu while using a recovered full XP seed when available. */
    public static Report current(Level level, Player player, int enchantmentSeed, boolean exact) {
        if (level == null || player == null || !(player.containerMenu instanceof EnchantmentMenu menu)) {
            return null;
        }

        ItemStack item = menu.getSlot(0).getItem();
        int bookshelfCount = findBookshelfCount(level, player);
        List<Offer> offers = new ArrayList<>(3);
        Registry<Enchantment> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tableEnchantments = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE);
        boolean verified = exact;

        for (int slot = 0; slot < 3; slot++) {
            int cost = menu.costs[slot];
            SelectedOffer selected = tableEnchantments.isEmpty() || cost <= 0 || item.isEmpty()
                ? new SelectedOffer(List.of(), -1, -1)
                : selectOffer(enchantmentSeed, item, slot, cost, tableEnchantments.get(), registry);
            List<EnchantmentResult> predicted = selected.enchantments();
            if (cost > 0 && (selected.clueId() != menu.enchantClue[slot]
                || selected.clueLevel() != menu.levelClue[slot])) {
                verified = false;
            }
            offers.add(new Offer(slot, cost, menu.enchantClue[slot], menu.levelClue[slot], predicted));
        }
        return new Report(item.copy(), enchantmentSeed, bookshelfCount, List.copyOf(offers), verified);
    }

    /** Offline/test entry point using the exact same helper path as the active menu. */
    public static List<EnchantmentResult> predictOffer(int enchantmentSeed, ItemStack item, int cost,
                                                        RegistryAccess registryAccess) {
        return predictOffer(enchantmentSeed, item, 0, cost, registryAccess);
    }

    /** Predicts one offer using its vanilla slot index (0, 1, or 2). */
    public static List<EnchantmentResult> predictOffer(int enchantmentSeed, ItemStack item, int slot, int cost,
                                                        RegistryAccess registryAccess) {
        if (item == null || item.isEmpty() || cost <= 0 || registryAccess == null) return List.of();
        Registry<Enchantment> registry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tableEnchantments = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE);
        return tableEnchantments.isEmpty() ? List.of()
            : selectOffer(enchantmentSeed, item, slot, cost, tableEnchantments.get(), registry).enchantments();
    }

    /** Predicts all three costs and full enchantment lists for an item not yet placed in the table. */
    public static Report predictForItem(ItemStack item, int enchantmentSeed, int bookshelfCount,
                                        RegistryAccess registryAccess) {
        if (item == null || item.isEmpty() || registryAccess == null || bookshelfCount < 0) return null;
        Registry<Enchantment> registry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tableEnchantments = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tableEnchantments.isEmpty()) return null;

        RandomSource random = RandomSource.create();
        random.setSeed(enchantmentSeed);
        List<Offer> offers = new ArrayList<>(3);
        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, bookshelfCount, item);
            if (cost < slot + 1) cost = 0;
            SelectedOffer selected = cost <= 0
                ? new SelectedOffer(List.of(), -1, -1)
                : selectOffer(enchantmentSeed, item, slot, cost, tableEnchantments.get(), registry);
            offers.add(new Offer(slot, cost, selected.clueId(), selected.clueLevel(), selected.enchantments()));
        }
        return new Report(item.copy(), enchantmentSeed, bookshelfCount, List.copyOf(offers), true);
    }

    /** Returns all enchanting-table enchantments supported by the supplied item. */
    public static List<EnchantmentOption> compatibleEnchantments(ItemStack item, RegistryAccess registryAccess) {
        if (item == null || item.isEmpty() || registryAccess == null) return List.of();
        Registry<Enchantment> registry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tableEnchantments = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tableEnchantments.isEmpty()) return List.of();

        boolean book = item.is(Items.BOOK);
        return tableEnchantments.get().stream()
            .filter(holder -> book || holder.value().isSupportedItem(item))
            .map(holder -> {
                String id = holder.unwrapKey()
                    .map(ResourceKey::identifier)
                    .map(Object::toString)
                    .orElse("unknown");
                return new EnchantmentOption(id, holder.value().description().getString(), holder.value().getMaxLevel());
            })
            .filter(option -> !option.id().equals("unknown"))
            .sorted(Comparator.comparing(EnchantmentOption::displayName).thenComparing(EnchantmentOption::id))
            .toList();
    }

    /** Uses vanilla's exclusive-set rules to compare two enchantments. */
    public static boolean areCompatible(String firstId, String secondId, RegistryAccess registryAccess) {
        if (firstId == null || secondId == null || registryAccess == null) return false;
        if (normalizeId(firstId).equals(normalizeId(secondId))) return true;
        try {
            Registry<Enchantment> registry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
            Optional<? extends Holder<Enchantment>> first = registry.get(Identifier.parse(normalizeId(firstId)));
            Optional<? extends Holder<Enchantment>> second = registry.get(Identifier.parse(normalizeId(secondId)));
            return first.isPresent() && second.isPresent()
                && Enchantment.areCompatible(first.get(), second.get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean requestsAreCompatible(List<RequestedEnchantment> requested,
                                                 RegistryAccess registryAccess) {
        if (requested == null || registryAccess == null) return false;
        for (int first = 0; first < requested.size(); first++) {
            for (int second = first + 1; second < requested.size(); second++) {
                if (!areCompatible(requested.get(first).id(), requested.get(second).id(), registryAccess)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Normalizes command input such as "sharpness" or "minecraft:sharpness". */
    public static String normalizeId(String value) {
        if (value == null) return "";
        String id = value.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return id;
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /**
     * Parses command text such as {@code sharpness 2, unbreaking 3}.
     *
     * <p>Comma-separated entries are preferred, but whitespace-only legacy input
     * remains valid ({@code sharpness unbreaking}). A numeric token immediately
     * after an enchantment is its exact requested level. The compact
     * {@code sharpness:2} and {@code sharpness=2} forms are accepted as well.</p>
     */
    public static List<RequestedEnchantment> parseRequested(String text) {
        if (text == null || text.isBlank()) return List.of();

        MapKeyedRequests requests = new MapKeyedRequests();
        for (String clause : text.split(",")) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) throw new IllegalArgumentException("empty enchantment entry");
            List<String> tokens = Arrays.stream(trimmed.split("\\s+"))
                .filter(token -> !token.isBlank()).toList();
            for (int index = 0; index < tokens.size(); index++) {
                String token = tokens.get(index);
                if (token.matches("\\d+")) {
                    throw new IllegalArgumentException("level " + token + " has no enchantment name");
                }

                String name = token;
                int level = 0;
                int separator = Math.max(token.lastIndexOf(':'), token.lastIndexOf('='));
                if (separator > 0 && separator < token.length() - 1
                    && token.substring(separator + 1).matches("\\d+")) {
                    name = token.substring(0, separator);
                    level = parseLevel(token.substring(separator + 1));
                } else if (index + 1 < tokens.size() && tokens.get(index + 1).matches("\\d+")) {
                    level = parseLevel(tokens.get(++index));
                }
                requests.put(new RequestedEnchantment(name, level));
            }
        }
        return requests.values();
    }

    private static int parseLevel(String value) {
        try {
            int level = Integer.parseInt(value);
            if (level < 1) throw new IllegalArgumentException("enchantment level must be at least 1");
            return level;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid enchantment level: " + value, exception);
        }
    }

    /** Preserves insertion order while removing duplicate id/level requests. */
    private static final class MapKeyedRequests {
        private final LinkedHashMap<String, RequestedEnchantment> values = new LinkedHashMap<>();

        void put(RequestedEnchantment request) {
            values.putIfAbsent(request.id() + "#" + request.level(), request);
        }

        List<RequestedEnchantment> values() {
            return List.copyOf(values.values());
        }
    }

    private record SelectedOffer(List<EnchantmentResult> enchantments, int clueId, int clueLevel) {
    }

    private static SelectedOffer selectOffer(int seed, ItemStack item, int slot, int cost,
                                             HolderSet.Named<Enchantment> enchantments,
                                             Registry<Enchantment> registry) {
        RandomSource random = RandomSource.create();
        // EnchantmentMenu resets its shared RNG to XpSeed + offer slot, not XpSeed + cost.
        // The vanilla menu uses int addition here. Do not widen before adding:
        // overflow is part of the XP-seed algorithm for negative/high seeds.
        random.setSeed(seed + slot);
        List<EnchantmentInstance> selected = EnchantmentHelper.selectEnchantment(
            random, item, cost, enchantments.stream());
        if (item.is(Items.BOOK) && selected.size() > 1) {
            selected.remove(random.nextInt(selected.size()));
        }

        List<EnchantmentResult> result = new ArrayList<>(selected.size());
        for (EnchantmentInstance instance : selected) {
            String id = instance.enchantment().unwrapKey()
                .map(ResourceKey::identifier)
                .map(Object::toString)
                .orElse("unknown");
            result.add(new EnchantmentResult(id, instance.level()));
        }
        int clueId = -1;
        int clueLevel = -1;
        if (!selected.isEmpty()) {
            EnchantmentInstance clue = selected.get(random.nextInt(selected.size()));
            clueId = registry.asHolderIdMap().getId(clue.enchantment());
            clueLevel = clue.level();
        }
        return new SelectedOffer(List.copyOf(result), clueId, clueLevel);
    }

    public static int bookshelfCount(Level level, Player player) {
        return findBookshelfCount(level, player);
    }

    private static int findBookshelfCount(Level level, Player player) {
        BlockPos center = player.blockPosition();
        BlockPos table = null;
        double best = Double.MAX_VALUE;
        for (int x = center.getX() - 8; x <= center.getX() + 8; x++) {
            for (int y = center.getY() - 4; y <= center.getY() + 4; y++) {
                for (int z = center.getZ() - 8; z <= center.getZ() + 8; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (!level.getBlockState(candidate).is(Blocks.ENCHANTING_TABLE)) continue;
                    double distance = player.distanceToSqr(candidate.getX() + 0.5, candidate.getY() + 0.5,
                        candidate.getZ() + 0.5);
                    if (distance < best) {
                        best = distance;
                        table = candidate;
                    }
                }
            }
        }
        if (table == null) return -1;

        int count = 0;
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, table, offset)) count++;
        }
        return count;
    }

    private static boolean sameId(String left, String right) {
        return normalizeId(left).equals(normalizeId(right));
    }

    private static String shortId(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }
}
