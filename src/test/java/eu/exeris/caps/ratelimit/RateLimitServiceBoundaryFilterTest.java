package eu.exeris.caps.ratelimit;

import eu.exeris.caps.ratelimit.api.RateLimitFilterSettings;
import eu.exeris.caps.ratelimit.api.RateLimitServiceBoundaryBinding;
import eu.exeris.caps.ratelimit.internal.RecordingExchange;
import eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("rate-limiting as a Service Boundary admission filter")
class RateLimitServiceBoundaryFilterTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String PEER_HEADER = "X-Test-Peer";

    private final AtomicLong clock = new AtomicLong(0);
    private ApiSurfaceRegistry registry;
    private RateLimitModule module;

    @BeforeEach
    void setUp() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        registry = sbc.apiSurfaceRegistry();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login,password-reset",
                "exeris.caps.ratelimit.group.login.routes", "POST /login",
                "exeris.caps.ratelimit.group.login.capacity", "2",
                "exeris.caps.ratelimit.group.login.refill-seconds", "30",
                "exeris.caps.ratelimit.group.password-reset.routes", "POST  /password/reset-request, POST /password/reset",
                "exeris.caps.ratelimit.group.password-reset.capacity", "1",
                RateLimitFilterSettings.KEY_TRUSTED_PROXIES, "10.0.0.0/8"));
        // The test transport reports the peer in a header that only the test sets.
        module = new RateLimitModule(RateLimitServiceBoundaryBinding.of(sbc.requestFilterChain(), settings,
                exchange -> exchange.request().firstHeader(PEER_HEADER), clock::get));
        module.initialize();
        for (String path : List.of("/login", "/password/reset-request", "/password/reset", "/health")) {
            registry.registerRoute(ApiSurfaceRoute.builder()
                    .method(HttpMethod.POST)
                    .pathPattern(path)
                    .actionName(path)
                    .handler(ex -> ex.respond(HttpStatus.OK))
                    .build());
        }
    }

    private RecordingExchange post(String path, String peer, HttpHeader... extra) {
        List<HttpHeader> headers = new java.util.ArrayList<>(List.of(extra));
        headers.add(new HttpHeader(PEER_HEADER, peer));
        RecordingExchange exchange = new RecordingExchange(HttpMethod.POST, path, headers);
        registry.handle(exchange);
        return exchange;
    }

    @Test
    @DisplayName("over the limit the request is answered 429 with Retry-After and a problem body")
    void throttles() {
        assertThat(post("/login", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
        assertThat(post("/login", "203.0.113.7").status).isEqualTo(HttpStatus.OK);

        clock.addAndGet(10 * SECOND);
        RecordingExchange third = post("/login", "203.0.113.7");
        assertThat(third.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.header("Retry-After")).contains("20");
        assertThat(third.header("Content-Type")).contains("application/problem+json");
        assertThat(third.body()).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Too many requests for this operation; retry later.\"}");

        clock.addAndGet(20 * SECOND);
        assertThat(post("/login", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("each route has its own bucket, also within one group")
    void perRouteIsolation() {
        post("/login", "203.0.113.7");
        post("/login", "203.0.113.7");
        assertThat(post("/login", "203.0.113.7").status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(post("/password/reset-request", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
        assertThat(post("/password/reset", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
        assertThat(post("/password/reset-request", "203.0.113.7").status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("each client has its own bucket")
    void perClientIsolation() {
        post("/password/reset", "203.0.113.7");
        assertThat(post("/password/reset", "203.0.113.7").status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(post("/password/reset", "203.0.113.8").status).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a client cannot escape its bucket by spoofing X-Forwarded-For")
    void spoofedHeaderIgnored() {
        post("/password/reset", "203.0.113.7", new HttpHeader("X-Forwarded-For", "198.51.100.1"));
        assertThat(post("/password/reset", "203.0.113.7", new HttpHeader("X-Forwarded-For", "198.51.100.2")).status)
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("behind a trusted proxy the forwarded client is metered, not the proxy")
    void trustedProxy() {
        post("/password/reset", "10.0.0.2", new HttpHeader("X-Forwarded-For", "198.51.100.1"));
        assertThat(post("/password/reset", "10.0.0.2", new HttpHeader("X-Forwarded-For", "198.51.100.2")).status)
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/password/reset", "10.0.0.3", new HttpHeader("X-Forwarded-For", "198.51.100.1")).status)
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a route outside every group is not metered")
    void unlistedRoute() {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/health", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("terminate removes the filter; with no host bound the module refuses to initialise")
    void lifecycle() {
        assertThat(registry.filters()).hasSize(1);
        module.terminate();
        assertThat(registry.filters()).isEmpty();
        assertThatThrownBy(() -> new RateLimitModule().initialize())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no admission host");
    }

    @Test
    @DisplayName("a route in two groups, or a group without routes, is a configuration error")
    void invalidSettings() {
        assertThatThrownBy(() -> RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "a,b",
                "exeris.caps.ratelimit.group.a.routes", "POST /login",
                "exeris.caps.ratelimit.group.b.routes", "POST /login")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimitFilterSettings.fromProperties(Map.of(RateLimitFilterSettings.KEY_GROUPS, "a")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void peerUnknownSharesOneBucket() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login",
                "exeris.caps.ratelimit.group.login.routes", "POST /login",
                "exeris.caps.ratelimit.group.login.capacity", "1"));
        new RateLimitModule(RateLimitServiceBoundaryBinding.of(sbc.requestFilterChain(), settings,
                exchange -> Optional.empty(), clock::get)).initialize();
        sbc.apiSurfaceRegistry().registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.POST).pathPattern("/login")
                .actionName("login").handler(ex -> ex.respond(HttpStatus.OK)).build());

        RecordingExchange first = new RecordingExchange(HttpMethod.POST, "/login", List.of());
        RecordingExchange second = new RecordingExchange(HttpMethod.POST, "/login", List.of(new HttpHeader("X-Forwarded-For", "198.51.100.3")));
        sbc.apiSurfaceRegistry().handle(first);
        sbc.apiSurfaceRegistry().handle(second);
        assertThat(first.status).isEqualTo(HttpStatus.OK);
        assertThat(second.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
