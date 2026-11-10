package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("a problem body is allocated by the smallest hint that holds it")
class ProblemResponseHintTest {

    private static final HttpStatus STATUS = HttpStatus.TOO_MANY_REQUESTS;
    private static final byte PATTERN = (byte) 0xA5;
    private static final int JUMBO = AllocationHint.JUMBO.sizeBytes();

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

    private static String expected(String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"" + STATUS.reasonPhrase() + "\",\"status\":" + STATUS.code()
                + ",\"detail\":\"" + detail + "\"}";
    }

    /** A detail whose encoded problem document is exactly {@code bodyLength} bytes. */
    private static String detailForBody(int bodyLength) {
        int overhead = expected("").getBytes(StandardCharsets.UTF_8).length;
        assertThat(bodyLength).isGreaterThanOrEqualTo(overhead);
        return "a".repeat(bodyLength - overhead);
    }

    private static RecordingExchange exchange(RecordingExchange.Mode mode) {
        return new RecordingExchange(HttpMethod.POST, "/login", HttpVersion.HTTP_1_1, List.of(), mode);
    }

    private static void send(TrackingAllocator allocator, RecordingExchange exchange, String detail) {
        java.lang.ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .run(() -> ProblemResponses.send(exchange, STATUS, detail, List.of(new HttpHeader("Retry-After", "7"))));
    }

    // H1: the pure function, on its boundaries

    static Stream<Object[]> boundaries() {
        return Stream.of(
                new Object[] {0, AllocationHint.MICRO},
                new Object[] {1, AllocationHint.MICRO},
                new Object[] {511, AllocationHint.MICRO},
                new Object[] {512, AllocationHint.MICRO},
                new Object[] {513, AllocationHint.SMALL},
                new Object[] {4095, AllocationHint.SMALL},
                new Object[] {4096, AllocationHint.SMALL},
                new Object[] {4097, AllocationHint.MEDIUM},
                new Object[] {16384, AllocationHint.MEDIUM},
                new Object[] {16385, AllocationHint.STREAMING_CHUNK},
                new Object[] {32768, AllocationHint.STREAMING_CHUNK},
                new Object[] {32769, AllocationHint.NETWORK_FRAME},
                new Object[] {65536, AllocationHint.NETWORK_FRAME},
                new Object[] {65537, AllocationHint.JUMBO},
                new Object[] {131072, AllocationHint.JUMBO});
    }

    @ParameterizedTest(name = "length {0} -> {1}")
    @MethodSource("boundaries")
    @DisplayName("hintFor picks the smallest hint whose size holds the length")
    void hintAtBoundary(int length, AllocationHint want) {
        assertThat(ProblemResponses.hintFor(length)).isEqualTo(want);
        assertThat(want.sizeBytes()).isGreaterThanOrEqualTo(length);
        assertThat(ProblemResponseBufferTest.smallestHint(length)).isEqualTo(want);
    }

