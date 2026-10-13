package eu.exeris.caps.ratelimit.api;

/**
 * The immutable verdict of a rate limit evaluation.
 *
 * @param allowed whether the request is allowed to proceed
 * @param remaining estimated number of requests remaining before throttle
 * @param limit total burst capacity/limit configured for this bucket
 * @param resetAfterNanos nanoseconds until the bucket is completely replenished or ready for the next request
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long limit,
        long resetAfterNanos
) {

    public static RateLimitDecision allowed(long remaining, long limit, long resetAfterNanos) {
        return new RateLimitDecision(true, remaining, limit, resetAfterNanos);
    }

    public static RateLimitDecision rejected(long limit, long retryAfterNanos) {
        return new RateLimitDecision(false, 0L, limit, retryAfterNanos);
    }

    /**
     * Helper to compute HTTP {@code Retry-After} in integer seconds (rounded up).
     */
    public long retryAfterSeconds() {
        if (allowed) {
            return 0L;
        }
        return Math.max(1L, (resetAfterNanos + 999_999_999L) / 1_000_000_000L);
    }
}
