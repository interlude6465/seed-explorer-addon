package me.seedexplorer.addon.enchant;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.orbit.EventHandler;
import me.seedexplorer.addon.debug.PredictionDebugLogger;

/** Tracks and cracks the player's Java LCG using consecutive enchanting seeds. */
public final class EnchantingPlayerRng {
    public enum State {
        UNKNOWN,
        WAITING_FIRST,
        HALF_CRACKED,
        WAITING_SECOND,
        CRACKED,
        DESYNCED
    }

    public static final long MULTIPLIER = 0x5deece66dL;
    public static final long ADDEND = 0xbL;
    public static final long MASK = (1L << 48) - 1;
    private static final int UNKNOWN_SEED = Integer.MIN_VALUE;
    private static final long UNKNOWN_PLAYER_SEED = Long.MIN_VALUE;
    private static final Object LOCK = new Object();
    private static final EnchantingPlayerRng EVENTS = new EnchantingPlayerRng();

    private static volatile State state = State.UNKNOWN;
    private static volatile int currentXpSeed = UNKNOWN_SEED;
    private static volatile int firstConsecutiveXpSeed = UNKNOWN_SEED;
    private static volatile int expectedXpSeed = UNKNOWN_SEED;
    private static volatile long playerSeed = UNKNOWN_PLAYER_SEED;
    private static volatile long dropCount;
    private static volatile int lastServerXpSeed = UNKNOWN_SEED;
    private static volatile int pendingBaseXpSeed = UNKNOWN_SEED;
    private static volatile int pendingExpectedXpSeed = UNKNOWN_SEED;
    private static volatile long pendingSinceNanos;

    /* A missing confirmation means the client and server state is ambiguous. */
    private static final long PENDING_TIMEOUT_NANOS = 8_000_000_000L;

    private EnchantingPlayerRng() {
    }

