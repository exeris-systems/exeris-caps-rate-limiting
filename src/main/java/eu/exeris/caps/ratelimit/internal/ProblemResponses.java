package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes RFC 9457 problem responses ({@code application/problem+json}).
 *
 * <p>The body carries only fixed text chosen by this capability. No exception message or request-derived
 * text goes into it.
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
        all.add(new HttpHeader("Content-Type", "application/problem+json"));
        all.add(new HttpHeader("Content-Length", Integer.toString(bytes.length)));
        all.add(new HttpHeader("Cache-Control", "no-store"));
        all.addAll(headers);
        exchange.respond(new HttpResponse(status, exchange.request().version(), List.copyOf(all), new HeapLoanedBuffer(bytes)));
    }
}
