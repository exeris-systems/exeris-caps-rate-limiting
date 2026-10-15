package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Token buckets, one per key, each holding at most {@code capacity} tokens and gaining one token per
 * {@code refillPeriodNanos}.
 *
 * <p>Keys are compared exactly, never by hash, so two clients never share a bucket by collision. The
 * number of live buckets is bounded: when the table is full, buckets that have refilled completely are
 * dropped (dropping one changes nothing, since a new bucket starts full). The sweep runs at most once per
 * refill period, because a bucket cannot become full sooner than one period after its last take. If none can be dropped, a new
 * key is refused rather than admitted unmetered, because these buckets guard credential endpoints where
 * failing open would remove the protection exactly when an attacker floods the table.
 *
 * <p>Known limits, both accepted:
 * <ul>
 *   <li>The eviction scan visits every bucket, so one sweep costs O(maxKeys); a flood of distinct keys
 *       against a full table pays it once per refill period, and every other new key is refused at once.</li>
 *   <li>A bucket evicted between a {@code get} and the following {@code take} can lose that one token,
 *       after which the client holds a full bucket again. The effect is only ever to under-limit, and only
 *       for a bucket that was already full.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class TokenBucketLimiter {

    private final long capacity;
    private final long refillPeriodNanos;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos;

    public TokenBucketLimiter(long capacity, long refillPeriodNanos, int maxKeys, LongSupplier nanoClock) {
        if (capacity <= 0 || refillPeriodNanos <= 0 || maxKeys <= 0) {
            throw new IllegalArgumentException("capacity, refill period and max keys must be positive");
        }
        this.capacity = capacity;
        this.refillPeriodNanos = refillPeriodNanos;
        this.maxKeys = maxKeys;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        // Start a period in the past so the first full table is swept at once.
        this.lastSweepNanos = new AtomicLong(nanoClock.getAsLong() - refillPeriodNanos);
    }

    /**
     * Takes one token from {@code key}'s bucket.
     *
     * @param key bucket key
     * @return allowed with the tokens left, or rejected with the time until the next token
     */
    public RateLimitDecision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                evictRefilledOncePerPeriod(now);
                if (buckets.size() >= maxKeys) {
                    return RateLimitDecision.rejected(capacity, refillPeriodNanos);
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        }
        return bucket.take(now, capacity, refillPeriodNanos);
    }

    /**
     * Counts the buckets the table holds, which never exceeds the configured maximum.
     *
     * @return the number of live buckets
     */
    public int size() {
        return buckets.size();
    }

    private void evictRefilledOncePerPeriod(long now) {
        long last = lastSweepNanos.get();
        if (now - last >= refillPeriodNanos && lastSweepNanos.compareAndSet(last, now)) {
            evictRefilled(now);
        }
    }

    private void evictRefilled(long now) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            if (it.next().isFull(now, capacity, refillPeriodNanos)) {
                it.remove();
            }
        }
    }

    static final class Bucket {
        private long tokens;
        private long lastRefillNanos;

        Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }

        synchronized RateLimitDecision take(long now, long capacity, long period) {
            refill(now, capacity, period);
            if (tokens > 0) {
                if (tokens >= capacity) {
                    // A full bucket starts refilling when a token leaves it, not when it last filled.
                    lastRefillNanos = now;
                }
                tokens--;
                long untilFull = (capacity - tokens) * period - (now - lastRefillNanos);
                return RateLimitDecision.allowed(tokens, capacity, Math.max(0L, untilFull));
            }
            return RateLimitDecision.rejected(capacity, period - (now - lastRefillNanos));
        }

        synchronized boolean isFull(long now, long capacity, long period) {
            refill(now, capacity, period);
            return tokens >= capacity;
        }

        private void refill(long now, long capacity, long period) {
            long elapsed = now - lastRefillNanos;
            if (elapsed < period) {
                return;
            }
            long earned = elapsed / period;
            if (earned >= capacity - tokens) {
                tokens = capacity;
                lastRefillNanos = now;
            } else {
                tokens += earned;
                lastRefillNanos += earned * period;
            }
        }
    }
}
