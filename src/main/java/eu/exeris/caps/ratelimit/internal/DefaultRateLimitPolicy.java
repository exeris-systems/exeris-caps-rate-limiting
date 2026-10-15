package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import eu.exeris.caps.ratelimit.api.RateLimitPolicy;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * High-performance GCRA (Generic Cell Rate Algorithm) implementation of {@link RateLimitPolicy}.
 *
 * <p>State per key is a single 64-bit timestamp (Theoretical Arrival Time, TAT).
 * Allowed requests require a single atomic CAS; throttled requests trigger zero CAS operations,
 * preserving CPU cache-line sharing under heavy contention.
 *
 * <p>The policy fails closed: a request is allowed only after its cost has been recorded, so a key
 * that cannot be given a table slot, or whose update loses every compare-and-set attempt, is rejected.
 */
public final class DefaultRateLimitPolicy implements RateLimitPolicy {

    private static final int MAX_CAS_RETRIES = 5;

    private final long emissionIntervalNanos;
    private final long burstToleranceNanos;
    private final long burstCapacity;
    private final LockFreePrimitiveTable table;
    private final LongSupplier nanoClock;
    private final long hashSeed = new SecureRandom().nextLong();

    public DefaultRateLimitPolicy(long emissionIntervalNanos, long burstCapacity, int maxKeys) {
        this(emissionIntervalNanos, burstCapacity, maxKeys, System::nanoTime);
    }

    public DefaultRateLimitPolicy(long emissionIntervalNanos, long burstCapacity, int maxKeys, LongSupplier nanoClock) {
        if (emissionIntervalNanos <= 0) {
            throw new IllegalArgumentException("emissionIntervalNanos must be > 0: " + emissionIntervalNanos);
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0: " + burstCapacity);
        }
        this.emissionIntervalNanos = emissionIntervalNanos;
        this.burstCapacity = burstCapacity;
        this.burstToleranceNanos = burstCapacity * emissionIntervalNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");

        // Set table idle eviction time to 10x burst tolerance or at least 1 minute
        long maxIdleNanos = Math.max(60_000_000_000L, this.burstToleranceNanos * 10);
        this.table = new LockFreePrimitiveTable(maxKeys, maxIdleNanos);
    }

    /** Test seam: the caller supplies the state table. */
    DefaultRateLimitPolicy(long emissionIntervalNanos, long burstCapacity, LockFreePrimitiveTable table,
                           LongSupplier nanoClock) {
        this.emissionIntervalNanos = emissionIntervalNanos;
        this.burstCapacity = burstCapacity;
        this.burstToleranceNanos = burstCapacity * emissionIntervalNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public RateLimitDecision evaluate(long keyHash, long cost) {
        if (cost <= 0) {
            return RateLimitDecision.allowed(burstCapacity, burstCapacity, 0L);
        }

        long now = nanoClock.getAsLong();
        long incrementNanos = cost * emissionIntervalNanos;
        int slot = table.findOrAllocateSlot(keyHash, now);
        if (slot == LockFreePrimitiveTable.NO_SLOT) {
            // No state can be held for this key: refuse rather than share another key's state.
            return RateLimitDecision.rejected(burstCapacity, incrementNanos);
        }

        for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
            long currentTat = table.getValue(slot);
            long effectiveTat = (now > currentTat) ? now : currentTat;
            long nextTat = effectiveTat + incrementNanos;
            long diff = nextTat - now;

            if (diff > burstToleranceNanos) {
                // Throttled: zero CAS, pure volatile read
                long retryAfterNanos = diff - burstToleranceNanos;
                return RateLimitDecision.rejected(burstCapacity, retryAfterNanos);
            }

            if (table.compareAndSet(slot, currentTat, nextTat)) {
                long remaining = Math.max(0L, (burstToleranceNanos - diff) / emissionIntervalNanos);
                return RateLimitDecision.allowed(remaining, burstCapacity, diff);
            }
        }

        // Contention outlasted the retry budget. An allow is only ever returned together with the
        // recorded update, so the request is refused.
        return RateLimitDecision.rejected(burstCapacity, incrementNanos);
    }

    @Override
    public RateLimitDecision evaluate(CharSequence key, long cost) {
        long hash = ZeroAllocHasher.hash(key, hashSeed);
        return evaluate(hash, cost);
    }

    public long emissionIntervalNanos() {
        return emissionIntervalNanos;
    }

    public long burstCapacity() {
        return burstCapacity;
    }

    public int tableCapacity() {
        return table.capacity();
    }
}
