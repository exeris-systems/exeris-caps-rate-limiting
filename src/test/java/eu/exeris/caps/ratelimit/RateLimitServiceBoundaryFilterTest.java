package eu.exeris.caps.ratelimit;

import eu.exeris.caps.ratelimit.api.RateLimitFilterSettings;
import eu.exeris.caps.ratelimit.api.RateLimitServiceBoundaryBinding;
import eu.exeris.caps.ratelimit.internal.RecordingExchange;
import eu.exeris.caps.ratelimit.internal.TrackingAllocator;
import eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
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
    private MemoryAllocator base;
    private TrackingAllocator allocator;

    @BeforeEach
    void setUp() {
        base = ServiceLoader.load(MemoryProvider.class).findFirst().orElseThrow()
                .createAllocator(MemoryProviderConfig.defaults());
        allocator = new TrackingAllocator(base);
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
        module = new RateLimitModule(RateLimitServiceBoundaryBinding.of(registry, settings,
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
        module.ready();
    }

    @AfterEach
    void closeAllocator() {
        base.close();
    }

    private RecordingExchange post(String path, String peer, HttpHeader... extra) {
        return post(path, peer, HttpVersion.HTTP_1_1, RecordingExchange.Mode.ACCEPT_NOW, extra);
    }

    private RecordingExchange post(String path, String peer, HttpVersion version, RecordingExchange.Mode mode,
                                   HttpHeader... extra) {
        List<HttpHeader> headers = new java.util.ArrayList<>(List.of(extra));
        headers.add(new HttpHeader(PEER_HEADER, peer));
        RecordingExchange exchange = new RecordingExchange(HttpMethod.POST, path, version, headers, mode);
        java.lang.ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> registry.handle(exchange));
        return exchange;
    }

    @Test
    @DisplayName("each throttled request takes one kernel buffer sized to the body, and it is released once")
    void throttledResponsesUseKernelBuffers() {
        post("/password/reset", "203.0.113.7");
        assertThat(allocator.allocations()).isZero();
        for (int i = 0; i < 5; i++) {
            RecordingExchange throttled = post("/password/reset", "203.0.113.7");
            assertThat(throttled.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(throttled.response.body()).isSameAs(allocator.buffers.get(i));
            assertThat(allocator.requested.get(i).longValue()).isEqualTo(throttled.bodySizeAtRespond);
            assertThat(allocator.buffers.get(i).closeCalls.get()).isEqualTo(1);
        }
        assertThat(allocator.allocations()).isEqualTo(5);
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("an HTTP/2 request is answered on HTTP/2 and the body outlives the admission call")
    void http2LifetimeThroughTheHost() {
        post("/password/reset", "203.0.113.7");
        RecordingExchange throttled = post("/password/reset", "203.0.113.7", HttpVersion.HTTP_2,
                RecordingExchange.Mode.ACCEPT_DEFERRED);
        assertThat(throttled.response.version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(throttled.refCountAtRespond).isEqualTo(1);
        assertThat(allocator.buffers.get(0).isAlive()).isTrue();
        assertThat(allocator.buffers.get(0).closeCalls.get()).isZero();
        throttled.completeDeferred();
        assertThat(allocator.outstanding()).isZero();
    }

    @Test
    @DisplayName("allowed requests allocate nothing")
    void allowedRequestsAllocateNothing() {
        long before = base.stats().allocationCount();
        assertThat(post("/login", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
        assertThat(post("/health", "203.0.113.7").status).isEqualTo(HttpStatus.OK);
        assertThat(base.stats().allocationCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a throttled request on a thread with no allocator bound is answered 429 without a body")
    void unboundThread() {
        post("/password/reset", "203.0.113.7");
        RecordingExchange exchange = new RecordingExchange(HttpMethod.POST, "/password/reset",
                List.of(new HttpHeader(PEER_HEADER, "203.0.113.7")));
        registry.handle(exchange);
        assertThat(exchange.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.response.body()).isNull();
        assertThat(exchange.header("Retry-After")).isPresent();
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
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RateLimitFilterSettings.fromProperties(Map.of(RateLimitFilterSettings.KEY_GROUPS, "a")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a configured route is canonicalised: method upper-cased, trailing slash dropped")
    void routesAreCanonicalised() {
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "a",
                "exeris.caps.ratelimit.group.a.routes", "post /login/, Get  /me"));
        assertThat(settings.groups().get(0).routes()).containsExactlyInAnyOrder("POST /login", "GET /me");
        assertThatThrownBy(() -> RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "a,b",
                "exeris.caps.ratelimit.group.a.routes", "POST /login",
                "exeris.caps.ratelimit.group.b.routes", "post /login/")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every unreadable setting surfaces as IllegalStateException")
    void settingsFailuresAreIllegalState() {
        for (String[] bad : new String[][] {
                {RateLimitFilterSettings.KEY_GROUPS + "|a", "exeris.caps.ratelimit.group.a.routes|POST /x", "exeris.caps.ratelimit.group.a.capacity|0"},
                {RateLimitFilterSettings.KEY_MAX_KEYS + "|0"},
                {RateLimitFilterSettings.KEY_MAX_KEYS + "|99999999999"},
                {RateLimitFilterSettings.KEY_FORWARDED_HEADER + "|x-real-ip"},
                {RateLimitFilterSettings.KEY_GROUPS + "|a,b", "exeris.caps.ratelimit.group.a.routes|POST /x",
                        "exeris.caps.ratelimit.group.b.routes|POST /x"}}) {
            Map<String, String> props = new java.util.HashMap<>();
            for (String entry : bad) {
                props.put(entry.substring(0, entry.indexOf('|')), entry.substring(entry.indexOf('|') + 1));
            }
            assertThatThrownBy(() -> RateLimitFilterSettings.fromProperties(props))
                    .as(props.toString()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("the forwarding header defaults to X-Forwarded-For and is configurable")
    void forwardedHeaderSetting() {
        assertThat(RateLimitFilterSettings.fromProperties(Map.of()).forwardedHeader())
                .isEqualTo(eu.exeris.caps.ratelimit.api.ForwardedHeader.X_FORWARDED_FOR);
        assertThat(RateLimitFilterSettings.fromProperties(Map.of(RateLimitFilterSettings.KEY_FORWARDED_HEADER, "Forwarded"))
                .forwardedHeader()).isEqualTo(eu.exeris.caps.ratelimit.api.ForwardedHeader.FORWARDED);
    }

    @Test
    @DisplayName("behind a proxy that appends X-Forwarded-For, a client-sent Forwarded gains no fresh buckets")
    void forwardedInjectionKeepsOneBucket() {
        post("/password/reset", "10.0.0.2", new HttpHeader("X-Forwarded-For", "198.51.100.1"),
                new HttpHeader("Forwarded", "for=192.0.2.1"));
        assertThat(post("/password/reset", "10.0.0.2", new HttpHeader("X-Forwarded-For", "198.51.100.1"),
                new HttpHeader("Forwarded", "for=192.0.2.2")).status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void peerUnknownSharesOneBucket() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login",
                "exeris.caps.ratelimit.group.login.routes", "POST /login",
                "exeris.caps.ratelimit.group.login.capacity", "1"));
        RateLimitModule testModule = new RateLimitModule(RateLimitServiceBoundaryBinding.of(sbc.apiSurfaceRegistry(), settings,
                exchange -> Optional.empty(), clock::get));
        testModule.initialize();
        sbc.apiSurfaceRegistry().registerRoute(ApiSurfaceRoute.builder().method(HttpMethod.POST).pathPattern("/login")
                .actionName("login").handler(ex -> ex.respond(HttpStatus.OK)).build());
        testModule.ready();

        RecordingExchange first = new RecordingExchange(HttpMethod.POST, "/login", List.of());
        RecordingExchange second = new RecordingExchange(HttpMethod.POST, "/login", List.of(new HttpHeader("X-Forwarded-For", "198.51.100.3")));
        sbc.apiSurfaceRegistry().handle(first);
        sbc.apiSurfaceRegistry().handle(second);
        assertThat(first.status).isEqualTo(HttpStatus.OK);
        assertThat(second.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a matching route passes validation at ready()")
    void matchingRoutePassesValidation() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry reg = sbc.apiSurfaceRegistry();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login",
                "exeris.caps.ratelimit.group.login.routes", "POST /auth/login",
                "exeris.caps.ratelimit.group.login.capacity", "2"));
        RateLimitModule mod = new RateLimitModule(RateLimitServiceBoundaryBinding.of(reg, settings,
                exchange -> Optional.of("192.0.2.1")));
        mod.initialize();
        reg.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/auth/login")
                .actionName("login")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());
        mod.ready();
    }

    @Test
    @DisplayName("a typo in a configured route fails at ready() naming the undeclared route")
    void typoInConfiguredRouteFailsAtReady() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry reg = sbc.apiSurfaceRegistry();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login",
                "exeris.caps.ratelimit.group.login.routes", "POST /auth/logni",
                "exeris.caps.ratelimit.group.login.capacity", "2"));
        RateLimitModule mod = new RateLimitModule(RateLimitServiceBoundaryBinding.of(reg, settings,
                exchange -> Optional.of("192.0.2.1")));
        mod.initialize();
        reg.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/auth/login")
                .actionName("login")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());
        assertThatThrownBy(mod::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POST /auth/logni");
    }

    @Test
    @DisplayName("a differently written template fails at ready() naming the undeclared route")
    void differentlyWrittenTemplateFailsAtReady() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry reg = sbc.apiSurfaceRegistry();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "reset",
                "exeris.caps.ratelimit.group.reset.routes", "POST /auth/reset/{userId}",
                "exeris.caps.ratelimit.group.reset.capacity", "2"));
        RateLimitModule mod = new RateLimitModule(RateLimitServiceBoundaryBinding.of(reg, settings,
                exchange -> Optional.of("192.0.2.1")));
        mod.initialize();
        reg.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/auth/reset/{id}")
                .actionName("reset")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());
        assertThatThrownBy(mod::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POST /auth/reset/{userId}");
    }

    @Test
    @DisplayName("a route registered after the filter passes validation at ready()")
    void routeRegisteredAfterFilterPassesAtReady() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry reg = sbc.apiSurfaceRegistry();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "verify",
                "exeris.caps.ratelimit.group.verify.routes", "POST /auth/verify",
                "exeris.caps.ratelimit.group.verify.capacity", "1"));
        RateLimitModule mod = new RateLimitModule(RateLimitServiceBoundaryBinding.of(reg, settings,
                exchange -> Optional.of("192.0.2.1")));
        // Filter is attached first during initialize()
        mod.initialize();
        assertThat(reg.filters()).hasSize(1);

        // Route is registered after the filter
        reg.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/auth/verify")
                .actionName("verify")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());

        // ready() runs after route registration and succeeds
        mod.ready();

        RecordingExchange exchange = new RecordingExchange(HttpMethod.POST, "/auth/verify", List.of());
        reg.handle(exchange);
        assertThat(exchange.status).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an undeclared route halts admission with 500 if ready() was not called before serving a request")
    void undeclaredRouteFailsBeforeFirstRequest() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry reg = sbc.apiSurfaceRegistry();
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login",
                "exeris.caps.ratelimit.group.login.routes", "POST /auth/typo",
                "exeris.caps.ratelimit.group.login.capacity", "2"));
        RateLimitModule mod = new RateLimitModule(RateLimitServiceBoundaryBinding.of(reg, settings,
                exchange -> Optional.of("192.0.2.1")));
        mod.initialize();
        reg.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/auth/login")
                .actionName("login")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());
        RecordingExchange exchange = new RecordingExchange(HttpMethod.POST, "/auth/login", List.of());
        reg.handle(exchange);
        assertThat(exchange.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("a request before ready() under a valid configuration is admitted and validates the routes once")
    void validConfigurationAdmitsRequestBeforeReady() {
        ServiceBoundaryCoreModule sbc = new ServiceBoundaryCoreModule();
        ApiSurfaceRegistry real = sbc.apiSurfaceRegistry();
        AtomicInteger routeReads = new AtomicInteger();
        ApiSurfaceRegistry counting = (ApiSurfaceRegistry) Proxy.newProxyInstance(
                ApiSurfaceRegistry.class.getClassLoader(), new Class<?>[] {ApiSurfaceRegistry.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("routes") && method.getParameterCount() == 0) {
                        routeReads.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RateLimitFilterSettings settings = RateLimitFilterSettings.fromProperties(Map.of(
                RateLimitFilterSettings.KEY_GROUPS, "login",
                "exeris.caps.ratelimit.group.login.routes", "POST /auth/login",
                "exeris.caps.ratelimit.group.login.capacity", "5"));
        RateLimitModule mod = new RateLimitModule(RateLimitServiceBoundaryBinding.of(counting, settings,
                exchange -> Optional.of("192.0.2.1")));
        mod.initialize();
        real.registerRoute(ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/auth/login")
                .actionName("login")
                .handler(ex -> ex.respond(HttpStatus.OK))
                .build());

        RecordingExchange first = new RecordingExchange(HttpMethod.POST, "/auth/login", List.of());
        real.handle(first);
        RecordingExchange second = new RecordingExchange(HttpMethod.POST, "/auth/login", List.of());
        real.handle(second);
        mod.ready();

        assertThat(first.status).isEqualTo(HttpStatus.OK);
        assertThat(second.status).isEqualTo(HttpStatus.OK);
        assertThat(routeReads.get()).isEqualTo(1);
    }
}
