package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("problem responses hand the kernel a kernel-allocated body")
class ProblemResponseBufferTest {

    private static final String DETAIL = "Too many requests for this operation; retry later.";

    private static MemoryAllocator base;

    @BeforeAll
    static void openAllocator() {
        base = ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
    }

    @AfterAll
    static void closeAllocator() {
        base.close();
    }

    private static String expected(HttpStatus status, String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"" + status.reasonPhrase() + "\",\"status\":" + status.code()
                + ",\"detail\":\"" + detail + "\"}";
    }

    private static RecordingExchange exchange(HttpVersion version, RecordingExchange.Mode mode) {
        return new RecordingExchange(HttpMethod.POST, "/login", version, List.of(), mode);
    }

    private static void send(TrackingAllocator allocator, RecordingExchange exchange, HttpStatus status, String detail,
                             List<HttpHeader> headers) {
        java.lang.ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .run(() -> ProblemResponses.send(exchange, status, detail, headers));
    }

    private static void send(TrackingAllocator allocator, RecordingExchange exchange) {
        send(allocator, exchange, HttpStatus.TOO_MANY_REQUESTS, DETAIL, List.of(new HttpHeader("Retry-After", "7")));
    }

    // 1, 2, 3: bytes, Content-Length, status, headers, version

    @Test
    @DisplayName("the body handed over holds exactly the encoded problem document, for several statuses")
    void bytesMatch() {
        for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.UNAUTHORIZED,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.FORBIDDEN)) {
            TrackingAllocator allocator = new TrackingAllocator(base);
            RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
            send(allocator, exchange, status, DETAIL, List.of());
            byte[] want = expected(status, DETAIL).getBytes(StandardCharsets.UTF_8);
            assertThat(exchange.bodyBytes).as(status.toString()).isEqualTo(want);
            assertThat(exchange.bodySizeAtRespond).isEqualTo(want.length);
            assertThat(exchange.status).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("the size is the encoded byte length, not the character count")
    void sizeIsByteLength() {
        String detail = "Zażółć gęślą jaźń €";
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, exchange, HttpStatus.TOO_MANY_REQUESTS, detail, List.of());
        byte[] want = expected(HttpStatus.TOO_MANY_REQUESTS, detail).getBytes(StandardCharsets.UTF_8);
        assertThat(want.length).isGreaterThan(expected(HttpStatus.TOO_MANY_REQUESTS, detail).length());
        assertThat(exchange.bodyBytes).isEqualTo(want);
        assertThat(allocator.requested).containsExactly(want.length);
    }

    @Test
    @DisplayName("Content-Length is absent or equals the body size; status, headers and request version are kept")
    void headersAndVersion() {
        for (HttpVersion version : List.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2)) {
            TrackingAllocator allocator = new TrackingAllocator(base);
            RecordingExchange exchange = exchange(version, RecordingExchange.Mode.ACCEPT_NOW);
            send(allocator, exchange);
            assertThat(exchange.response.version()).isEqualTo(version);
            assertThat(exchange.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exchange.header("Retry-After")).contains("7");
            assertThat(exchange.header("Cache-Control")).contains("no-store");
            assertThat(exchange.header("Content-Type")).contains("application/problem+json");
            assertThat(exchange.response.headers().stream().filter(h -> h.nameEqualsIgnoreCase("Content-Type")).count())
                    .isEqualTo(1);
            exchange.header("Content-Length").ifPresent(value ->
                    assertThat(value).isEqualTo(String.valueOf(exchange.bodySizeAtRespond)));
        }
    }

    // 4, 5: kernel buffers only; released once on success

    @Test
    @DisplayName("one kernel allocation per response, sized to the body, handed over as the body and released once")
    void oneAllocationReleasedOnce() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, exchange);
        assertThat(allocator.allocations()).isEqualTo(1);
        assertThat(allocator.requested).containsExactly((int) exchange.bodySizeAtRespond);
        assertThat(exchange.response.body()).isSameAs(allocator.buffers.get(0));
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a buffer exactly as large as the body is enough")
    void capacityAtTheEstimate() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, exchange);
        assertThat(allocator.buffers.get(0).capacity()).isGreaterThanOrEqualTo(exchange.bodySizeAtRespond);
        assertThat(exchange.bodySizeAtRespond).isEqualTo(allocator.requested.get(0).longValue());
    }

    // 6, 7: respond rejects, respond fails after taking ownership

    @Test
    @DisplayName("when respond rejects before taking the body, the exception reaches the caller and the buffer is released once")
    void rejectedBeforeTake() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.REJECT_BEFORE_TAKE);
        assertThatThrownBy(() -> send(allocator, exchange)).isInstanceOf(IllegalStateException.class);
        assertThat(allocator.allocations()).isEqualTo(1);
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("when respond fails after releasing the body, nothing leaks and the release happened once")
    void failedAfterRelease() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.FAIL_AFTER_RELEASE);
        assertThatThrownBy(() -> send(allocator, exchange)).isInstanceOf(IllegalStateException.class);
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.buffers.get(0).closeCalls.get()).isBetween(1, 2);
    }

    // 8: failures before respond

    @Test
    @DisplayName("a buffer that reports less capacity than requested is released once and respond is never reached")
    void capacityBelowTheEstimate() {
        TrackingAllocator allocator = new TrackingAllocator(base, 1);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
        assertThatThrownBy(() -> send(allocator, exchange)).isInstanceOf(IllegalStateException.class);
        assertThat(exchange.respondCalls).isZero();
        assertThat(allocator.allocations()).isEqualTo(1);
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure while building the response after the allocation releases the buffer once")
    void failureBeforeRespond() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
        List<HttpHeader> poisoned = Arrays.asList(new HttpHeader("Retry-After", "7"), null);
        assertThatThrownBy(() -> send(allocator, exchange, HttpStatus.TOO_MANY_REQUESTS, DETAIL, poisoned))
                .isInstanceOf(RuntimeException.class);
        assertThat(exchange.respondCalls).isZero();
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.buffers.stream().mapToInt(b -> b.closeCalls.get()).sum()).isEqualTo(allocator.allocations());
    }

    // 9: lifetime after respond

    @Test
    @DisplayName("the body is alive with one reference at respond and stays alive until the engine releases it")
    void bodyOutlivesTheCaller() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(HttpVersion.HTTP_2, RecordingExchange.Mode.ACCEPT_DEFERRED);
        send(allocator, exchange);
        assertThat(exchange.aliveAtRespond).isTrue();
        assertThat(exchange.refCountAtRespond).isEqualTo(1);
        assertThat(allocator.buffers.get(0).isAlive()).isTrue();
        assertThat(allocator.buffers.get(0).refCount()).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isZero();
        assertThat(allocator.finalReleases.get()).isZero();
        exchange.completeDeferred();
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
    }

    // 10, 15: empty bodies and the unbound slot

    @Test
    @DisplayName("with no allocator bound the answer is bodyless with the right status and headers, and nothing is allocated")
    void unboundAllocator() {
        long before = base.stats().allocationCount();
        RecordingExchange exchange = exchange(HttpVersion.HTTP_2, RecordingExchange.Mode.ACCEPT_NOW);
        ProblemResponses.send(exchange, HttpStatus.TOO_MANY_REQUESTS, DETAIL, List.of(new HttpHeader("Retry-After", "7")));
        assertThat(exchange.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.response.body()).isNull();
        assertThat(exchange.response.version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(exchange.header("Retry-After")).contains("7");
        exchange.header("Content-Length").ifPresent(value -> assertThat(value).isEqualTo("0"));
        assertThat(base.stats().allocationCount()).isEqualTo(before);
    }

    // 13: no shared memory between responses

    @Test
    @DisplayName("overwriting a handed-over body does not change the next response")
    void bodiesAreNotShared() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange first = exchange(HttpVersion.HTTP_2, RecordingExchange.Mode.ACCEPT_DEFERRED);
        send(allocator, first);
        first.response.body().segment().fill((byte) 0xFF);
        first.completeDeferred();

        RecordingExchange second = exchange(HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, second);
        assertThat(second.body()).isEqualTo(expected(HttpStatus.TOO_MANY_REQUESTS, DETAIL));
        assertThat(second.response.body()).isNotSameAs(first.response.body());
    }

    // 14: leak detection

    @Test
    @DisplayName("under paranoid leak detection every allocation is released and none is reported leaked")
    void noLeaks() {
        MemoryAllocator audited = ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
        try {
            for (RecordingExchange.Mode mode : List.of(RecordingExchange.Mode.ACCEPT_NOW,
                    RecordingExchange.Mode.ACCEPT_DEFERRED, RecordingExchange.Mode.REJECT_BEFORE_TAKE,
                    RecordingExchange.Mode.FAIL_AFTER_RELEASE)) {
                for (int i = 0; i < 50; i++) {
                    TrackingAllocator allocator = new TrackingAllocator(audited);
                    RecordingExchange exchange = exchange(HttpVersion.HTTP_1_1, mode);
                    try {
                        send(allocator, exchange);
                    } catch (IllegalStateException expected) {
                        // the engine double refuses on purpose
                    }
                    exchange.completeDeferred();
                }
            }
            System.gc();
            MemoryStats stats = audited.stats();
            assertThat(stats.allocationCount()).isEqualTo(200);
            assertThat(stats.releaseCount()).isEqualTo(stats.allocationCount());
            assertThat(stats.allocatedBytes()).isZero();
            assertThat(stats.leakCount()).isZero();
        } finally {
            audited.close();
        }
    }
}
