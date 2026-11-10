package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
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
     * Allowed difference between the per-response heap figures of the two bodies. It is far below the
     * difference in body length and above the run-to-run noise of the measurement.
     */
    private static final double TOLERANCE_BYTES = 8.0;

    /** The kernel's fixed cost per response, measured, plus a modest margin. */
    private static final double SHORT_BODY_BOUND_BYTES = 128.0;

    private static final int WARM_UP_RESPONSES = 300_000;

    private static volatile byte[] sink;

    /** Median of several measurements, so one run in which the JIT elided an object does not decide. */
    private static double medianBytesPerResponse(MemoryAllocator allocator, String detail) throws Exception {
        double[] runs = new double[5];
        for (int i = 0; i < runs.length; i++) {
            runs[i] = bytesPerResponse(allocator, detail);
        }
        java.util.Arrays.sort(runs);
        return runs[runs.length / 2];
    }

    private static double bytesPerResponse(MemoryAllocator allocator, String detail) throws Exception {
        // Fills the body cache and drives the path to its compiled steady state, so the figure is
        // not a function of when the JIT happened to eliminate an object.
        DiscardingExchange warm = new DiscardingExchange();
        ScopedValueRun.run(allocator, () -> {
            for (int i = 0; i < WARM_UP_RESPONSES; i++) {
                ProblemResponses.send(warm, HttpStatus.TOO_MANY_REQUESTS, detail, HEADERS);
            }
        });
        JfrAllocationMonitor.Config config = JfrAllocationMonitor.Config.ofDefaults("rate-limiting",
                ProblemResponseAllocationTest.class.getName());
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            DiscardingExchange discarding = new DiscardingExchange();
            ScopedValueRun.run(allocator, () -> {
                for (int i = 0; i < iterations; i++) {
                    ProblemResponses.send(discarding, HttpStatus.TOO_MANY_REQUESTS, detail, HEADERS);
                }
            });
        });
        return (double) result.allocatedBytesDelta() / result.hotPathIterations();
    }

    /** Same shape as production, plus a body-sized heap array per response. */
    private static double bytesPerResponseWithHeapCopy(String detail) throws Exception {
        byte[] body = detail.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JfrAllocationMonitor.Config config = JfrAllocationMonitor.Config.ofDefaults("rate-limiting",
                ProblemResponseAllocationTest.class.getName());
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                sink = body.clone();
            }
        });
        return (double) result.allocatedBytesDelta() / result.hotPathIterations();
    }

    private static MemoryAllocator allocator() {
        return ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults());
    }

    @Test
    @DisplayName("heap bytes per response do not depend on the body length")
    void heapPerResponseIsIndependentOfBodyLength() throws Exception {
        MemoryAllocator allocator = allocator();
        try {
            double shortBytes = medianBytesPerResponse(allocator, SHORT_DETAIL);
            double longBytes = medianBytesPerResponse(allocator, LONG_DETAIL);
            System.out.println("heap bytes per response: short=" + shortBytes + " long=" + longBytes
                    + " (detail lengths " + SHORT_DETAIL.length() + " / "
                    + LONG_DETAIL.length() + ")");

            assertThat(Math.abs(longBytes - shortBytes)).as("long %s vs short %s", longBytes, shortBytes)
                    .isLessThanOrEqualTo(TOLERANCE_BYTES)
                    .isLessThan((LONG_DETAIL.length() - SHORT_DETAIL.length()) / 100.0);
            assertThat(shortBytes).as("the kernel's fixed cost per response").isLessThan(SHORT_BODY_BOUND_BYTES);
        } finally {
            allocator.close();
        }
    }

    @Test
    @DisplayName("the measurement discriminates: a body-sized heap array per response breaks the same assertion")
    void controlAllocatesAndIsDetected() throws Exception {
        double shortBytes = bytesPerResponseWithHeapCopy(SHORT_DETAIL);
        double longBytes = bytesPerResponseWithHeapCopy(LONG_DETAIL);
        System.out.println("control heap bytes per response: short=" + shortBytes + " long=" + longBytes);

        assertThat(Math.abs(longBytes - shortBytes)).isGreaterThan(TOLERANCE_BYTES);
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
