package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** An exchange that records the response it is answered with. */
public final class RecordingExchange implements HttpExchange {

    private final HttpRequest request;
    public HttpResponse response;
    public HttpStatus status;

    public RecordingExchange(HttpMethod method, String path, List<HttpHeader> headers) {
        this.request = HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, headers);
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse response) {
        this.response = response;
        this.status = response.status();
    }

    @Override
    public void respond(HttpStatus status) {
        this.status = status;
    }

    public Optional<String> header(String name) {
        return response.headers().stream().filter(h -> h.nameEqualsIgnoreCase(name)).map(HttpHeader::value).findFirst();
    }

    public String body() {
        byte[] bytes = response.body().segment().asSlice(0, response.body().size()).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
