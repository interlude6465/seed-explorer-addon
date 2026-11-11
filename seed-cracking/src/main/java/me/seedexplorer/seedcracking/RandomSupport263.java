package me.seedexplorer.seedcracking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Exact 26.1-26.3 {@code RandomSupport} seed helpers used by the Xoroshiro
 * random source.
 *
 * <p>Bytecode-verified against 26.1.2, 26.2, and 26.3:</p>
 * <ul>
 *   <li>{@code upgradeSeedTo128bit(long)} XORs the silver ratio constant, adds
 *       the golden ratio constant, then applies the Stafford mix-13 finalizer
 *       to both halves.</li>
 *   <li>{@code seedFromHashOf(String)} takes the first 16 bytes of the UTF-8
 *       MD5 digest as two big-endian longs.</li>
 * </ul>
 */
public final class RandomSupport263 {
    /** 0x9E3779B97F4A7C15. */
    public static final long GOLDEN_GAMMA = -7046029254386353131L;
    /** 0x6A09E667F3BCC909. */
    public static final long SILVER_GAMMA = 7640891576956012809L;

    private static final long MIX_MULTIPLIER_1 = -4658895280553007687L; // 0xBF58476D1CE4E5B9
    private static final long MIX_MULTIPLIER_2 = -7723592293110705685L; // 0x94D049BB133111EB

    private RandomSupport263() {
    }

    public record Seed128(long lo, long hi) {
        public Seed128 xor(long otherLo, long otherHi) {
            return new Seed128(lo ^ otherLo, hi ^ otherHi);
        }

        public Seed128 xor(Seed128 other) {
            return xor(other.lo, other.hi);
        }

        public Seed128 mixed() {
            return new Seed128(mixStafford13(lo), mixStafford13(hi));
        }
    }

    public static long mixStafford13(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * MIX_MULTIPLIER_1;
        mixed = (mixed ^ (mixed >>> 27)) * MIX_MULTIPLIER_2;
        return mixed ^ (mixed >>> 31);
    }

    public static Seed128 upgradeSeedTo128bitUnmixed(long seed) {
        long lo = seed ^ SILVER_GAMMA;
        long hi = lo + GOLDEN_GAMMA;
        return new Seed128(lo, hi);
    }

    public static Seed128 upgradeSeedTo128bit(long seed) {
        return upgradeSeedTo128bitUnmixed(seed).mixed();
    }

    /** MD5-based 128-bit seed used by {@code PositionalRandomFactory.fromHashOf}. */
    public static Seed128 seedFromHashOf(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new Seed128(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 is required by the Java platform", impossible);
        }
    }
}
