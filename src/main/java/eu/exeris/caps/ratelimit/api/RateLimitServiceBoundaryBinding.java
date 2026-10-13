package eu.exeris.caps.ratelimit.api;

import eu.exeris.caps.ratelimit.internal.RateLimitAdmissionFilter;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;

import java.util.Objects;
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
 * @since 0.1.0
 */
public final class RateLimitServiceBoundaryBinding implements AdmissionHostBinding {

    private final RequestFilterChain chain;
    private final RateLimitAdmissionFilter filter;

    private RateLimitServiceBoundaryBinding(RequestFilterChain chain, RateLimitAdmissionFilter filter) {
        this.chain = chain;
        this.filter = filter;
    }

    /**
     * @param chain the Service Boundary filter chain
     * @param settings route groups, limits and trusted proxies
     * @param peerAddress the connection's peer address, from the transport
     * @return the binding; attach it through the capability module
     */
    public static RateLimitServiceBoundaryBinding of(RequestFilterChain chain, RateLimitFilterSettings settings,
                                                     PeerAddressSource peerAddress) {
        return of(chain, settings, peerAddress, System::nanoTime);
    }

    /**
     * As {@link #of(RequestFilterChain, RateLimitFilterSettings, PeerAddressSource)}, with the monotonic
     * clock the buckets refill by.
     */
    public static RateLimitServiceBoundaryBinding of(RequestFilterChain chain, RateLimitFilterSettings settings,
                                                     PeerAddressSource peerAddress, LongSupplier nanoClock) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(peerAddress, "peerAddress");
        return new RateLimitServiceBoundaryBinding(chain, new RateLimitAdmissionFilter(settings, peerAddress, nanoClock));
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
    public void detach() {
        chain.unregisterFilter(filter);
    }
}
