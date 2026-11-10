package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bucket that has just been published must already have paid for its first request, otherwise a sweep
 * of full buckets can evict it and hand its slot to another key before it is charged.
 *
 * <p>The interleaving is forced by running the second key's acquisition on the same thread, from inside
 * the first key's {@code putIfAbsent}, immediately after the bucket has become visible. The table is
 * swapped for one that does this; the limiter itself is untouched.
 */
@DisplayName("TokenBucketLimiter publication")
class TokenBucketLimiterPublicationTest {

    private static final long SECOND = 1_000_000_000L;

    /** Runs {@code afterPublish} once, right after the first successful insert becomes visible. */
    private static final class HookedTable extends ConcurrentHashMap<String, TokenBucketLimiter.Bucket> {
        private static final long serialVersionUID = 1L;

        private final transient AtomicReference<Runnable> afterPublish = new AtomicReference<>();

        @Override
        public TokenBucketLimiter.Bucket putIfAbsent(String key, TokenBucketLimiter.Bucket value) {
            TokenBucketLimiter.Bucket prior = super.putIfAbsent(key, value);
            Runnable hook = prior == null ? afterPublish.getAndSet(null) : null;
            if (hook != null) {
                hook.run();
            }
            return prior;
        }
    }

    @Test
    @DisplayName("a key admitted at the bound is not swept before its first token is taken")
    void newBucketCannotBeSweptBeforeItsFirstCharge() throws Exception {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 10 * SECOND, 1, () -> 1_000 * SECOND);
        HookedTable table = new HookedTable();
        Field field = TokenBucketLimiter.class.getDeclaredField("buckets");
        field.setAccessible(true);
        field.set(limiter, table);

        AtomicReference<RateLimitDecision> second = new AtomicReference<>();
        table.afterPublish.set(() -> second.set(limiter.tryAcquire("b")));

        RateLimitDecision first = limiter.tryAcquire("a");

        assertThat(first.allowed()).as("the key that reserved the only slot is admitted").isTrue();
        assertThat(second.get()).as("the hook ran").isNotNull();
        assertThat(second.get().allowed())
                .as("a second key must be refused while the first holds the only slot and the clock has not moved")
                .isFalse();
        assertThat(limiter.size()).isEqualTo(1);
        assertThat(table).containsKey("a").doesNotContainKey("b");
    }
}
