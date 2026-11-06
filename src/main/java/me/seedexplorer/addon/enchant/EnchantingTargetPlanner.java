package me.seedexplorer.addon.enchant;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnchantingTableBlock;

import java.util.List;
import java.util.Optional;

/** Searches deterministic bookshelf/offer combinations for the active player seed. */
public final class EnchantingTargetPlanner {
    public static final int DEFAULT_MAX_ITEM_THROWS = 49_152;
    private static final int MAX_BOOKSHELF_POWER = EnchantingTableBlock.BOOKSHELF_OFFSETS.size();

    private EnchantingTargetPlanner() {
    }

    public record Plan(int bookshelfCount, int slot,
                       EnchantingTablePredictor.Offer offer, boolean currentBookshelves,
                       boolean currentSeed, int itemThrows, int enchantmentSeed) {
        public Plan(int bookshelfCount, int slot, EnchantingTablePredictor.Offer offer,
                    boolean currentBookshelves) {
            this(bookshelfCount, slot, offer, currentBookshelves, true, 0, Integer.MIN_VALUE);
        }

        public String instruction() {
            if (currentSeed && currentBookshelves) {
                return "READY: click offer " + (slot + 1);
            }
            if (currentSeed) {
                return "Set bookshelves to " + bookshelfCount + "; click offer " + (slot + 1);
            }
            String setup = itemThrows == 0
                ? "Do one dummy enchant"
                : "Drop " + itemThrows + " item" + (itemThrows == 1 ? "" : "s")
                    + ", then do one dummy enchant";
            return setup + "; set bookshelves to " + bookshelfCount + "; click offer " + (slot + 1);
        }
    }

    /** Best reachable table roll when one roll cannot contain the full requested set. */
    public record PartialPlan(Plan base, List<EnchantingTablePredictor.RequestedEnchantment> matched,
                              List<EnchantingTablePredictor.RequestedEnchantment> missing) {
        public String instruction() {
            StringBuilder text = new StringBuilder(base.instruction());
            text.append(" for ").append(summary(matched));
            if (!missing.isEmpty()) {
                text.append("; get ").append(summary(missing))
                    .append(" on book").append(missing.size() == 1 ? "" : "s")
                    .append("; combine books first, then apply to the item");
            }
            return text.toString();
        }
    }

    /** One-pass future search result, retaining the best partial roll on exact-search failure. */
    public record FutureSearch(Plan exact, PartialPlan bestPartial) {
    }

