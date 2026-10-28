package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.PeerAddressSource;
import eu.exeris.caps.ratelimit.api.RateLimitDecision;
import eu.exeris.caps.ratelimit.api.RateLimitFilterSettings;
import eu.exeris.caps.ratelimit.api.RouteGroup;
import eu.exeris.caps.serviceboundarycore.api.AdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.AdmissionPhase;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Service Boundary admission filter that meters the configured route groups per client and route.
 *
 * <p>It runs in the {@code RATE_LIMITING} phase, before authentication, so a throttled request costs no
 * signature or password work. The bucket key is the client address (see {@link ClientAddressResolver})
 * and the matched route; the request body is never read. A request over its limit is answered 429 with
 * {@code Retry-After} and a problem body. Routes outside every group pass untouched.
 *
 * <p>This is the only part of the capability that touches HTTP types (ADR-099 obligation 4: the policy
 * caps bind as {@code RequestFilterChain} filters and answer the request themselves). It meters with a
 * {@link TokenBucketLimiter} rather than a {@link eu.exeris.caps.ratelimit.api.RateLimitPolicy}: the
 * policy keys its table by a 64-bit hash, so two clients can share a bucket by collision and a
 * credential-route bucket would then be exhausted by someone else, and it reports no per-bucket state
 * to evict. The limiter compares keys exactly and refuses new keys when its table is full.
 *
 * @since 0.1.0
 */
public final class RateLimitAdmissionFilter implements AdmissionFilter {

    /** Filter name in the chain. */
    public static final String NAME = "rate-limiting";

    private static final String DETAIL = "Too many requests for this operation; retry later.";

    private final Map<String, TokenBucketLimiter> limiterByRoute;
    private final ClientAddressResolver addresses;
    private final Runnable routeValidator;

    public RateLimitAdmissionFilter(RateLimitFilterSettings settings, PeerAddressSource peerAddress, LongSupplier nanoClock) {
        this(settings, peerAddress, nanoClock, null);
    }

    public RateLimitAdmissionFilter(RateLimitFilterSettings settings, PeerAddressSource peerAddress,
                                    LongSupplier nanoClock, Runnable routeValidator) {
        this.routeValidator = routeValidator;
        List<Cidr> trusted = new ArrayList<>();
        for (String cidr : settings.trustedProxies()) {
            trusted.add(Cidr.parse(cidr));
        }
        this.addresses = new ClientAddressResolver(trusted, settings.forwardedHeader(), peerAddress);
        Map<String, TokenBucketLimiter> byRoute = new HashMap<>();
        for (RouteGroup group : settings.groups()) {
            TokenBucketLimiter limiter = new TokenBucketLimiter(group.capacity(), group.refillPeriod().toNanos(),
                    settings.maxKeysPerGroup(), nanoClock);
            for (String route : group.routes()) {
                byRoute.put(route, limiter);
            }
        }
        this.limiterByRoute = Map.copyOf(byRoute);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AdmissionPhase phase() {
        return AdmissionPhase.RATE_LIMITING;
    }

    @Override
    public boolean filter(HttpExchange exchange, RouteMatch routeMatch) {
        if (routeValidator != null) {
            routeValidator.run();
        }
        String route = routeMatch.route().method().name() + " " + routeMatch.route().pathPattern();
        TokenBucketLimiter limiter = limiterByRoute.get(route);
        if (limiter == null) {
            return true;
        }
        RateLimitDecision decision = limiter.tryAcquire(addresses.clientKey(exchange) + " " + route);
        if (decision.allowed()) {
            return true;
        }
        ProblemResponses.send(exchange, HttpStatus.TOO_MANY_REQUESTS, DETAIL,
                List.of(new HttpHeader("Retry-After", Long.toString(decision.retryAfterSeconds()))));
        return false;
    }
}
