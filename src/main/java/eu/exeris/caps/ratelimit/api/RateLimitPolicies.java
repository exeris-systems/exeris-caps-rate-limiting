package eu.exeris.caps.ratelimit.api;

import eu.exeris.caps.ratelimit.internal.DefaultRateLimitPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * Public factory and fluent builder for constructing {@link RateLimitPolicy} instances.
 */
public final class RateLimitPolicies {

    private RateLimitPolicies() {
    }

    /**
     * Creates a rate limiter allowing {@code requestsPerSecond} with burst equal to rate.
     */
    public static RateLimitPolicy perSecond(long requestsPerSecond) {
        return perSecond(requestsPerSecond, requestsPerSecond);
    }

    /**
     * Creates a rate limiter allowing {@code requestsPerSecond} with specified burst capacity.
     */
    public static RateLimitPolicy perSecond(long requestsPerSecond, long burstCapacity) {
        return builder()
                .rate(requestsPerSecond, Duration.ofSeconds(1))
                .burst(burstCapacity)
                .build();
    }

    /**
     * Creates a rate limiter allowing {@code requestsPerMinute} with burst equal to rate.
     */
    public static RateLimitPolicy perMinute(long requestsPerMinute) {
        return builder()
                .rate(requestsPerMinute, Duration.ofMinutes(1))
                .burst(requestsPerMinute)
                .build();
    }

    /**
     * Returns a new fluent {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long tokens = 1000L;
        private Duration period = Duration.ofSeconds(1);
        private long burst = 1000L;
        private int capacity = 65_536;

        private Builder() {
        }

        public Builder rate(long tokens, Duration period) {
            if (tokens <= 0) {
                throw new IllegalArgumentException("tokens must be > 0: " + tokens);
            }
            this.tokens = tokens;
            this.period = Objects.requireNonNull(period, "period");
            return this;
        }

        public Builder burst(long burstCapacity) {
            if (burstCapacity <= 0) {
                throw new IllegalArgumentException("burstCapacity must be > 0: " + burstCapacity);
            }
            this.burst = burstCapacity;
            return this;
        }

        public Builder capacity(int maxKeys) {
            if (maxKeys <= 0) {
                throw new IllegalArgumentException("maxKeys must be > 0: " + maxKeys);
            }
            this.capacity = maxKeys;
            return this;
        }

        public RateLimitPolicy build() {
            long periodNanos = period.toNanos();
            long intervalNanos = Math.max(1L, periodNanos / tokens);
            return new DefaultRateLimitPolicy(intervalNanos, burst, capacity);
        }
    }
}
