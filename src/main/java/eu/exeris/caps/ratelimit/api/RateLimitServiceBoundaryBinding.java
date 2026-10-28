package eu.exeris.caps.ratelimit.api;

import eu.exeris.caps.ratelimit.internal.RateLimitAdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Binds the rate-limit filter into {@code exeris-caps-service-boundary-core}'s {@link RequestFilterChain},
 * in the {@code RATE_LIMITING} phase.
 *
 * <p>This type and the internal admission filter are the only types that load Service Boundary classes, so
 * a composition without {@code service-boundary-core} never loads them. {@code RateLimitModule} names
 * {@link RequestFilterChain} only in its source-retention {@code @Requires} and in Javadoc, so it leaves no
 * reference in the class file.
 *
 * <p>Configured routes are verified against the registered routes in {@link #ready()}, before the first
 * request is served.
 *
 * @since 0.1.0
 */
public final class RateLimitServiceBoundaryBinding implements AdmissionHostBinding {

    private final RequestFilterChain chain;
    private final RateLimitAdmissionFilter filter;
    private final Runnable routeValidator;

    private RateLimitServiceBoundaryBinding(RequestFilterChain chain, RateLimitAdmissionFilter filter,
                                            Runnable routeValidator) {
        this.chain = chain;
        this.filter = filter;
        this.routeValidator = routeValidator;
    }

    /**
     * Creates the binding from an {@link ApiSurfaceRegistry}, measuring time with {@link System#nanoTime()}.
     *
     * <p>Configured routes are validated against the registry's declared routes when {@link #ready()} runs.
     *
     * @param registry the Service Boundary API surface registry providing the filter chain and declared routes
     * @param settings route groups, limits and trusted proxies
     * @param peerAddress the connection's peer address, from the transport
     * @return the binding; attach it through the capability module
     */
    public static RateLimitServiceBoundaryBinding of(ApiSurfaceRegistry registry, RateLimitFilterSettings settings,
                                                     PeerAddressSource peerAddress) {
        return of(registry, settings, peerAddress, System::nanoTime);
    }

    /**
     * As {@link #of(ApiSurfaceRegistry, RateLimitFilterSettings, PeerAddressSource)}, with the monotonic
     * clock the buckets refill by.
     *
     * @param registry the Service Boundary API surface registry providing the filter chain and declared routes
     * @param settings route groups, limits and trusted proxies
     * @param peerAddress the connection's peer address, from the transport
     * @param nanoClock the monotonic clock the buckets refill by
     * @return the binding; attach it through the capability module
     */
    public static RateLimitServiceBoundaryBinding of(ApiSurfaceRegistry registry, RateLimitFilterSettings settings,
                                                     PeerAddressSource peerAddress, LongSupplier nanoClock) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(peerAddress, "peerAddress");
        Objects.requireNonNull(nanoClock, "nanoClock");
        Runnable validator = createRouteValidator(registry, settings);
        RateLimitAdmissionFilter filter = new RateLimitAdmissionFilter(settings, peerAddress, nanoClock, validator);
        return new RateLimitServiceBoundaryBinding(registry.filterChain(), filter, validator);
    }

    private static Runnable createRouteValidator(ApiSurfaceRegistry registry,
                                                 RateLimitFilterSettings settings) {
        return new Runnable() {
            private volatile boolean validated;

            @Override
            public void run() {
                if (validated) {
                    return;
                }
                List<ApiSurfaceRoute> routes = registry.routes();
                Set<String> declared = new HashSet<>();
                if (routes != null) {
                    for (ApiSurfaceRoute route : routes) {
                        declared.add(route.method().name() + " " + route.pathPattern());
                    }
                }
                List<String> missing = new ArrayList<>();
                for (RouteGroup group : settings.groups()) {
                    for (String configuredRoute : group.routes()) {
                        if (!declared.contains(configuredRoute)) {
                            missing.add(configuredRoute);
                        }
                    }
                }
                if (!missing.isEmpty()) {
                    if (missing.size() == 1) {
                        throw new IllegalStateException("Configured rate-limit route '" + missing.getFirst()
                                + "' is not declared by any registered route");
                    }
                    throw new IllegalStateException("Configured rate-limit routes not declared by any registered route: "
                            + String.join(", ", missing));
                }
                validated = true;
            }
        };
    }

    @Override
    public String host() {
        return "service-boundary-core RequestFilterChain";
    }

    @Override
    public void attach() {
        chain.registerFilter(filter);
    }

    @Override
    public void ready() {
        if (routeValidator != null) {
            routeValidator.run();
        }
    }

    @Override
    public void detach() {
        chain.unregisterFilter(filter);
    }
}
