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
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final HttpHeader CACHE_CONTROL = new HttpHeader("Cache-Control", "no-store");
    private static final HttpHeader CONTENT_TYPE = new HttpHeader("Content-Type", "application/problem+json");

    /** Encoded bodies per status and detail; the text is fixed, so each is encoded once. */
    private static final Map<HttpStatus, Map<String, byte[]>> BODIES = new ConcurrentHashMap<>();

    private ProblemResponses() {
    }

    private static byte[] bodyFor(HttpStatus status, String detail) {
        Map<String, byte[]> byDetail = BODIES.get(status);
        if (byDetail == null) {
            byDetail = BODIES.computeIfAbsent(status, k -> new ConcurrentHashMap<>());
        }
        byte[] bytes = byDetail.get(detail);
        if (bytes == null) {
            String body = "{\"type\":\"about:blank\",\"title\":\"" + status.reasonPhrase()
                    + "\",\"status\":" + status.code() + ",\"detail\":\"" + detail + "\"}";
            bytes = byDetail.computeIfAbsent(detail, k -> body.getBytes(StandardCharsets.UTF_8));
        }
        return bytes;
    }

    private static List<HttpHeader> headers(boolean withContentType, List<HttpHeader> extra) {
        for (int i = 0; i < extra.size(); i++) {
            Objects.requireNonNull(extra.get(i), "header");
        }
        return new Headers(withContentType, extra);
    }

    /**
     * Read-only view of the fixed headers followed by the caller's headers. It holds the caller's list
     * by reference, so it must only be built from lists that are not modified afterwards.
     */
    private static final class Headers extends AbstractList<HttpHeader> implements RandomAccess {
        private final boolean withContentType;
        private final List<HttpHeader> extra;

        Headers(boolean withContentType, List<HttpHeader> extra) {
            this.withContentType = withContentType;
            this.extra = extra;
        }

        @Override
        public HttpHeader get(int index) {
            int i = index;
            if (withContentType) {
                if (i == 0) {
                    return CONTENT_TYPE;
                }
                i--;
            }
            if (i == 0) {
                return CACHE_CONTROL;
            }
            return extra.get(i - 1);
        }

        @Override
        public int size() {
            return (withContentType ? 2 : 1) + extra.size();
        }
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
        byte[] bytes = bodyFor(status, detail);
        HttpVersion version = exchange.request().version();
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            exchange.respond(HttpResponse.noBody(status, version, headers(false, headers)));
            return;
        }
        MemoryAllocator allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        LoanedBuffer buffer = allocator.allocateNetwork(bytes.length);
        boolean handedOver = false;
        try {
            if (buffer.capacity() < bytes.length) {
                throw new IllegalStateException("allocator returned a buffer smaller than requested");
            }
            MemorySegment.copy(bytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0L, bytes.length);
            buffer.setSize(bytes.length);
            exchange.respond(new HttpResponse(status, version, headers(true, headers), buffer));
            handedOver = true;
        } finally {
            if (!handedOver) {
                buffer.close();
            }
        }
    }
}
