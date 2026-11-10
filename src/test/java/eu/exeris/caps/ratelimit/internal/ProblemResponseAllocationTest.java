package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;


@DisplayName("the 429 body lives in the kernel buffer, not on the heap")
class ProblemResponseAllocationTest {

    private static final List<HttpHeader> HEADERS = List.of(new HttpHeader("Retry-After", "7"));
    private static final String SHORT_DETAIL = "Too many requests for this operation; retry later. ".repeat(2);
    private static final String LONG_DETAIL = "Too many requests for this operation; retry later. ".repeat(80);

    /**
     * Largest growth of the per-response heap figure that the extra body length may cause. A body-sized
     * copy would grow by the whole difference in length; a tenth of it is far from that and far above
     * the objects the JIT may or may not allocate. Only growth is a failure: a long body that costs
     * less than a short one is not.
     */
    private static final double GROWTH_BOUND_BYTES = (LONG_DETAIL.length() - SHORT_DETAIL.length()) / 10.0;

    /**
     * Budget for one response with the long body, in any JIT state: several times the fixed cost of the
     * kernel and the header objects, and far below the length of the long body, which a body-sized
     * array per response would exceed on its own.
     */
    private static final double LONG_BODY_BUDGET_BYTES = 1024.0;

    private static final int WARMUP_CALLS = 300_000;
    private static final int WINDOW_CALLS = 2_000;
    private static final int ROUNDS = 8;

    private static volatile byte[] sink;

    private static MemoryAllocator allocator() {
        return ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults());
    }

    private static Runnable send(DiscardingExchange exchange, String detail) {
        return () -> ProblemResponses.send(exchange, HttpStatus.TOO_MANY_REQUESTS, detail, HEADERS);
    }

    /** Minimum heap bytes per call of the short-body and the long-body path, interleaved. */
    private static double[] shortAndLong(MemoryAllocator allocator, Runnable shortCall, Runnable longCall) {
        double[][] result = new double[1][];
        ScopedValueRun.run(allocator, () -> result[0] = HeapMeter.interleavedMinima(
                WARMUP_CALLS, WINDOW_CALLS, ROUNDS, shortCall, longCall));
        return result[0];
    }

    private static double growth(double[] shortAndLong) {
        return shortAndLong[1] - shortAndLong[0];
    }

    @Test
    @DisplayName("heap bytes per response do not grow with the body length")
    void heapPerResponseIsIndependentOfBodyLength() {
        MemoryAllocator allocator = allocator();
        try {
            DiscardingExchange exchange = new DiscardingExchange();
            double[] m = shortAndLong(allocator, send(exchange, SHORT_DETAIL), send(exchange, LONG_DETAIL));
            System.out.println("heap bytes per response (minimum): short=" + m[0] + " long=" + m[1]
                    + " (detail lengths " + SHORT_DETAIL.length() + " / " + LONG_DETAIL.length() + ")");

            assertThat(growth(m)).as("long %s minus short %s", m[1], m[0]).isLessThanOrEqualTo(GROWTH_BOUND_BYTES);
            assertThat(m[1]).as("long-body response").isLessThan(LONG_BODY_BUDGET_BYTES);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("the measurement discriminates: a body-sized heap array per response breaks the same assertions")
    void controlAllocatesAndIsDetected() {
        MemoryAllocator allocator = allocator();
        try {
            double[] m = shortAndLong(allocator,
                    () -> sink = SHORT_DETAIL.getBytes(java.nio.charset.StandardCharsets.UTF_8).clone(),
                    () -> sink = LONG_DETAIL.getBytes(java.nio.charset.StandardCharsets.UTF_8).clone());
            System.out.println("control heap bytes per response (minimum): short=" + m[0] + " long=" + m[1]);

            assertThat(growth(m)).isGreaterThan(GROWTH_BOUND_BYTES);
            assertThat(m[1]).isGreaterThanOrEqualTo(LONG_BODY_BUDGET_BYTES);
        } finally {
            allocator.close();
        }
    }

    private static final class ScopedValueRun {
        static void run(MemoryAllocator allocator, Runnable action) {
            java.lang.ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(action);
        }
    }

    /** Releases the body without copying it. */
    private static final class DiscardingExchange implements eu.exeris.kernel.spi.http.HttpExchange {
        private final eu.exeris.kernel.spi.http.HttpRequest request = eu.exeris.kernel.spi.http.HttpRequest.noBody(
                HttpMethod.POST, "/login", HttpVersion.HTTP_1_1, List.of());

        @Override
        public eu.exeris.kernel.spi.http.HttpRequest request() {
            return request;
        }

        @Override
        public void respond(eu.exeris.kernel.spi.http.HttpResponse response) {
            if (response.body() != null) {
                response.body().close();
            }
        }

        @Override
        public void respond(HttpStatus status) {
            // a bodyless response carries nothing to measure in this double
        }
    }
}
