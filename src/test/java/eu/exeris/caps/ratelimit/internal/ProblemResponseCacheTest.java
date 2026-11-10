package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body cache has a fixed upper bound; a response is the same whether or not its body was cached.
 *
 * <p>The cache size is not exposed, so the bound is observed through heap allocation: sending a
 * long detail whose body is cached allocates far less than the body, one that is not cached allocates
 * at least the encoded body.
 */
@DisplayName("the problem body cache is bounded and does not change the bytes sent")
class ProblemResponseCacheTest {

    private static final int BOUND = ProblemResponses.MAX_CACHED_BODIES;
    private static final int LONG_DETAIL_CHARS = 3000;
    /** Far below the size of an encoded long body and far above the fixed per-response cost. */
    private static final long UNCACHED_THRESHOLD_BYTES = 2000;
    private static final int WARM_UP = 20_000;

    private static MemoryAllocator allocator() {
        return ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults());
    }

    private static String expected(HttpStatus status, String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"" + status.reasonPhrase() + "\",\"status\":" + status.code()
                + ",\"detail\":\"" + detail + "\"}";
    }

    private static String longDetail(String tag, int i) {
        String head = tag + "-" + i + "-";
        return head + "x".repeat(LONG_DETAIL_CHARS - head.length());
    }

    private static RecordingExchange recording(RecordingExchange.Mode mode) {
        return new RecordingExchange(HttpMethod.POST, "/login", HttpVersion.HTTP_1_1, List.of(), mode);
    }

    private static void send(Send sender, MemoryAllocator allocator, HttpExchange exchange, HttpStatus status,
                             String detail) {
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .run(() -> sender.send(exchange, status, detail, List.of(new HttpHeader("Retry-After", "7"))));
    }

    private static byte[] sendForBytes(Send sender, MemoryAllocator allocator, HttpStatus status, String detail) {
        RecordingExchange exchange = recording(RecordingExchange.Mode.ACCEPT_NOW);
        send(sender, allocator, exchange, status, detail);
        return exchange.bodyBytes;
    }

    /** The entry point under test. */
    @FunctionalInterface
    private interface Send {
        void send(HttpExchange exchange, HttpStatus status, String detail, List<HttpHeader> headers);
    }

    /**
     * A copy of {@link ProblemResponses} defined by its own class loader, so its cache starts empty
     * whatever other tests in the JVM have already put into the shared one.
     */
    private static Send withEmptyCache() throws Exception {
        String[] names = {ProblemResponses.class.getName(), ProblemResponses.class.getName() + "$Headers"};
        ClassLoader parent = ProblemResponses.class.getClassLoader();
        ClassLoader isolated = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(names[0]) && !name.equals(names[1])) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (java.io.InputStream in = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    return loaded;
                }
            }
        };
        Class<?> copy = isolated.loadClass(names[0]);
        assertThat(copy).isNotSameAs(ProblemResponses.class);
        java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.publicLookup().findStatic(copy, "send",
                java.lang.invoke.MethodType.methodType(void.class, HttpExchange.class, HttpStatus.class, String.class,
                        List.class));
        return (exchange, status, detail, headers) -> {
            try {
                handle.invokeExact(exchange, status, detail, headers);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new AssertionError(e);
            }
        };
    }

    /** Releases the body at once and copies nothing. */
    private static final class DiscardingExchange implements HttpExchange {
        private final HttpRequest request = HttpRequest.noBody(HttpMethod.POST, "/login", HttpVersion.HTTP_1_1, List.of());

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            if (response.body() != null) {
                response.body().close();
            }
        }

        @Override
        public void respond(HttpStatus status) {
            // a bodyless response carries nothing to record in this double
        }
    }

    private static long allocatedByCurrentThread() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** Heap bytes allocated by one send of the detail on this thread, after the path has been warmed. */
    private static long heapBytesOfOneSend(Send sender, MemoryAllocator allocator, HttpStatus status, String detail) {
        DiscardingExchange exchange = new DiscardingExchange();
        long[] bytes = new long[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
            long before = allocatedByCurrentThread();
            sender.send(exchange, status, detail, List.of());
            bytes[0] = allocatedByCurrentThread() - before;
        });
        return bytes[0];
    }

    private static void warmUp(Send sender, MemoryAllocator allocator) {
        DiscardingExchange exchange = new DiscardingExchange();
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
            for (int i = 0; i < WARM_UP; i++) {
                sender.send(exchange, HttpStatus.TOO_MANY_REQUESTS, "warm-up detail", List.of());
            }
        });
    }

    /** Counts the details whose body is retained, by probing each once. Probing never inserts into a full cache. */
    private static int countRetained(Send sender, MemoryAllocator allocator, HttpStatus status, List<String> details) {
        int retained = 0;
        for (String detail : details) {
            long bytes = heapBytesOfOneSend(sender, allocator, status, detail);
            if (bytes < UNCACHED_THRESHOLD_BYTES) {
                retained++;
            }
        }
        return retained;
    }

    @Test
    @DisplayName("sequentially, no more than the bound of distinct long details is retained, and the cache stays full afterwards")
    void sequentialBound() throws Exception {
        Send sender = withEmptyCache();
        MemoryAllocator allocator = allocator();
        try {
            warmUp(sender, allocator);
            int distinct = BOUND * 4;
            List<String> details = new ArrayList<>();
            for (int i = 0; i < distinct; i++) {
                details.add(longDetail("seq", i));
            }
            DiscardingExchange exchange = new DiscardingExchange();
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
                for (String detail : details) {
                    sender.send(exchange, HttpStatus.TOO_MANY_REQUESTS, detail, List.of());
                }
            });
            int retained = countRetained(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, details);
            // one slot is taken by the warm-up detail
            assertThat(retained).as("distinct details retained").isBetween(BOUND - 1, BOUND);

            // a detail first seen now is not retained either: the cache does not grow past the bound
            String late = longDetail("late", 0);
            send(sender, allocator, exchange, HttpStatus.TOO_MANY_REQUESTS, late);
            assertThat(heapBytesOfOneSend(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, late))
                    .as("a detail first seen when the cache is full is encoded per response")
                    .isGreaterThanOrEqualTo(UNCACHED_THRESHOLD_BYTES);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("the bound counts all statuses together")
    void boundSpansStatuses() throws Exception {
        Send sender = withEmptyCache();
        MemoryAllocator allocator = allocator();
        try {
            warmUp(sender, allocator);
            List<HttpStatus> statuses = List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.UNAUTHORIZED,
                    HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FORBIDDEN);
            DiscardingExchange exchange = new DiscardingExchange();
            List<String> details = new ArrayList<>();
            for (int i = 0; i < BOUND; i++) {
                details.add(longDetail("st", i));
            }
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
                for (HttpStatus status : statuses) {
                    for (String detail : details) {
                        sender.send(exchange, status, detail, List.of());
                    }
                }
            });
            int retained = 0;
            for (HttpStatus status : statuses) {
                retained += countRetained(sender, allocator, status, details);
            }
            assertThat(retained).as("bodies retained over %d statuses", statuses.size()).isLessThanOrEqualTo(BOUND);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("many threads with distinct details finish, answer correctly, and exceed the bound by at most the thread count")
    void concurrentBoundAndCompletion() throws Exception {
        Send sender = withEmptyCache();
        int threads = 16;
        int perThread = 200;
        List<List<String>> perThreadDetails = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<String> mine = new ArrayList<>();
            for (int i = 0; i < perThread; i++) {
                mine.add(longDetail("c" + t, i));
            }
            perThreadDetails.add(mine);
            all.addAll(mine);
        }
        assertThat(all).hasSizeGreaterThan(BOUND * 4);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wrong = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                List<String> mine = perThreadDetails.get(t);
                futures.add(pool.submit(() -> {
                    MemoryAllocator allocator = allocator();
                    try {
                        start.await();
                        for (String detail : mine) {
                            byte[] got = sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, detail);
                            byte[] want = expected(HttpStatus.TOO_MANY_REQUESTS, detail).getBytes(StandardCharsets.UTF_8);
                            if (!java.util.Arrays.equals(got, want)) {
                                wrong.incrementAndGet();
                            }
                        }
                    } finally {
                        allocator.close();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(wrong.get()).as("responses with wrong bytes").isZero();

        MemoryAllocator allocator = allocator();
        try {
            warmUp(sender, allocator);
            int retained = countRetained(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, all);
            assertThat(retained).as("details retained by %d concurrent callers", threads)
                    .isBetween(BOUND, BOUND + threads);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("a detail first seen when the cache is full yields exactly the bytes a cached detail yields")
    void fullCacheSameBytes() throws Exception {
        Send sender = withEmptyCache();
        MemoryAllocator allocator = allocator();
        try {
            String early = "Too many requests for this operation; retry later.";
            byte[] cachedOrNot = sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, early);
            for (int i = 0; i < BOUND * 3; i++) {
                sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, "fill-" + i);
            }
            for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.UNAUTHORIZED,
                    HttpStatus.SERVICE_UNAVAILABLE)) {
                String fresh = "first seen when full for " + status.code();
                byte[] want = expected(status, fresh).getBytes(StandardCharsets.UTF_8);
                assertThat(sendForBytes(sender, allocator, status, fresh)).isEqualTo(want);
                assertThat(sendForBytes(sender, allocator, status, fresh)).isEqualTo(want);
            }
            String multibyte = "Zażółć gęślą jaźń €";
            byte[] want = expected(HttpStatus.TOO_MANY_REQUESTS, multibyte).getBytes(StandardCharsets.UTF_8);
            assertThat(sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, multibyte)).isEqualTo(want);
            assertThat(sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, early)).isEqualTo(cachedOrNot);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("the bytes for a detail are unchanged after many other details were added")
    void bytesStableAfterOtherDetails() throws Exception {
        Send sender = withEmptyCache();
        MemoryAllocator allocator = allocator();
        try {
            String detail = "stable detail text";
            byte[] want = expected(HttpStatus.TOO_MANY_REQUESTS, detail).getBytes(StandardCharsets.UTF_8);
            assertThat(sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, detail)).isEqualTo(want);
            for (int i = 0; i < BOUND * 3; i++) {
                sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, "other-" + i);
                sendForBytes(sender, allocator, HttpStatus.UNAUTHORIZED, "other-" + i);
                if (i % 97 == 0) {
                    assertThat(sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, detail)).isEqualTo(want);
                }
            }
            assertThat(sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, detail)).isEqualTo(want);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("overwriting the body handed to the kernel never changes later responses, cached or not")
    void handedOverBufferIsNotTheCachedArray() throws Exception {
        Send sender = withEmptyCache();
        MemoryAllocator allocator = allocator();
        try {
            String cached = "overwrite probe detail";
            sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, cached);
            sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, cached);
            for (int i = 0; i < BOUND * 2; i++) {
                sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, "pad-" + i);
            }
            String uncached = "overwrite probe when full";
            for (String detail : List.of(cached, uncached)) {
                byte[] want = expected(HttpStatus.TOO_MANY_REQUESTS, detail).getBytes(StandardCharsets.UTF_8);
                for (int round = 0; round < 3; round++) {
                    RecordingExchange held = recording(RecordingExchange.Mode.ACCEPT_DEFERRED);
                    send(sender, allocator, held, HttpStatus.TOO_MANY_REQUESTS, detail);
                    assertThat(held.response.body().segment().isNative()).as("body memory is kernel memory").isTrue();
                    assertThat(held.bodyBytes).isEqualTo(want);
                    held.response.body().segment().fill((byte) 0x7F);
                    held.completeDeferred();
                    assertThat(sendForBytes(sender, allocator, HttpStatus.TOO_MANY_REQUESTS, detail)).isEqualTo(want);
                }
            }
        } finally {
            allocator.close();
        }
    }
}
