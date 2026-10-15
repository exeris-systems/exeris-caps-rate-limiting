package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenBucketLimiter")
class TokenBucketLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);

    private TokenBucketLimiter limiter(long capacity, long refillSeconds, int maxKeys) {
        return new TokenBucketLimiter(capacity, refillSeconds * SECOND, maxKeys, clock::get);
    }

    @Test
    @DisplayName("allows the burst, then rejects with the time until the next token")
    void burstThenReject() {
        TokenBucketLimiter limiter = limiter(3, 10, 16);

        assertThat(limiter.tryAcquire("k").remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("k").remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire("k").remaining()).isEqualTo(0);

        clock.addAndGet(4 * SECOND);
        RateLimitDecision rejected = limiter.tryAcquire("k");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.resetAfterNanos()).isEqualTo(6 * SECOND);
        assertThat(rejected.retryAfterSeconds()).isEqualTo(6);
    }

    @Test
    @DisplayName("regains one token per refill period and keeps the remainder of a partial period")
    void refillsPerPeriod() {
        TokenBucketLimiter limiter = limiter(2, 10, 16);
        limiter.tryAcquire("k");
        limiter.tryAcquire("k");

        clock.addAndGet(15 * SECOND);
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();

        clock.addAndGet(5 * SECOND);
        assertThat(limiter.tryAcquire("k").allowed()).as("the 5 s left over from the first wait count").isTrue();
    }

    @Test
    @DisplayName("never holds more than its capacity, however long it idles")
    void capsAtCapacity() {
        TokenBucketLimiter limiter = limiter(2, 1, 16);
        limiter.tryAcquire("k");
        clock.addAndGet(3_600 * SECOND);

        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    @DisplayName("keys are isolated and compared exactly")
    void keysIsolated() {
        TokenBucketLimiter limiter = limiter(1, 60, 16);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    @DisplayName("a full table drops refilled buckets, and refuses a new key when none can go")
    void boundedTable() {
        TokenBucketLimiter limiter = limiter(1, 60, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");

        assertThat(limiter.tryAcquire("c").allowed()).as("no bucket has refilled: fail closed").isFalse();
        assertThat(limiter.size()).isEqualTo(2);

        clock.addAndGet(60 * SECOND);
        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a full table is swept at most once per refill period")
    void sweepRunsOncePerPeriod() {
        TokenBucketLimiter limiter = limiter(2, 60, 2);
        limiter.tryAcquire("a");
        clock.addAndGet(10 * SECOND);
        limiter.tryAcquire("b");
        assertThat(limiter.tryAcquire("c").allowed()).as("first sweep finds nothing refilled").isFalse();

        clock.addAndGet(55 * SECOND);
        assertThat(limiter.tryAcquire("c").allowed()).as("a is refilled but the period's sweep is spent").isFalse();

        clock.addAndGet(5 * SECOND);
        assertThat(limiter.tryAcquire("c").allowed()).as("the next period sweeps").isTrue();
    }

    @Test
    @DisplayName("a full bucket starts its refill period when the first token leaves it")
    void fullBucketRefillStartsAtFirstTake() {
        TokenBucketLimiter.Bucket bucket = new TokenBucketLimiter.Bucket(5, 0);
        long period = 60 * SECOND;
        for (int i = 0; i < 5; i++) {
            assertThat(bucket.take(59 * SECOND, 5, period).allowed()).isTrue();
        }
        // One second later the bucket has not had a full period since the first token left it.
        assertThat(bucket.take(60 * SECOND, 5, period).allowed()).isFalse();
        assertThat(bucket.take(119 * SECOND, 5, period).allowed()).isTrue();
    }
}
