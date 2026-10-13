package eu.exeris.caps.ratelimit.internal;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size, lock-free open-addressing hash table storing primitive 64-bit keys and values.
 *
 * <p>Uses linear probing with a bounded hop count. Dead/idle slots whose value is older
 * than {@code maxIdleNanos} are reclaimed lock-free during probing, preventing table
 * saturation without requiring background cleanup threads or GC allocation.
 */
public final class LockFreePrimitiveTable {

    private static final int MAX_PROBE_HOPS = 16;

    private final AtomicLongArray keys;
    private final AtomicLongArray values;
    private final int mask;
    private final int capacity;
    private final long maxIdleNanos;

    public LockFreePrimitiveTable(int initialCapacity, long maxIdleNanos) {
        int cap = Math.max(16, roundToPowerOfTwo(initialCapacity));
        this.capacity = cap;
        this.mask = cap - 1;
        this.keys = new AtomicLongArray(cap);
        this.values = new AtomicLongArray(cap);
        this.maxIdleNanos = Math.max(1_000_000_000L, maxIdleNanos); // at least 1s
    }

    /**
     * Resolves the slot index for the given keyHash.
     *
     * @param keyHash 64-bit non-zero hash
     * @param nowNanos current timestamp in nanoseconds
     * @return slot index in range [0, capacity - 1]
     */
    public int findOrAllocateSlot(long keyHash, long nowNanos) {
        int baseSlot = (int) (keyHash ^ (keyHash >>> 32)) & mask;

        for (int hop = 0; hop < MAX_PROBE_HOPS; hop++) {
            int slot = (baseSlot + hop) & mask;
            long existingKey = keys.get(slot);

            if (existingKey == keyHash) {
                return slot;
            }

            if (existingKey == 0L) {
                if (keys.compareAndSet(slot, 0L, keyHash)) {
                    values.set(slot, nowNanos);
                    return slot;
                }
                if (keys.get(slot) == keyHash) {
                    return slot;
                }
            } else {
                // Check if existing slot is dead and eligible for reclamation
                long tat = values.get(slot);
                if (nowNanos - tat > maxIdleNanos) {
                    if (keys.compareAndSet(slot, existingKey, keyHash)) {
                        values.set(slot, nowNanos);
                        return slot;
                    }
                    if (keys.get(slot) == keyHash) {
                        return slot;
                    }
                }
            }
        }

        // Bounded fallback: overwrite the base slot to maintain constant time
        return baseSlot;
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
