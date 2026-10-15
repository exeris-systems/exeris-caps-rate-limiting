package eu.exeris.caps.ratelimit.api;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Routes that share one limit, e.g. login, register, password reset, email verification or token refresh.
 *
 * <p>Each route of the group still has its own buckets: the bucket key is the client address and the
 * route, so exhausting the login limit leaves the reset limit untouched.
 *
 * @param name group name, for configuration and diagnostics
 * @param routes routes as {@code "METHOD /path/{template}"}, matched against the route's declared pattern;
 *               the method is upper-cased and a trailing slash dropped, as the Service Boundary declares them
 * @param capacity burst: requests a client may make at once
 * @param refillPeriod time in which a client regains one request
 *
 * @since 0.1.0
 */
public record RouteGroup(String name, Set<String> routes, long capacity, Duration refillPeriod) {

    public RouteGroup {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (capacity <= 0 || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Route group " + name + " needs a positive capacity and refill period");
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String route : routes) {
            canonical.add(canonicalRoute(name, route));
        }
        routes = Set.copyOf(canonical);
    }

    /**
     * Writes a route the way the Service Boundary declares it, so the filter's exact-string match cannot
     * miss on spelling: the method upper-cased and the path without a trailing slash.
     */
    private static String canonicalRoute(String group, String route) {
        int space = route.indexOf(' ');
        if (space <= 0) {
            throw new IllegalArgumentException("Route group " + group + " entry is not \"METHOD /path\": " + route);
        }
        String path = route.substring(space + 1).strip();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return route.substring(0, space).toUpperCase(Locale.ROOT) + " " + path;
    }
}
