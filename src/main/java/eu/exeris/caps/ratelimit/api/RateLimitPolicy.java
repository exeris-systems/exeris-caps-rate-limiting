package eu.exeris.caps.ratelimit.api;

/**
 * Service interface for evaluating rate limits against client keys.
 *
 * <p>Implementations must guarantee thread safety, lock-free execution on the hot path,
 * and zero heap allocations when evaluating requests.
 *
 * @since 0.1.0
 */
public interface RateLimitPolicy {

    /**
     * Evaluates whether a request with the given 64-bit key hash and cost is allowed.
     * This is the primary zero-allocation hot-path method.
     *
     * @param keyHash 64-bit hash identifying the client, tenant, IP or route
     * @param cost number of tokens/permits requested (typically 1)
     * @return the evaluation verdict
     */
    RateLimitDecision evaluate(long keyHash, long cost);

    /**
     * Convenience method evaluating with cost = 1.
     */
    default RateLimitDecision evaluate(long keyHash) {
        return evaluate(keyHash, 1L);
    }

    /**
     * Evaluates whether a request with the given string/character key is allowed.
     * Hashing is performed without allocating new String instances.
     *
     * @param key character sequence identifying the client (IP, API key, etc.)
     * @param cost number of tokens/permits requested
     * @return the evaluation verdict
     */
    RateLimitDecision evaluate(CharSequence key, long cost);

    /**
     * Convenience method evaluating with cost = 1.
     */
    default RateLimitDecision evaluate(CharSequence key) {
        return evaluate(key, 1L);
    }
}
