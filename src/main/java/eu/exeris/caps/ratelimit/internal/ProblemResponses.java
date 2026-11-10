package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.AllocationHint;
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
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>The buffer comes from {@code allocate(AllocationHint)} with the smallest hint whose
 * {@code sizeBytes()} holds the body; no byte size is passed to the allocator. Every body this class
 * writes is fixed capability text plus a status line, far below {@link AllocationHint#JUMBO}, so a body
 * that exceeds the largest hint is a caller error and is rejected with {@link IllegalArgumentException}
 * before any allocation. The buffer capacity may exceed the body; only {@code size()} bytes are sent and
 * no write relies on the buffer content being zeroed.
 *
 * @since 0.1.0
 */
public final class ProblemResponses {

    private static final HttpHeader CACHE_CONTROL = new HttpHeader("Cache-Control", "no-store");
    private static final HttpHeader CONTENT_TYPE = new HttpHeader("Content-Type", "application/problem+json");

    /**
     * Upper bound on the number of cached bodies, counted over all statuses. A body for a detail that
     * does not fit is encoded per response and not retained. The count is read before the insert, so the
     * bound can be exceeded by at most the number of concurrent callers.
     */
    static final int MAX_CACHED_BODIES = 256;

    /** Encoded bodies per status and detail. Cached arrays are read-only and never leave this class. */
    private static final Map<HttpStatus, Map<String, byte[]>> BODIES = new ConcurrentHashMap<>();
    private static final AtomicInteger CACHED = new AtomicInteger();

    private ProblemResponses() {
    }

    /** Number of cached bodies, the value {@link #MAX_CACHED_BODIES} is enforced against. */
    static int cachedBodyCount() {
        return CACHED.get();
    }

    /** Whether a body for this status and detail is retained in the cache. */
    static boolean isCached(HttpStatus status, String detail) {
        Map<String, byte[]> byDetail = BODIES.get(status);
        return byDetail != null && byDetail.containsKey(detail);
    }

    private static byte[] encode(HttpStatus status, String detail) {
        return ("{\"type\":\"about:blank\",\"title\":\"" + status.reasonPhrase()
                + "\",\"status\":" + status.code() + ",\"detail\":\"" + detail + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bodyFor(HttpStatus status, String detail) {
        Map<String, byte[]> byDetail = BODIES.get(status);
        if (byDetail == null) {
            if (CACHED.get() >= MAX_CACHED_BODIES) {
                return encode(status, detail);
            }
            Map<String, byte[]> fresh = new ConcurrentHashMap<>();
            byDetail = BODIES.putIfAbsent(status, fresh);
            if (byDetail == null) {
                byDetail = fresh;
            }
        }
        byte[] bytes = byDetail.get(detail);
        if (bytes == null) {
            bytes = encode(status, detail);
            if (CACHED.get() < MAX_CACHED_BODIES) {
                byte[] raced = byDetail.putIfAbsent(detail, bytes);
                if (raced == null) {
                    CACHED.incrementAndGet();
                } else {
                    bytes = raced;
                }
            }
        }
        return bytes;
    }

    /**
     * Smallest hint whose size holds {@code length} bytes.
     *
     * @throws IllegalArgumentException when {@code length} exceeds {@link AllocationHint#JUMBO}
     */
    static AllocationHint hintFor(int length) {
        if (length <= AllocationHint.MICRO.sizeBytes()) {
            return AllocationHint.MICRO;
        }
        if (length <= AllocationHint.SMALL.sizeBytes()) {
            return AllocationHint.SMALL;
        }
        if (length <= AllocationHint.MEDIUM.sizeBytes()) {
            return AllocationHint.MEDIUM;
        }
        if (length <= AllocationHint.STREAMING_CHUNK.sizeBytes()) {
            return AllocationHint.STREAMING_CHUNK;
        }
        if (length <= AllocationHint.NETWORK_FRAME.sizeBytes()) {
            return AllocationHint.NETWORK_FRAME;
        }
        if (length <= AllocationHint.JUMBO.sizeBytes()) {
            return AllocationHint.JUMBO;
        }
        throw new IllegalArgumentException("problem body of " + length + " bytes exceeds "
                + AllocationHint.JUMBO.sizeBytes() + " bytes");
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
        AllocationHint hint = hintFor(bytes.length);
        MemoryAllocator allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        LoanedBuffer buffer = allocator.allocate(hint);
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
