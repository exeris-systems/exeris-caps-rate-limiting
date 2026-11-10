package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes RFC 9457 problem responses ({@code application/problem+json}).
 *
 * <p>The body carries only fixed text chosen by this capability. No exception message or request-derived
 * text goes into it.
 *
 * <p>The body is written into a buffer from the kernel allocator bound for the request. Until
 * {@code respond} returns normally the buffer belongs to this class and every other path releases it
 * once; after a normal return the engine owns it and this class never touches it again. When no
 * allocator is bound the response is sent without a body.
 *
 * @since 0.1.0
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    /**
     * Answers the exchange with a problem response.
     *
     * @param exchange exchange to answer
     * @param status status
     * @param detail fixed detail text
     * @param headers additional headers
     */
    public static void send(HttpExchange exchange, HttpStatus status, String detail, List<HttpHeader> headers) {
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.reasonPhrase()
                + "\",\"status\":" + status.code() + ",\"detail\":\"" + detail + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        List<HttpHeader> all = new ArrayList<>(headers.size() + 3);
        all.add(new HttpHeader("Cache-Control", "no-store"));
        all.addAll(headers);
        HttpVersion version = exchange.request().version();
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            exchange.respond(HttpResponse.noBody(status, version, List.copyOf(all)));
            return;
        }
        all.add(0, new HttpHeader("Content-Type", "application/problem+json"));
        MemoryAllocator allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        LoanedBuffer buffer = allocator.allocateNetwork(bytes.length);
        boolean handedOver = false;
        try {
            if (buffer.capacity() < bytes.length) {
                throw new IllegalStateException("allocator returned a buffer smaller than requested");
            }
            MemorySegment.copy(bytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0L, bytes.length);
            buffer.setSize(bytes.length);
            exchange.respond(new HttpResponse(status, version, List.copyOf(all), buffer));
            handedOver = true;
        } finally {
            if (!handedOver) {
                buffer.close();
            }
        }
    }
}
