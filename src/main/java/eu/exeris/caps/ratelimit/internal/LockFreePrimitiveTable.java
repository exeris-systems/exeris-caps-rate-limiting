package eu.exeris.caps.ratelimit.internal;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size, lock-free open-addressing hash table storing primitive 64-bit keys and values.
 *
 * <p>Uses linear probing with a bounded hop count. Dead/idle slots whose value is older
 * than {@code maxIdleNanos} are reclaimed lock-free during probing, preventing table
 * saturation without requiring background cleanup threads or GC allocation.
 *
 * <p>The home slot of a key is derived from a per-instance random seed, so the placement of keys
 * cannot be predicted or aimed at one probe sequence from outside the process. A key is only ever
 * given a slot it owns: when the probe window holds no free or reclaimable slot the lookup returns
 * {@link #NO_SLOT} and the caller must not read or write shared state for that key.
 */
public class LockFreePrimitiveTable {

    public static final int NO_SLOT = -1;
    private static final int MAX_PROBE_HOPS = 16;

    private final AtomicLongArray keys;
    private final AtomicLongArray values;
    private final int mask;
    private final int capacity;
    private final long maxIdleNanos;
    private final long seed;

    public LockFreePrimitiveTable(int initialCapacity, long maxIdleNanos) {
        this(initialCapacity, maxIdleNanos, new SecureRandom().nextLong());
    }

    /** Test seam: the caller fixes the placement seed, which makes slot placement reproducible. */
    LockFreePrimitiveTable(int initialCapacity, long maxIdleNanos, long seed) {
        int cap = Math.max(16, roundToPowerOfTwo(initialCapacity));
        this.capacity = cap;
        this.mask = cap - 1;
        this.keys = new AtomicLongArray(cap);
        this.values = new AtomicLongArray(cap);
        this.maxIdleNanos = Math.max(1_000_000_000L, maxIdleNanos); // at least 1s
        this.seed = seed;
    }

    /**
     * Resolves the slot index for the given keyHash.
     *
     * @param keyHash 64-bit hash; zero is the empty-slot marker and is remapped
     * @param nowNanos current timestamp in nanoseconds
     * @return slot index in range [0, capacity - 1] owned by {@code keyHash}, or {@link #NO_SLOT}
     *         when no slot in the probe window is free, reclaimable or already owned by this key
     */
    public int findOrAllocateSlot(long keyHash, long nowNanos) {
        if (keyHash == 0L) {
            keyHash = Long.MIN_VALUE;
        }
        int baseSlot = (int) mix(keyHash ^ seed) & mask;

        for (int hop = 0; hop < MAX_PROBE_HOPS; hop++) {
            int slot = (baseSlot + hop) & mask;
            // The value is read before the key and refreshed from that observation, before the key is
            // published: a concurrent update by the slot's current user makes the refresh fail instead
            // of being overwritten, and a slot never carries its new key with the previous key's stale
            // value.
            long observed = values.get(slot);
            long existingKey = keys.get(slot);

            if (existingKey == keyHash) {
                return slot;
            }

            if (existingKey == 0L) {
                values.compareAndSet(slot, observed, nowNanos);
                if (keys.compareAndSet(slot, 0L, keyHash) || keys.get(slot) == keyHash) {
                    return slot;
                }
            } else if (nowNanos - observed > maxIdleNanos && values.compareAndSet(slot, observed, nowNanos)) {
                // The slot was idle when its value was refreshed; take it over.
                if (keys.compareAndSet(slot, existingKey, keyHash) || keys.get(slot) == keyHash) {
                    return slot;
                }
            }
        }

        return NO_SLOT;
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    public long getValue(int slot) {
        return values.get(slot);
    }

    public boolean compareAndSet(int slot, long expected, long newValue) {
        return values.compareAndSet(slot, expected, newValue);
    }

    public int capacity() {
        return capacity;
    }

    private static int roundToPowerOfTwo(int value) {
        int highestOneBit = Integer.highestOneBit(value);
        if (value == highestOneBit) {
            return value;
        }
        return highestOneBit << 1;
    }
}
