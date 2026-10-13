package eu.exeris.caps.ratelimit.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Routes that share one limit, e.g. login, register, password reset, email verification or token refresh.
 *
 * <p>Each route of the group still has its own buckets: the bucket key is the client address and the
 * route, so exhausting the login limit leaves the reset limit untouched.
 *
 * @param name group name, for configuration and diagnostics
 * @param routes routes as {@code "METHOD /path/{template}"}, matched against the route's declared pattern
 * @param capacity burst: requests a client may make at once
 * @param refillPeriod time in which a client regains one request
 *
 * @since 0.1.0
 */
public record RouteGroup(String name, Set<String> routes, long capacity, Duration refillPeriod) {

    public RouteGroup {
        Objects.requireNonNull(name, "name");
        routes = Set.copyOf(routes);
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (capacity <= 0 || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Route group " + name + " needs a positive capacity and refill period");
        }
        for (String route : routes) {
            if (route.indexOf(' ') <= 0) {
                throw new IllegalArgumentException("Route group " + name + " entry is not \"METHOD /path\": " + route);
            }
        }
    }
}
