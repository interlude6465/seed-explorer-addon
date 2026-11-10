package me.seedexplorer.standalone;

import me.seedexplorer.addon.enchant.EnchantingTablePredictor;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Resolves the hidden 32-bit XP seed from masked menu data and vanilla offer clues. */
public final class StandaloneEnchantingSeedCracker implements AutoCloseable {
    public enum State { UNKNOWN, CRACKING, CRACKED, INVALID }
    private static final int UNKNOWN = Integer.MIN_VALUE;
    private final StandaloneEnchantingRng playerRng;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer XP seed cracker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private volatile State state = State.UNKNOWN;
    private volatile int reportedSeed = UNKNOWN;
    private volatile int crackedSeed = UNKNOWN;
    private volatile int candidateCount;
    private volatile Snapshot active;
    private volatile int[] candidates;

    public StandaloneEnchantingSeedCracker(StandaloneEnchantingRng playerRng) {
        this.playerRng = playerRng;
    }

    public void observe(Level level, Player player, EnchantmentMenu menu) {
        if (level == null || player == null || menu == null) return;
        ItemStack item = menu.getSlot(0).getItem();
        int bookshelves = EnchantingTablePredictor.bookshelfCount(level, player);
        if (item.isEmpty() || !item.isEnchantable() || bookshelves < 0
            || menu.costs[0] == 0 && menu.costs[1] == 0 && menu.costs[2] == 0) return;
        Snapshot snapshot = new Snapshot(menu.getEnchantmentSeed(), bookshelves, item.copy(),
            menu.costs.clone(), menu.enchantClue.clone(), menu.levelClue.clone(), level.registryAccess());
        synchronized (this) {
            if (snapshot.sameAs(active)) return;
            int priorReported = reportedSeed;
            active = snapshot;
            reportedSeed = snapshot.reportedSeed();
            crackedSeed = UNKNOWN;
            state = State.CRACKING;
            int currentGeneration = generation.incrementAndGet();
            int[] source = priorReported == reportedSeed ? candidates : null;
            Integer expected = playerRng.expectedSeed(reportedSeed);
            if (expected != null) source = new int[]{expected};
            if (source == null) source = allCandidates(reportedSeed & 0xFFF0);
            candidates = source;
            candidateCount = source.length;
            int[] work = source;
            worker.submit(() -> crack(snapshot, currentGeneration, work));
        }
    }

    public Integer resolvedSeed(int menuSeed) {
        return state == State.CRACKED && reportedSeed == menuSeed && crackedSeed != UNKNOWN
            ? crackedSeed : null;
    }

    public State state() {
        return state;
    }

    public int candidateCount() {
        return candidateCount;
    }

    public synchronized void reset() {
        generation.incrementAndGet();
        state = State.UNKNOWN;
        reportedSeed = crackedSeed = UNKNOWN;
        candidateCount = 0;
        active = null;
        candidates = null;
    }

    private void crack(Snapshot snapshot, int currentGeneration, int[] source) {
        try {
            Registry<Enchantment> registry = snapshot.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            HolderSet.Named<Enchantment> allowed = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE).orElse(null);
            if (allowed == null) {
                finish(currentGeneration, State.INVALID, UNKNOWN, new int[0]);
                return;
            }
            int[] result = filter(snapshot, source, allowed, registry, currentGeneration);
            State next = result.length == 1 ? State.CRACKED
                : result.length == 0 ? State.INVALID : State.CRACKING;
            finish(currentGeneration, next, result.length == 1 ? result[0] : UNKNOWN, result);
            if (result.length == 1) playerRng.observeXpSeed(result[0]);
        } catch (Throwable ignored) {
            finish(currentGeneration, State.INVALID, UNKNOWN, new int[0]);
        }
    }

    private int[] filter(Snapshot snapshot, int[] source, HolderSet.Named<Enchantment> allowed,
                         Registry<Enchantment> registry, int currentGeneration) {
        RandomSource random = RandomSource.create();
        int[] matching = new int[Math.min(source.length, 1024)];
        int count = 0;
        for (int candidate : source) {
            if (generation.get() != currentGeneration) return new int[0];
            if (!matches(snapshot, candidate, random, allowed, registry)) continue;
            if (count == matching.length) matching = Arrays.copyOf(matching,
                Math.min(source.length, Math.max(1, matching.length * 2)));
            matching[count++] = candidate;
        }
        return Arrays.copyOf(matching, count);
    }

    private static boolean matches(Snapshot snapshot, int seed, RandomSource random,
                                   HolderSet.Named<Enchantment> allowed, Registry<Enchantment> registry) {
        random.setSeed(seed);
        int[] costs = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, snapshot.bookshelves(), snapshot.item());
            costs[slot] = cost < slot + 1 ? 0 : cost;
            if (costs[slot] != snapshot.costs()[slot]) return false;
        }
        for (int slot = 0; slot < 3; slot++) {
            if (costs[slot] <= 0) continue;
            random.setSeed(seed + slot);
            List<EnchantmentInstance> selected = EnchantmentHelper.selectEnchantment(
                random, snapshot.item(), costs[slot], allowed.stream());
            if (snapshot.item().is(Items.BOOK) && selected.size() > 1) {
                selected.remove(random.nextInt(selected.size()));
            }
            if (selected.isEmpty()) return snapshot.clues()[slot] == -1;
            EnchantmentInstance clue = selected.get(random.nextInt(selected.size()));
            if (registry.asHolderIdMap().getId(clue.enchantment()) != snapshot.clues()[slot]
                || clue.level() != snapshot.levels()[slot]) return false;
        }
        return true;
    }

    private synchronized void finish(int expectedGeneration, State next, int seed, int[] result) {
        if (generation.get() != expectedGeneration) return;
        state = next;
        crackedSeed = seed;
        candidates = result;
        candidateCount = result.length;
    }

    private static int[] allCandidates(int masked) {
        int[] result = new int[1 << 20];
        int index = 0;
        for (int high = 0; high < 65_536; high++) {
            for (int low = 0; low < 16; low++) result[index++] = high << 16 | masked | low;
        }
        return result;
    }

    @Override
    public void close() {
        reset();
        worker.shutdownNow();
    }

    private record Snapshot(int reportedSeed, int bookshelves, ItemStack item, int[] costs,
                            int[] clues, int[] levels, RegistryAccess registryAccess) {
        boolean sameAs(Snapshot other) {
            return other != null && reportedSeed == other.reportedSeed && bookshelves == other.bookshelves
                && ItemStack.matches(item, other.item) && Arrays.equals(costs, other.costs)
                && Arrays.equals(clues, other.clues) && Arrays.equals(levels, other.levels);
        }
    }
}
