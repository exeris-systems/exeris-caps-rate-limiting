package eu.exeris.caps.ratelimit.internal;

/**
 * Zero-allocation 64-bit hasher for CharSequence and primitive values.
 * Uses a 64-bit mix function inspired by MurmurHash3 / SplitMix64.
 */
public final class ZeroAllocHasher {

    private static final long SEED = 0x9e3779b97f4a7c15L;
    private static final long M1 = 0xff51afd7ed558ccdL;
    private static final long M2 = 0xc4ceb9fe1a85ec53L;

    private ZeroAllocHasher() {
    }

    /**
     * Hashes a CharSequence without allocating String objects.
     */
    public static long hash(CharSequence cs) {
        return hash(cs, 0L);
    }

    /**
     * Hashes a CharSequence under a caller-held secret seed, so the hash of a given input is not
     * reproducible by a party that does not know the seed.
     */
    public static long hash(CharSequence cs, long seed) {
        if (cs == null || cs.isEmpty()) {
            long empty = fmix64(SEED ^ seed);
            return empty == 0L ? 1L : empty;
        }

        long h = SEED ^ fmix64(seed) ^ cs.length();
        int len = cs.length();
        for (int i = 0; i < len; i++) {
            h ^= cs.charAt(i);
            h *= M1;
            h = Long.rotateLeft(h, 31);
        }

        long result = fmix64(h);
        return result == 0L ? 1L : result;
    }

    /**
     * Mixes a 64-bit primitive key.
     */
    public static long hash(long key) {
        long result = fmix64(key ^ SEED);
        return result == 0L ? 1L : result;
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= M1;
        k ^= k >>> 33;
        k *= M2;
        k ^= k >>> 33;
        return k;
    }
}