    public static void registerEvents() {
        MeteorClient.EVENT_BUS.subscribe(EVENTS);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    public static void reset() {
        synchronized (LOCK) {
            state = State.UNKNOWN;
            currentXpSeed = UNKNOWN_SEED;
            firstConsecutiveXpSeed = UNKNOWN_SEED;
            expectedXpSeed = UNKNOWN_SEED;
            playerSeed = UNKNOWN_PLAYER_SEED;
            dropCount = 0L;
            lastServerXpSeed = UNKNOWN_SEED;
            pendingBaseXpSeed = UNKNOWN_SEED;
            pendingExpectedXpSeed = UNKNOWN_SEED;
            pendingSinceNanos = 0L;
        }
    }

    /** Records a resolved menu seed and advances the two-seed recovery state. */
    public static void onXpSeedResolved(int xpSeed) {
        synchronized (LOCK) {
            currentXpSeed = xpSeed;

            // Keep the confirmed prediction available long enough for the seed cracker to use it
            // as a one-candidate filter, then retire it when that observation is resolved.
            if (state == State.CRACKED && expectedXpSeed != UNKNOWN_SEED
                && (xpSeed & 0xFFFFFFF0) == (expectedXpSeed & 0xFFFFFFF0)) {
                expectedXpSeed = UNKNOWN_SEED;
            }

            if (state == State.WAITING_FIRST) {
                firstConsecutiveXpSeed = xpSeed;
                state = State.HALF_CRACKED;
            } else if (state == State.WAITING_SECOND) {
                Long recovered = recoverState(firstConsecutiveXpSeed, xpSeed);
                if (recovered == null) {
                    state = State.DESYNCED;
                    playerSeed = UNKNOWN_PLAYER_SEED;
                    firstConsecutiveXpSeed = UNKNOWN_SEED;
                } else {
                    playerSeed = recovered;
                    state = State.CRACKED;
                    firstConsecutiveXpSeed = UNKNOWN_SEED;
                }
            }
        }
    }

    /** Marks a client request; the player LCG is advanced only after server confirmation. */
    public static void onEnchantmentRequested() {
        synchronized (LOCK) {
            if (state == State.CRACKED) {
                if (pendingExpectedXpSeed != UNKNOWN_SEED) {
                    PredictionDebugLogger.append("event=enchant_request_duplicate\n");
                    return;
                }
                pendingBaseXpSeed = lastServerXpSeed != UNKNOWN_SEED ? lastServerXpSeed : currentXpSeed;
                pendingExpectedXpSeed = nextInt(playerSeed);
                expectedXpSeed = pendingExpectedXpSeed;
                pendingSinceNanos = System.nanoTime();
                PredictionDebugLogger.append("event=enchant_request"
                    + " base=" + pendingBaseXpSeed
                    + " expected=" + pendingExpectedXpSeed + "\n");
                return;
            }
            if (firstConsecutiveXpSeed == UNKNOWN_SEED) {
                state = State.WAITING_FIRST;
            } else {
                state = State.WAITING_SECOND;
            }
        }
    }

    /**
     * Handles the authoritative enchantment-seed value delivered through a container data packet.
     * A client-side EnchantmentMenu click mutates a local copy optimistically, so this hook is the
     * first point at which we know that the server accepted (or rejected) the request.
     */
    public static void onServerXpSeedUpdate(int xpSeed) {
        synchronized (LOCK) {
            currentXpSeed = xpSeed;
            lastServerXpSeed = xpSeed;
            if (state != State.CRACKED || pendingExpectedXpSeed == UNKNOWN_SEED) return;

            int expected = pendingExpectedXpSeed;
            int base = pendingBaseXpSeed;
            // Container data exposes the low 16 bits; the offer clues recover the remaining bits.
            if ((xpSeed & 0x0000FFF0) == (expected & 0x0000FFF0)) {
                playerSeed = advance(playerSeed);
                clearPending();
                PredictionDebugLogger.append("event=enchant_confirmed reported=" + xpSeed
                    + " expected=" + expected + "\n");
                return;
            }
            if (xpSeed == base) {
                clearPending();
                expectedXpSeed = UNKNOWN_SEED;
                PredictionDebugLogger.append("event=enchant_rejected_or_unchanged reported=" + xpSeed + "\n");
                return;
            }

            state = State.DESYNCED;
            playerSeed = UNKNOWN_PLAYER_SEED;
            firstConsecutiveXpSeed = UNKNOWN_SEED;
            clearPending();
            expectedXpSeed = UNKNOWN_SEED;
            PredictionDebugLogger.append("event=enchant_seed_mismatch reported=" + xpSeed
                + " expected=" + expected + " base=" + base + "\n");
        }
    }

    /** Accounts for the four player-RNG calls made by a vanilla item drop. */
    public static void onItemDropped() {
        synchronized (LOCK) {
            expirePendingIfNeeded();
            if (pendingExpectedXpSeed != UNKNOWN_SEED) {
                state = State.DESYNCED;
                playerSeed = UNKNOWN_PLAYER_SEED;
                firstConsecutiveXpSeed = UNKNOWN_SEED;
                clearPending();
                expectedXpSeed = UNKNOWN_SEED;
                PredictionDebugLogger.append("event=enchant_drop_while_pending\n");
                return;
            }
            if (state == State.CRACKED) {
                for (int call = 0; call < 4; call++) {
                    playerSeed = advance(playerSeed);
                }
                dropCount++;
                PredictionDebugLogger.append("event=enchant_item_drop count=" + dropCount
                    + " playerSeed=" + playerSeed + "\n");
            } else if (state == State.WAITING_FIRST || state == State.WAITING_SECOND
                || state == State.HALF_CRACKED) {
                state = State.DESYNCED;
                playerSeed = UNKNOWN_PLAYER_SEED;
                firstConsecutiveXpSeed = UNKNOWN_SEED;
                expectedXpSeed = UNKNOWN_SEED;
            }
        }
    }

    /** Monotonic count of tracked item-drop actions since the current game session began. */
    public static long dropCount() {
        return dropCount;
    }

    /** Returns a predicted next XP seed when the player RNG is fully known. */
    public static Integer expectedSeed(int reportedSeed) {
        expirePendingIfNeeded();
        int expected = expectedXpSeed;
        return expected != UNKNOWN_SEED
            && (expected & 0x0000FFF0) == (reportedSeed & 0x0000FFF0)
            ? expected : null;
    }

    public static State state() {
        return state;
    }

    public static int currentXpSeed() {
        return currentXpSeed;
    }

    public static Long playerSeed() {
        long seed = playerSeed;
        return seed == UNKNOWN_PLAYER_SEED ? null : seed;
    }

    public static boolean enchantmentPending() {
        synchronized (LOCK) {
            expirePendingIfNeeded();
            return pendingExpectedXpSeed != UNKNOWN_SEED;
        }
    }

    /** Regression hook for the request/confirmation protocol. */
    public static boolean verifyPendingConfirmationForTesting(long initialSeed) {
        long baseState = initialSeed & MASK;
        int baseXpSeed = 0x13572460;
        reset();
        synchronized (LOCK) {
            state = State.CRACKED;
            playerSeed = baseState;
            currentXpSeed = baseXpSeed;
            lastServerXpSeed = baseXpSeed;
        }
        onEnchantmentRequested();
        long unchangedSeed = playerSeed;
        int expected = nextInt(baseState);
        onXpSeedResolved(expected);
        boolean held = enchantmentPending() && playerSeed() != null && playerSeed() == unchangedSeed;
        onServerXpSeedUpdate(baseXpSeed);
        boolean rejected = !enchantmentPending() && playerSeed() != null && playerSeed() == unchangedSeed;

        onEnchantmentRequested();
        expected = nextInt(unchangedSeed);
        onServerXpSeedUpdate((short) expected);
        boolean confirmed = !enchantmentPending() && playerSeed() != null
            && playerSeed() == advance(unchangedSeed);

        onEnchantmentRequested();
        onServerXpSeedUpdate(((short) nextInt(advance(unchangedSeed))) ^ 0x100);
        boolean mismatch = state() == State.DESYNCED && playerSeed() == null;
        reset();
        return held && rejected && confirmed && mismatch;
    }

    private static void expirePendingIfNeeded() {
        if (pendingExpectedXpSeed == UNKNOWN_SEED || pendingSinceNanos == 0L
            || System.nanoTime() - pendingSinceNanos < PENDING_TIMEOUT_NANOS) return;
        int expected = pendingExpectedXpSeed;
        state = State.DESYNCED;
        playerSeed = UNKNOWN_PLAYER_SEED;
        firstConsecutiveXpSeed = UNKNOWN_SEED;
        clearPending();
        expectedXpSeed = UNKNOWN_SEED;
        PredictionDebugLogger.append("event=enchant_confirmation_timeout expected=" + expected + "\n");
    }

    private static void clearPending() {
        pendingBaseXpSeed = UNKNOWN_SEED;
        pendingExpectedXpSeed = UNKNOWN_SEED;
        pendingSinceNanos = 0L;
    }

    /** Regression hook for the LCG interval-recovery math. */
    public static boolean verifyRecoveryForTesting(long initialSeed) {
        long firstState = advance(initialSeed & MASK);
        long secondState = advance(firstState);
        int first = (int) (firstState >>> 16);
        int second = (int) (secondState >>> 16);
        Long recovered = recoverState(first, second);
        return recovered != null && recovered == secondState;
    }

    private static long advance(long seed) {
        return (seed * MULTIPLIER + ADDEND) & MASK;
    }

    private static int nextInt(long seed) {
        return (int) (advance(seed) >>> 16);
    }

    /** Exact interval-recovery math used by ClientCommands' EnchantmentCracker. */
    private static Long recoverState(int first, int second) {
        long max1 = Integer.toUnsignedLong(first) + 1;
        long min1 = Integer.toUnsignedLong(first);
        long max2 = Integer.toUnsignedLong(second) + 1;
        long a = (24667315L * max1 + 18218081L * max2) >> 32;
        long b = (-4824621L * min1 + 7847617L * max2) >> 32;

        long candidate = (7847617L * a - 18218081L * b) & MASK;
        if ((int) (candidate >>> 16) != first) return null;
        candidate = advance(candidate);
        if ((int) (candidate >>> 16) != second) return null;
        return candidate;
    }
}
