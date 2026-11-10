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


@DisplayName("the 429 body is not allocated on the heap per response")
class ProblemResponseAllocationTest {

    private static final String DETAIL = "Too many requests for this operation; retry later.";
    private static final List<HttpHeader> HEADERS = List.of(new HttpHeader("Retry-After", "7"));

    @Test
    @DisplayName("steady-state heap bytes per response stay below the body length")
    void boundedBytesPerResponse() throws Exception {
        MemoryAllocator allocator = ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults());
        try {
            int bodyLength = ("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\""
                    + DETAIL + "\"}").length();
            JfrAllocationMonitor.Config config = JfrAllocationMonitor.Config.ofDefaults("rate-limiting",
                    ProblemResponseAllocationTest.class.getName());
            JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
                DiscardingExchange discarding = new DiscardingExchange();
                java.lang.ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
                    for (int i = 0; i < iterations; i++) {
                        ProblemResponses.send(discarding, HttpStatus.TOO_MANY_REQUESTS, DETAIL, HEADERS);
                    }
                });
            });
            System.out.println("allocated bytes per response: "
                    + (double) result.allocatedBytesDelta() / result.hotPathIterations() + " (body " + bodyLength + ")");
            JfrAllocationMonitor.assertBoundedBytesPerIteration(result, bodyLength,
                    "a response must not allocate a body-sized heap array");
        } finally {
            allocator.close();
        }
    }

    private static volatile byte[] sink;

    @Test
    @DisplayName("the measurement discriminates: a body-sized array per iteration exceeds the same budget")
    void controlAllocatesAndIsDetected() throws Exception {
        JfrAllocationMonitor.Config config = JfrAllocationMonitor.Config.ofDefaults("rate-limiting",
                ProblemResponseAllocationTest.class.getName());
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                sink = new byte[125];
            }
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                JfrAllocationMonitor.assertBoundedBytesPerIteration(result, 125, "control"))
                .isInstanceOf(AssertionError.class);
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
        }
    }
}
