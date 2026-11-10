package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * An exchange that plays the engine: it records the response, copies the body bytes at the moment it
 * takes the response, rejects a second response without touching the body, and releases the body the
 * way the engine does (at once, or only when {@link #completeDeferred()} is called).
 */
public final class RecordingExchange implements HttpExchange {

    /** How the exchange treats a response it is handed. */
    public enum Mode {
        /** Takes the response and releases the body at once, as an HTTP/1.1 write does. */
        ACCEPT_NOW,
        /** Takes the response and releases the body only on {@link #completeDeferred()}, as HTTP/2 does. */
        ACCEPT_DEFERRED,
        /** Throws {@link IllegalStateException} without taking the body. */
        REJECT_BEFORE_TAKE,
        /** Releases the body, then throws {@link IllegalStateException}, as a failed write does. */
        FAIL_AFTER_RELEASE
    }

    private final HttpRequest request;
    private final Mode mode;
    private boolean claimed;
    private LoanedBuffer pending;

    public HttpResponse response;
    public HttpStatus status;
    /** Bytes of the body as they were when the response was handed over; null when there was no body. */
    public byte[] bodyBytes;
    /** Declared size of the body when the response was handed over. */
    public long bodySizeAtRespond = -1;
    /** Reference count of the body when the response was handed over. */
    public int refCountAtRespond = -1;
    public boolean aliveAtRespond;
    public int respondCalls;

    public RecordingExchange(HttpMethod method, String path, List<HttpHeader> headers) {
        this(method, path, HttpVersion.HTTP_1_1, headers, Mode.ACCEPT_NOW);
    }

    public RecordingExchange(HttpMethod method, String path, HttpVersion version, List<HttpHeader> headers, Mode mode) {
        this.request = HttpRequest.noBody(method, path, version, headers);
        this.mode = mode;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse response) {
        respondCalls++;
        if (mode == Mode.REJECT_BEFORE_TAKE || claimed) {
            throw new IllegalStateException("response already sent");
        }
        claimed = true;
        this.response = response;
        this.status = response.status();
        LoanedBuffer body = response.body();
        if (body != null) {
            aliveAtRespond = body.isAlive();
            refCountAtRespond = body.refCount();
            bodySizeAtRespond = body.size();
            bodyBytes = body.segment().asSlice(0, body.size()).toArray(ValueLayout.JAVA_BYTE);
            switch (mode) {
                case ACCEPT_NOW -> body.close();
                case ACCEPT_DEFERRED -> pending = body;
                case FAIL_AFTER_RELEASE -> {
                    body.close();
                    throw new IllegalStateException("write failed");
                }
                default -> throw new AssertionError(mode);
            }
        } else if (mode == Mode.FAIL_AFTER_RELEASE) {
            throw new IllegalStateException("write failed");
        }
    }

    @Override
    public void respond(HttpStatus status) {
        this.status = status;
    }

    /** Releases a body held back by {@link Mode#ACCEPT_DEFERRED}. */
    public void completeDeferred() {
        LoanedBuffer body = pending;
        pending = null;
        if (body != null) {
            body.close();
        }
    }

    public Optional<String> header(String name) {
        return response.headers().stream().filter(h -> h.nameEqualsIgnoreCase(name)).map(HttpHeader::value).findFirst();
    }

    public String body() {
        return bodyBytes == null ? null : new String(bodyBytes, StandardCharsets.UTF_8);
    }
}