    @Test
    @DisplayName("hintFor rejects a length above the largest hint, never a smaller or truncating hint")
    void hintAboveLargest() {
        assertThatThrownBy(() -> ProblemResponses.hintFor(JUMBO + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemResponses.hintFor(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the hint never shrinks as the length grows")
    void hintIsMonotonic() {
        int previous = 0;
        for (int length = 0; length <= JUMBO; length += 97) {
            int size = ProblemResponses.hintFor(length).sizeBytes();
            assertThat(size).isGreaterThanOrEqualTo(previous).isGreaterThanOrEqualTo(length);
            previous = size;
        }
    }

    // H1, H3, H4: send at each boundary

    static Stream<Object[]> bodyLengths() {
        return Stream.of(512, 513, 4096, 4097, 16384, 16385, 32768, 32769, 65536, 65537, JUMBO)
                .map(l -> new Object[] {l, ProblemResponseBufferTest.smallestHint(l)});
    }

    @ParameterizedTest(name = "body of {0} bytes -> {1}")
    @MethodSource("bodyLengths")
    @DisplayName("send allocates by that hint, sets size to the body length, sends exactly the body and releases once")
    void sendAtBoundary(int length, AllocationHint want) {
        String detail = detailForBody(length);
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, exchange, detail);

        assertThat(allocator.networkCalls.get()).isZero();
        assertThat(allocator.hints).containsExactly(want);
        assertThat(exchange.bodySizeAtRespond).isEqualTo(length);
        assertThat(exchange.bodyBytes).isEqualTo(expected(detail).getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.bodyBytes).hasSize(length);
        assertThat(allocator.buffers.get(0).capacity()).isGreaterThanOrEqualTo(want.sizeBytes());
        exchange.header("Content-Length").ifPresent(v -> assertThat(v).isEqualTo(String.valueOf(length)));
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @ParameterizedTest(name = "body of {0} bytes")
    @MethodSource("bodyLengths")
    @DisplayName("with the whole capacity pre-filled non-zero the bytes sent are exactly the body and the tail is untouched")
    void prefilledBuffer(int length, AllocationHint want) {
        String detail = detailForBody(length);
        TrackingAllocator allocator = TrackingAllocator.prefilled(base, PATTERN);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_DEFERRED);
        send(allocator, exchange, detail);

        assertThat(exchange.bodyBytes).isEqualTo(expected(detail).getBytes(StandardCharsets.UTF_8));
        var buffer = allocator.buffers.get(0);
        assertThat(buffer.size()).isEqualTo(length);
        long tail = buffer.capacity() - length;
        // The capacity of the largest bodies may equal the hint size, so the tail can be empty only
        // when the body fills the buffer; for all others it is a real region that nothing wrote.
        if (length < want.sizeBytes()) {
            assertThat(tail).isPositive();
        }
        for (long i = 0; i < tail; i++) {
            assertThat(buffer.segment().get(ValueLayout.JAVA_BYTE, length + i)).as("tail byte %d", i).isEqualTo(PATTERN);
        }
        exchange.completeDeferred();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("a body shorter than the buffer is sent as size() bytes, not as the capacity")
    void sizeNotCapacity() {
        TrackingAllocator allocator = TrackingAllocator.prefilled(base, PATTERN);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, exchange, "short");
        assertThat(exchange.bodyBytes).hasSizeLessThan(AllocationHint.MICRO.sizeBytes());
        assertThat(exchange.body()).isEqualTo(expected("short"));
        assertThat(exchange.bodyBytes).doesNotContain(PATTERN);
    }

    // H2: larger than the largest hint fails closed

    @Test
    @DisplayName("a body larger than the largest hint is rejected before any allocation, never truncated")
    void oversizeFailsClosed() {
        for (int length : new int[] {JUMBO + 1, JUMBO * 4}) {
            String detail = detailForBody(length);
            TrackingAllocator allocator = new TrackingAllocator(base);
            RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_NOW);
            assertThatThrownBy(() -> send(allocator, exchange, detail)).isInstanceOf(IllegalArgumentException.class);
            assertThat(allocator.allocations()).isZero();
            assertThat(allocator.hints).isEmpty();
            assertThat(allocator.networkCalls.get()).isZero();
            assertThat(exchange.respondCalls).isZero();
        }
    }

    // H3: release counts on the failure paths with a large body

    @Test
    @DisplayName("when respond rejects a large body before taking it, the buffer is released once")
    void rejectedLarge() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.REJECT_BEFORE_TAKE);
        assertThatThrownBy(() -> send(allocator, exchange, detailForBody(JUMBO))).isInstanceOf(IllegalStateException.class);
        assertThat(allocator.hints).containsExactly(AllocationHint.JUMBO);
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("when a large body is released by a failing write, nothing leaks")
    void failedAfterReleaseLarge() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.FAIL_AFTER_RELEASE);
        assertThatThrownBy(() -> send(allocator, exchange, detailForBody(20_000))).isInstanceOf(IllegalStateException.class);
        assertThat(allocator.hints).containsExactly(AllocationHint.STREAMING_CHUNK);
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.outstanding()).isZero();
        assertThat(allocator.buffers.get(0).closeCalls.get()).isBetween(1, 2);
    }

    @Test
    @DisplayName("a deferred large body stays alive with one reference until the engine releases it")
    void deferredLarge() {
        TrackingAllocator allocator = new TrackingAllocator(base);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_DEFERRED);
        send(allocator, exchange, detailForBody(70_000));
        assertThat(exchange.refCountAtRespond).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isZero();
        assertThat(allocator.finalReleases.get()).isZero();
        exchange.completeDeferred();
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a buffer reporting a capacity below the body is released once and nothing is written to respond")
    void capacityBelowLargeBody() {
        int length = 4097;
        TrackingAllocator allocator = TrackingAllocator.reportingCapacity(base, length - 1);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_NOW);
        assertThatThrownBy(() -> send(allocator, exchange, detailForBody(length))).isInstanceOf(IllegalStateException.class);
        assertThat(exchange.respondCalls).isZero();
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
        assertThat(allocator.buffers.get(0).closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a buffer reporting exactly the body length as capacity is accepted")
    void capacityExactlyTheBody() {
        int length = 600;
        TrackingAllocator allocator = TrackingAllocator.reportingCapacity(base, length);
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_NOW);
        send(allocator, exchange, detailForBody(length));
        assertThat(exchange.bodySizeAtRespond).isEqualTo(length);
        assertThat(allocator.finalReleases.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("with no allocator bound, an oversize or any body is answered without a body and nothing is allocated")
    void unboundStillBodyless() {
        long before = base.stats().allocationCount();
        RecordingExchange exchange = exchange(RecordingExchange.Mode.ACCEPT_NOW);
        ProblemResponses.send(exchange, STATUS, detailForBody(4097), List.of());
        assertThat(exchange.response.body()).isNull();
        assertThat(base.stats().allocationCount()).isEqualTo(before);
    }
}
