package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultRateLimitPolicy fails closed")
class DefaultRateLimitPolicyFailClosedTest {

    private static final long INTERVAL = 1_000_000_000L;

    @Test
    @DisplayName("Never allows when the state update cannot be recorded")
    void deniesWhenCasNeverSucceeds() {
        LockFreePrimitiveTable table = new LockFreePrimitiveTable(64, 60_000_000_000L) {
            @Override
            public boolean compareAndSet(int slot, long expected, long newValue) {
                return false;
            }
        };
        DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(INTERVAL, 5, table, () -> 1_000_000_000L);

        RateLimitDecision decision = policy.evaluate(42L, 1);

        assertThat(decision.allowed()).isFalse();
    }

    @Test
    @DisplayName("Never allows a request for a key the table cannot hold")
    void deniesWhenTableIsFull() {
        DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(INTERVAL, 5, 16, () -> 1_000_000_000L);
        int capacity = policy.tableCapacity();
        for (long k = 1; k <= capacity; k++) {
            assertThat(policy.evaluate(k * 7919L, 1).allowed()).isTrue();
        }

        assertThat(policy.evaluate(0x5eedL, 1).allowed()).isFalse();
    }

    @Test
    @DisplayName("Concurrent first requests for fresh keys never record fewer allows than were granted")
    void freshKeysNeverGrantMoreThanBurst() throws Exception {
        int keys = 2000;
        int threads = 8;
        DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(INTERVAL, 1, 8192, () -> 1_000_000_000L);
        AtomicInteger[] allowed = new AtomicInteger[keys];
        for (int k = 0; k < keys; k++) {
            allowed[k] = new AtomicInteger();
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int k = 0; k < keys; k++) {
                    if (policy.evaluate(k + 1L, 1).allowed()) {
                        allowed[k].incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        for (int k = 0; k < keys; k++) {
            assertThat(allowed[k].get()).as("key %d", k + 1).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("Under contention allowed requests never exceed the recorded burst")
    void allowedNeverExceedsBurstUnderContention() throws Exception {
        int burst = 50;
        int threads = 16;
        int perThread = 2000;
        DefaultRateLimitPolicy policy = new DefaultRateLimitPolicy(INTERVAL, burst, 64, () -> 1_000_000_000L);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    if (policy.evaluate(7L, 1).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isLessThanOrEqualTo(burst);
    }
}
