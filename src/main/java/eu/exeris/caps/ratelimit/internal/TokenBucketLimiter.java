package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token buckets, one per key, each holding at most {@code capacity} tokens and gaining one token per
 * {@code refillPeriodNanos}.
 *
 * <p>Keys are compared exactly, never by hash, so two clients never share a bucket by collision. The
 * number of live buckets is bounded: when the table is full, buckets that have refilled completely are
 * dropped (dropping one changes nothing, since a new bucket starts full). If none can be dropped, a new
 * key is refused rather than admitted unmetered, because these buckets guard credential endpoints where
 * failing open would remove the protection exactly when an attacker floods the table.
 *
 * @since 0.1.0
 */
public final class TokenBucketLimiter {

    private final long capacity;
    private final long refillPeriodNanos;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(long capacity, long refillPeriodNanos, int maxKeys, LongSupplier nanoClock) {
        if (capacity <= 0 || refillPeriodNanos <= 0 || maxKeys <= 0) {
            throw new IllegalArgumentException("capacity, refill period and max keys must be positive");
        }
        this.capacity = capacity;
        this.refillPeriodNanos = refillPeriodNanos;
        this.maxKeys = maxKeys;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
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
                evictRefilled(now);
                if (buckets.size() >= maxKeys) {
                    return RateLimitDecision.rejected(capacity, refillPeriodNanos);
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        }
        return bucket.take(now, capacity, refillPeriodNanos);
    }

    /** @return the number of live buckets */
    public int size() {
        return buckets.size();
    }

    private void evictRefilled(long now) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            if (it.next().isFull(now, capacity, refillPeriodNanos)) {
                it.remove();
            }
        }
    }

    private static final class Bucket {
        private long tokens;
        private long lastRefillNanos;

        Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }

        synchronized RateLimitDecision take(long now, long capacity, long period) {
            refill(now, capacity, period);
            if (tokens > 0) {
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
