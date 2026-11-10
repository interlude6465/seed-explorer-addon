package me.seedexplorer.standalone;

/** Meteor-free Java LCG tracker driven by enchanting and item-drop hooks. */
public final class StandaloneEnchantingRng {
    public enum State { UNKNOWN, WAITING_FIRST, HALF_CRACKED, WAITING_SECOND, CRACKED, DESYNCED }

    static final long MULTIPLIER = 0x5deece66dL;
    static final long ADDEND = 0xbL;
    static final long MASK = (1L << 48) - 1;
    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final long PENDING_TIMEOUT_NANOS = 8_000_000_000L;

    private State state = State.UNKNOWN;
    private int currentXpSeed = UNKNOWN;
    private int firstXpSeed = UNKNOWN;
    private int pendingBase = UNKNOWN;
    private int pendingExpected = UNKNOWN;
    private long pendingSince;
    private Long playerSeed;
    private long dropCount;

    public synchronized void reset() {
        state = State.UNKNOWN;
        currentXpSeed = firstXpSeed = pendingBase = pendingExpected = UNKNOWN;
        pendingSince = 0L;
        playerSeed = null;
        dropCount = 0L;
    }

    public synchronized void observeXpSeed(int xpSeed) {
        expirePending();
        currentXpSeed = xpSeed;
        if (state == State.WAITING_FIRST) {
            firstXpSeed = xpSeed;
            state = State.HALF_CRACKED;
        } else if (state == State.WAITING_SECOND) {
            playerSeed = recoverState(firstXpSeed, xpSeed);
            state = playerSeed == null ? State.DESYNCED : State.CRACKED;
            firstXpSeed = UNKNOWN;
        }
    }

    public synchronized void enchantmentRequested() {
        expirePending();
        if (state == State.CRACKED && playerSeed != null) {
            if (pendingExpected != UNKNOWN) return;
            pendingBase = currentXpSeed;
            pendingExpected = nextInt(playerSeed);
            pendingSince = System.nanoTime();
            return;
        }
        state = firstXpSeed == UNKNOWN ? State.WAITING_FIRST : State.WAITING_SECOND;
    }

    public synchronized void serverXpSeedUpdate(int reportedSeed) {
        expirePending();
        currentXpSeed = reportedSeed;
        if (state != State.CRACKED || playerSeed == null || pendingExpected == UNKNOWN) return;
        if ((reportedSeed & 0xFFF0) == (pendingExpected & 0xFFF0)) {
            playerSeed = advance(playerSeed);
            clearPending();
        } else if (reportedSeed == pendingBase) {
            clearPending();
        } else {
            state = State.DESYNCED;
            playerSeed = null;
            clearPending();
        }
    }

    public synchronized void itemDropped() {
        expirePending();
        if (pendingExpected != UNKNOWN) {
            state = State.DESYNCED;
            playerSeed = null;
            clearPending();
            return;
        }
        if (state == State.CRACKED && playerSeed != null) {
            for (int i = 0; i < 4; i++) playerSeed = advance(playerSeed);
            dropCount++;
        } else if (state == State.HALF_CRACKED || state == State.WAITING_FIRST
            || state == State.WAITING_SECOND) {
            state = State.DESYNCED;
            playerSeed = null;
            firstXpSeed = UNKNOWN;
        }
    }

    public synchronized Snapshot snapshot() {
        expirePending();
        return new Snapshot(state, currentXpSeed, playerSeed, pendingExpected == UNKNOWN ? null : pendingExpected,
            dropCount);
    }

    public synchronized Integer expectedSeed(int reportedSeed) {
        expirePending();
        return pendingExpected != UNKNOWN
            && (pendingExpected & 0xFFF0) == (reportedSeed & 0xFFF0)
            ? pendingExpected : null;
    }

    private void expirePending() {
        if (pendingExpected == UNKNOWN || pendingSince == 0L
            || System.nanoTime() - pendingSince < PENDING_TIMEOUT_NANOS) return;
        state = State.DESYNCED;
        playerSeed = null;
        clearPending();
    }

    private void clearPending() {
        pendingBase = pendingExpected = UNKNOWN;
        pendingSince = 0L;
    }

    static long advance(long seed) {
        return (seed * MULTIPLIER + ADDEND) & MASK;
    }

    static int nextInt(long seed) {
        return (int) (advance(seed) >>> 16);
    }

    static Long recoverState(int first, int second) {
        long max1 = Integer.toUnsignedLong(first) + 1;
        long min1 = Integer.toUnsignedLong(first);
        long max2 = Integer.toUnsignedLong(second) + 1;
        long a = (24667315L * max1 + 18218081L * max2) >> 32;
        long b = (-4824621L * min1 + 7847617L * max2) >> 32;
        long candidate = (7847617L * a - 18218081L * b) & MASK;
        if ((int) (candidate >>> 16) != first) return null;
        candidate = advance(candidate);
        return (int) (candidate >>> 16) == second ? candidate : null;
    }

    public record Snapshot(State state, int currentXpSeed, Long playerSeed,
                           Integer expectedXpSeed, long dropCount) {
    }
}
