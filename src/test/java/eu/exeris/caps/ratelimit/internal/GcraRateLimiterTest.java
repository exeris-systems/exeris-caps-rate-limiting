package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GCRA Rate Limiter Tests")
class GcraRateLimiterTest {

    @Nested
    @DisplayName("Single-thread Deterministic Clock Tests")
    class DeterministicClockTests {

        @Test
        @DisplayName("Allows up to burst capacity then rejects with retry-after")
        void allowsBurstThenRejects() {
            AtomicLong clock = new AtomicLong(1_000_000_000L); // t = 1s
            long intervalNanos = 100_000_000L; // 100ms per request (10 req/s)
            long burst = 3L; // burst capacity = 3

            DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(intervalNanos, burst, 64, clock::get);

            long key = 42L;

            // 1st request at t=1s
            RateLimitDecision d1 = policy.evaluate(key, 1);
            assertThat(d1.allowed()).isTrue();
            assertThat(d1.remaining()).isEqualTo(2);

            // 2nd request at t=1s
            RateLimitDecision d2 = policy.evaluate(key, 1);
            assertThat(d2.allowed()).isTrue();
            assertThat(d2.remaining()).isEqualTo(1);

            // 3rd request at t=1s
            RateLimitDecision d3 = policy.evaluate(key, 1);
            assertThat(d3.allowed()).isTrue();
            assertThat(d3.remaining()).isEqualTo(0);

            // 4th request at t=1s -> Should be throttled!
            RateLimitDecision d4 = policy.evaluate(key, 1);
            assertThat(d4.allowed()).isFalse();
            assertThat(d4.resetAfterNanos()).isGreaterThan(0);
            assertThat(d4.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

            // Advance clock by 150ms -> 1 token should have regenerated!
            clock.addAndGet(150_000_000L);
            RateLimitDecision d5 = policy.evaluate(key, 1);
            assertThat(d5.allowed()).isTrue();

            // Next one immediately at t+150ms should be rejected again
            RateLimitDecision d6 = policy.evaluate(key, 1);
            assertThat(d6.allowed()).isFalse();
        }

        @Test
        @DisplayName("Idle bucket fully restores burst capacity")
        void idleBucketRestoresBurst() {
            AtomicLong clock = new AtomicLong(1_000_000_000L);
            long intervalNanos = 50_000_000L; // 50ms per req (20 req/s)
            long burst = 5L;

            DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(intervalNanos, burst, 64, clock::get);
            long key = 100L;

            // Exhaust burst
            for (int i = 0; i < 5; i++) {
                assertThat(policy.evaluate(key, 1).allowed()).isTrue();
            }
            assertThat(policy.evaluate(key, 1).allowed()).isFalse();

            // Advance time by 10 seconds (way beyond burst tolerance)
            clock.addAndGet(10_000_000_000L);

            // Full burst capacity should be available again
            RateLimitDecision fresh = policy.evaluate(key, 1);
            assertThat(fresh.allowed()).isTrue();
            assertThat(fresh.remaining()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Concurrent High-Throughput Contention Tests")
    class ConcurrentContentionTests {

        @Test
        @DisplayName("Strictly limits concurrent requests to burst under stampede")
        void strictlyLimitsConcurrentStampede() throws InterruptedException {
            int threads = 16;
            int requestsPerThread = 500;
            long intervalNanos = 1_000_000_000L; // 1 second per token!
            long burst = 10L; // only 10 allowed in the burst window!

            DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(intervalNanos, burst, 1024);

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            AtomicInteger allowedCount = new AtomicInteger(0);
            AtomicInteger rejectedCount = new AtomicInteger(0);
            long testKey = 9999L;

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int r = 0; r < requestsPerThread; r++) {
                            RateLimitDecision d = policy.evaluate(testKey, 1);
                            if (d.allowed()) {
                                allowedCount.incrementAndGet();
                            } else {
                                rejectedCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await();
            executor.shutdown();

            int total = threads * requestsPerThread;
            assertThat(allowedCount.get() + rejectedCount.get()).isEqualTo(total);
            // Exactly burst (or burst + tiny fraction if clock ticked a few nanos) allowed
            assertThat(allowedCount.get()).isGreaterThanOrEqualTo((int) burst).isLessThanOrEqualTo((int) burst + 2);
            assertThat(rejectedCount.get()).isGreaterThan(total - (int) burst - 5);
        }
    }
}
