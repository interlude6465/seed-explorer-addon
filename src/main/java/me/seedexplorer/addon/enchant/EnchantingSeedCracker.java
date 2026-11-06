package me.seedexplorer.addon.enchant;

import me.seedexplorer.addon.debug.PredictionDebugLogger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Recovers the full XP seed from the masked value and vanilla offer clues. */
public final class EnchantingSeedCracker {
    public enum State {
        UNKNOWN,
        CRACKING,
        CRACKED,
        INVALID
    }

    private static final int UNKNOWN_SEED = Integer.MIN_VALUE;
    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Seed Explorer enchantment seed cracker");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static volatile int reportedSeed = UNKNOWN_SEED;
    private static volatile int crackedSeed = UNKNOWN_SEED;
    private static volatile State state = State.UNKNOWN;
    private static volatile Snapshot activeSnapshot;
    private static volatile int[] candidateSeeds;

    private EnchantingSeedCracker() {
    }

    /** Starts or refreshes an off-thread crack using the local player for bookshelf detection. */
    public static void observe(Level level, net.minecraft.world.entity.player.Player player,
                               EnchantmentMenu menu) {
        if (level == null || player == null || menu == null) return;
        ItemStack item = menu.getSlot(0).getItem();
        int bookshelfCount = EnchantingTablePredictor.bookshelfCount(level, player);
        if (item.isEmpty() || !item.isEnchantable() || bookshelfCount < 0) {
            return;
        }

        // Item swaps are sent before the server's offer packet arrives. During
        // that short window the menu contains three zero costs and no clues.
        // ClientCommands avoids this by requiring a manual "add info" click;
        // the automatic overlay must explicitly ignore the pending state.
        if (menu.costs[0] == 0 && menu.costs[1] == 0 && menu.costs[2] == 0) return;

        Snapshot snapshot = new Snapshot(menu.getEnchantmentSeed(), bookshelfCount, item.copy(),
            menu.costs.clone(), menu.enchantClue.clone(), menu.levelClue.clone(), level.registryAccess());
        synchronized (LOCK) {
            if (snapshot.sameAs(activeSnapshot)) return;
            int previousReportedSeed = reportedSeed;
            activeSnapshot = snapshot;
            reportedSeed = snapshot.reportedSeed();
            crackedSeed = UNKNOWN_SEED;
            state = State.CRACKING;
            int generation = GENERATION.incrementAndGet();
            int[] priorCandidates = candidateSeeds;
            if (priorCandidates == null || previousReportedSeed != snapshot.reportedSeed()) {
                priorCandidates = null;
                candidateSeeds = null;
            }
            int[] sourceCandidates = priorCandidates;
            // Make the work visible immediately. The first observation scans all
            // 2^20 candidates; leaving this null looks like a hung cracker even
            // though the worker is active.
            if (sourceCandidates == null) {
                sourceCandidates = allCandidates(snapshot.reportedSeed() & 0x0000FFF0);
                candidateSeeds = sourceCandidates;
            }
            Integer expectedSeed = EnchantingPlayerRng.expectedSeed(snapshot.reportedSeed());
            if (expectedSeed != null) {
                sourceCandidates = new int[]{expectedSeed};
                candidateSeeds = sourceCandidates;
            }
            PredictionDebugLogger.append("event=enchant_seed_observation"
                + " reported=" + snapshot.reportedSeed()
                + " bookshelves=" + snapshot.bookshelfCount()
                + " item=" + snapshot.item().getItem()
                + " costs=" + Arrays.toString(snapshot.costs())
                + " clues=" + Arrays.toString(snapshot.enchantClue())
                + " levels=" + Arrays.toString(snapshot.levelClue())
                + " priorCandidates=" + (priorCandidates == null ? 0 : priorCandidates.length)
                + " sourceCandidates=" + sourceCandidates.length + "\n");
            int[] crackCandidates = sourceCandidates;
            WORKER.submit(() -> crack(snapshot, generation, crackCandidates));
        }
    }

    public static Integer resolvedSeed(int menuSeed) {
        return state == State.CRACKED && reportedSeed == menuSeed && crackedSeed != UNKNOWN_SEED
            ? crackedSeed : null;
    }

    public static State state() {
        return state;
    }

    public static int candidateCount() {
        int[] candidates = candidateSeeds;
        return candidates == null ? 0 : candidates.length;
    }