    /**
     * Finds a current-seed plan. The active bookshelf count is checked first,
     * followed by every legal count supported by this Minecraft version.
     */
    public static Optional<Plan> find(ItemStack item, int enchantmentSeed, int currentBookshelves,
                                      List<EnchantingTablePredictor.RequestedEnchantment> requested,
                                      EnchantingTablePredictor.Report activeReport,
                                      RegistryAccess registryAccess) {
        if (item == null || item.isEmpty() || requested == null || requested.isEmpty()
            || registryAccess == null
            || !EnchantingTablePredictor.requestsAreCompatible(requested, registryAccess)) {
            return Optional.empty();
        }

        if (activeReport != null && currentBookshelves >= 0
            && activeReport.enchantmentSeed() == enchantmentSeed
            && activeReport.item() != null
            && ItemStack.matches(activeReport.item(), item)) {
            for (EnchantingTablePredictor.Offer offer : activeReport.offers()) {
                if (offer.hasAll(requested)) {
                    return Optional.of(new Plan(currentBookshelves, offer.slot(), offer, true));
                }
            }
        }

        for (int bookshelves = 0; bookshelves <= MAX_BOOKSHELF_POWER; bookshelves++) {
            if (bookshelves == currentBookshelves) continue;
            EnchantingTablePredictor.Report report = EnchantingTablePredictor.predictForItem(
                item, enchantmentSeed, bookshelves, registryAccess);
            if (report == null) continue;
            for (EnchantingTablePredictor.Offer offer : report.offers()) {
                if (offer.hasAll(requested)) {
                    return Optional.of(new Plan(bookshelves, offer.slot(), offer, false));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Searches future XP seeds produced by item drops and one dummy enchant.
     * Each dropped item consumes four calls from the player's Java LCG.
     */
    public static Optional<Plan> findFuture(ItemStack item, long playerSeed, int currentBookshelves,
                                            List<EnchantingTablePredictor.RequestedEnchantment> requested,
                                            RegistryAccess registryAccess, int maxItemThrows) {
        return Optional.ofNullable(searchFuture(item, playerSeed, currentBookshelves, requested,
            registryAccess, maxItemThrows).exact());
    }

    public static FutureSearch searchFuture(ItemStack item, long playerSeed, int currentBookshelves,
                                             List<EnchantingTablePredictor.RequestedEnchantment> requested,
                                             RegistryAccess registryAccess, int maxItemThrows) {
        if (item == null || item.isEmpty() || requested == null || requested.isEmpty()
            || registryAccess == null || maxItemThrows < 0
            || !EnchantingTablePredictor.requestsAreCompatible(requested, registryAccess)) {
            return new FutureSearch(null, null);
        }

        long stateAfterDrops = playerSeed & EnchantingPlayerRng.MASK;
        PartialPlan bestPartial = null;
        for (int itemThrows = 0; itemThrows <= maxItemThrows; itemThrows++) {
            long xpState = advance(stateAfterDrops);
            int xpSeed = (int) (xpState >>> 16);
            for (int bookshelves : bookshelfOrder(currentBookshelves)) {
                EnchantingTablePredictor.Report report = EnchantingTablePredictor.predictForItem(
                    item, xpSeed, bookshelves, registryAccess);
                if (report == null) continue;
                for (EnchantingTablePredictor.Offer offer : report.offers()) {
                    if (offer.hasAll(requested)) {
                        return new FutureSearch(new Plan(bookshelves, offer.slot(), offer,
                            bookshelves == currentBookshelves, false, itemThrows, xpSeed), bestPartial);
                    }
                    Plan candidate = new Plan(bookshelves, offer.slot(), offer,
                        bookshelves == currentBookshelves, false, itemThrows, xpSeed);
                    PartialPlan partial = partial(candidate, requested);
                    if (betterPartial(partial, bestPartial)) bestPartial = partial;
                }
            }
            for (int call = 0; call < 4; call++) {
                stateAfterDrops = advance(stateAfterDrops);
            }
        }
        return new FutureSearch(null, bestPartial);
    }

    /** Finds the strongest partial offer on the currently displayed XP seed. */
    public static Optional<PartialPlan> findBestPartial(ItemStack item, int enchantmentSeed,
                                                         int currentBookshelves,
                                                         List<EnchantingTablePredictor.RequestedEnchantment> requested,
                                                         EnchantingTablePredictor.Report activeReport,
                                                         RegistryAccess registryAccess) {
        if (item == null || item.isEmpty() || requested == null || requested.size() < 2
            || registryAccess == null
            || !EnchantingTablePredictor.requestsAreCompatible(requested, registryAccess)) {
            return Optional.empty();
        }

        PartialPlan best = null;
        for (int bookshelves : bookshelfOrder(currentBookshelves)) {
            EnchantingTablePredictor.Report report = bookshelves == currentBookshelves
                && activeReport != null && activeReport.enchantmentSeed() == enchantmentSeed
                ? activeReport
                : EnchantingTablePredictor.predictForItem(item, enchantmentSeed, bookshelves, registryAccess);
            if (report == null) continue;
            for (EnchantingTablePredictor.Offer offer : report.offers()) {
                PartialPlan partial = partial(new Plan(bookshelves, offer.slot(), offer,
                    bookshelves == currentBookshelves), requested);
                if (betterPartial(partial, best)) best = partial;
            }
        }
        return Optional.ofNullable(best);
    }

    private static PartialPlan partial(Plan plan,
                                       List<EnchantingTablePredictor.RequestedEnchantment> requested) {
        List<EnchantingTablePredictor.RequestedEnchantment> matched = requested.stream()
            .filter(request -> plan.offer().enchantments().stream().anyMatch(request::matches))
            .toList();
        if (matched.isEmpty() || matched.size() == requested.size()) return null;
        List<EnchantingTablePredictor.RequestedEnchantment> missing = requested.stream()
            .filter(request -> !matched.contains(request)).toList();
        return new PartialPlan(plan, List.copyOf(matched), List.copyOf(missing));
    }

    private static boolean betterPartial(PartialPlan candidate, PartialPlan current) {
        if (candidate == null) return false;
        if (current == null) return true;
        if (candidate.matched().size() != current.matched().size()) {
            return candidate.matched().size() > current.matched().size();
        }
        if (candidate.base().itemThrows() != current.base().itemThrows()) {
            return candidate.base().itemThrows() < current.base().itemThrows();
        }
        if (candidate.base().currentBookshelves() != current.base().currentBookshelves()) {
            return candidate.base().currentBookshelves();
        }
        return candidate.base().offer().cost() < current.base().offer().cost();
    }

    private static String summary(List<EnchantingTablePredictor.RequestedEnchantment> requests) {
        return requests.stream().map(EnchantingTablePredictor.RequestedEnchantment::display)
            .collect(java.util.stream.Collectors.joining(" + "));
    }

    private static int[] bookshelfOrder(int currentBookshelves) {
        int[] result = new int[MAX_BOOKSHELF_POWER + 1];
        int index = 0;
        if (currentBookshelves >= 0 && currentBookshelves <= MAX_BOOKSHELF_POWER) {
            result[index++] = currentBookshelves;
        }
        for (int bookshelves = 0; bookshelves <= MAX_BOOKSHELF_POWER; bookshelves++) {
            if (bookshelves != currentBookshelves) result[index++] = bookshelves;
        }
        return result;
    }

    private static long advance(long seed) {
        return (seed * EnchantingPlayerRng.MULTIPLIER + EnchantingPlayerRng.ADDEND)
            & EnchantingPlayerRng.MASK;
    }

    /** Chooses the least expensive available offer as a safe reroll suggestion. */
    public static String rerollInstruction(EnchantingTablePredictor.Report report) {
        if (report == null) return "Reroll seed with a dummy enchantment";
        return report.offers().stream()
            .filter(offer -> offer.cost() > 0)
            .min(java.util.Comparator.comparingInt(EnchantingTablePredictor.Offer::cost))
            .map(offer -> "Reroll: use a cheap dummy in offer " + (offer.slot() + 1)
                + " (cost " + offer.cost() + ")")
            .orElse("Reroll seed with a dummy enchantment");
    }
}