    private static void crack(Snapshot snapshot, int generation, int[] priorCandidates) {
        long started = System.nanoTime();
        try {
            Registry<Enchantment> registry = snapshot.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            HolderSet.Named<Enchantment> tableEnchantments = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE)
                .orElse(null);
            if (tableEnchantments == null) {
                finish(generation, State.INVALID, UNKNOWN_SEED, new int[0]);
                return;
            }
            int masked = snapshot.reportedSeed() & 0x0000FFF0;
            int[] source = priorCandidates == null ? allCandidates(masked) : priorCandidates;
            int[] result = filterCandidates(snapshot, source, tableEnchantments, registry, generation);
            if (GENERATION.get() != generation) return;
            finish(generation, result.length == 1 ? State.CRACKED
                : result.length == 0 ? State.INVALID : State.CRACKING,
                result.length == 1 ? result[0] : UNKNOWN_SEED, result);
            if (result.length == 1) {
                EnchantingPlayerRng.onXpSeedResolved(result[0]);
            }
            PredictionDebugLogger.append("event=enchant_seed_crack state="
                + state.name().toLowerCase(java.util.Locale.ROOT) + " candidates=" + result.length
                + " reported=" + snapshot.reportedSeed() + " resolved="
                + (result.length == 1 ? result[0] : "unknown")
                + " elapsedMs=" + ((System.nanoTime() - started) / 1_000_000L) + "\n");
        } catch (Throwable throwable) {
            finish(generation, State.INVALID, UNKNOWN_SEED, new int[0]);
            PredictionDebugLogger.exception("event=enchant_seed_crack_error", throwable);
        }
    }

    private static int[] allCandidates(int masked) {
        int[] candidates = new int[1 << 20];
        int index = 0;
        for (int highBits = 0; highBits < 65536; highBits++) {
            for (int lowBits = 0; lowBits < 16; lowBits++) {
                candidates[index++] = (highBits << 16) | masked | lowBits;
            }
        }
        return candidates;
    }

    /**
     * Applies one observed menu state to a candidate array. Keeping this path
     * separate makes the in-game worker and offline regression probes exercise
     * exactly the same filtering code.
     */
    public static int[] filterCandidatesForTesting(int reportedSeed, int bookshelfCount, ItemStack item,
                                                    int[] costs, int[] enchantClue, int[] levelClue,
                                                    RegistryAccess registryAccess, int[] priorCandidates) {
        if (item == null || item.isEmpty() || costs == null || costs.length != 3
            || enchantClue == null || enchantClue.length != 3 || levelClue == null || levelClue.length != 3
            || registryAccess == null) {
            return new int[0];
        }
        Registry<Enchantment> registry = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        HolderSet.Named<Enchantment> tableEnchantments = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE)
            .orElseThrow(() -> new IllegalStateException("missing enchanting-table enchantment tag"));
        Snapshot snapshot = new Snapshot(reportedSeed, bookshelfCount, item.copy(), costs.clone(),
            enchantClue.clone(), levelClue.clone(), registryAccess);
        int[] source = priorCandidates == null
            ? allCandidates(reportedSeed & 0x0000FFF0)
            : priorCandidates.clone();
        return filterCandidates(snapshot, source, tableEnchantments, registry, 0);
    }

    private static int[] filterCandidates(Snapshot snapshot, int[] source,
                                           HolderSet.Named<Enchantment> tableEnchantments,
                                           Registry<Enchantment> registry, int generation) {
        RandomSource random = RandomSource.create();
        int[] matching = new int[Math.min(source.length, 1024)];
        int count = 0;
        for (int candidate : source) {
            if (generation != 0 && GENERATION.get() != generation) return new int[0];
            if (!matches(snapshot, candidate, random, tableEnchantments, registry)) continue;
            if (count == matching.length) {
                matching = Arrays.copyOf(matching, Math.min(source.length, matching.length * 2));
            }
            matching[count++] = candidate;
        }
        return Arrays.copyOf(matching, count);
    }

    private static boolean matches(Snapshot snapshot, int seed, RandomSource random,
                                   HolderSet.Named<Enchantment> tableEnchantments,
                                   Registry<Enchantment> registry) {
        random.setSeed(seed);
        int[] costs = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, snapshot.bookshelfCount(), snapshot.item());
            costs[slot] = cost < slot + 1 ? 0 : cost;
            if (costs[slot] != snapshot.costs()[slot]) return false;
        }

        for (int slot = 0; slot < 3; slot++) {
            int cost = costs[slot];
            if (cost <= 0) {
                if (snapshot.enchantClue()[slot] != -1 || snapshot.levelClue()[slot] != -1) return false;
                continue;
            }
            // ClientCommands/vanilla add the slot as an int before widening to
            // the RandomSource long seed. Preserve the signed 32-bit overflow.
            random.setSeed(seed + slot);
            List<EnchantmentInstance> selected = EnchantmentHelper.selectEnchantment(
                random, snapshot.item(), cost, tableEnchantments.stream());
            if (snapshot.item().is(Items.BOOK) && selected.size() > 1) {
                selected.remove(random.nextInt(selected.size()));
            }
            if (selected.isEmpty()) {
                if (snapshot.enchantClue()[slot] != -1 || snapshot.levelClue()[slot] != -1) return false;
                continue;
            }
            EnchantmentInstance clue = selected.get(random.nextInt(selected.size()));
            int clueId = registry.asHolderIdMap().getId(clue.enchantment());
            if (clueId != snapshot.enchantClue()[slot] || clue.level() != snapshot.levelClue()[slot]) {
                return false;
            }
        }
        return true;
    }

    private static void finish(int generation, State nextState, int nextSeed, int[] nextCandidates) {
        if (GENERATION.get() != generation) return;
        state = nextState;
        crackedSeed = nextSeed;
        candidateSeeds = nextCandidates;
    }

    private record Snapshot(int reportedSeed, int bookshelfCount, ItemStack item,
                            int[] costs, int[] enchantClue, int[] levelClue,
                            RegistryAccess registryAccess) {
        boolean sameAs(Snapshot prior) {
            return prior != null
                && prior.reportedSeed() == reportedSeed
                && prior.bookshelfCount() == bookshelfCount
                && ItemStack.matches(prior.item(), item)
                && java.util.Arrays.equals(prior.costs(), costs)
                && java.util.Arrays.equals(prior.enchantClue(), enchantClue)
                && java.util.Arrays.equals(prior.levelClue(), levelClue);
        }
    }
}
