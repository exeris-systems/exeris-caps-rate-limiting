package eu.exeris.caps.ratelimit.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Settings of the Service Boundary rate-limit filter.
 *
 * <p>A bucket is keyed by the client address and the route. Nothing from the request body or query,
 * such as an account name or email address, enters the key: those are chosen by the caller, so keying on
 * them would let a client spread its attempts over invented identifiers or exhaust a victim's budget.
 *
 * @param groups the limited route groups; a route may belong to one group only
 * @param trustedProxies CIDRs whose {@code Forwarded} / {@code X-Forwarded-For} headers are honoured; empty
 *                       (the default) honours neither header
 * @param maxKeysPerGroup bound on live buckets per group
 *
 * @since 0.1.0
 */
public record RateLimitFilterSettings(List<RouteGroup> groups, List<String> trustedProxies, int maxKeysPerGroup) {

    /** Comma-separated group names. */
    public static final String KEY_GROUPS = "exeris.caps.ratelimit.groups";
    /** Prefix of a group's keys: {@code <prefix><name>.routes}, {@code .capacity}, {@code .refill-seconds}. */
    public static final String KEY_GROUP_PREFIX = "exeris.caps.ratelimit.group.";
    /** Comma-separated trusted-proxy CIDRs; default none. */
    public static final String KEY_TRUSTED_PROXIES = "exeris.caps.ratelimit.trusted-proxies";
    /** Bound on live buckets per group; default 100000. */
    public static final String KEY_MAX_KEYS = "exeris.caps.ratelimit.max-keys";

    /** Default burst of a group. */
    public static final long DEFAULT_CAPACITY = 5;
    /** Default seconds in which a client regains one request. */
    public static final long DEFAULT_REFILL_SECONDS = 60;
    /** Default bound on live buckets per group. */
    public static final int DEFAULT_MAX_KEYS = 100_000;

    public RateLimitFilterSettings {
        groups = List.copyOf(groups);
        trustedProxies = List.copyOf(trustedProxies);
        if (maxKeysPerGroup <= 0) {
            throw new IllegalArgumentException("maxKeysPerGroup must be positive");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (RouteGroup group : groups) {
            for (String route : group.routes()) {
                if (!seen.add(route)) {
                    throw new IllegalArgumentException("Route " + route + " is in more than one rate-limit group");
                }
            }
        }
    }

    /**
     * Reads the settings from configuration. Example:
     * {@snippet lang=properties :
     * exeris.caps.ratelimit.groups=login,password-reset
     * exeris.caps.ratelimit.group.login.routes=POST /login
     * exeris.caps.ratelimit.group.login.capacity=5
     * exeris.caps.ratelimit.group.login.refill-seconds=60
     * exeris.caps.ratelimit.group.password-reset.routes=POST /password/reset-request, POST /password/reset
     * exeris.caps.ratelimit.trusted-proxies=10.0.0.0/8
     * }
     *
     * @param properties configuration
     * @return the settings
     * @throws IllegalStateException if a value cannot be read
     */
    public static RateLimitFilterSettings fromProperties(Map<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        List<RouteGroup> groups = new ArrayList<>();
        for (String name : list(properties.get(KEY_GROUPS))) {
            String prefix = KEY_GROUP_PREFIX + name + ".";
            Set<String> routes = new LinkedHashSet<>();
            for (String route : list(properties.get(prefix + "routes"))) {
                routes.add(route.replaceAll("\\s+", " "));
            }
            if (routes.isEmpty()) {
                throw new IllegalStateException(prefix + "routes names no route");
            }
            long capacity = number(properties, prefix + "capacity", DEFAULT_CAPACITY);
            long refill = number(properties, prefix + "refill-seconds", DEFAULT_REFILL_SECONDS);
            try {
                groups.add(new RouteGroup(name, routes, capacity, Duration.ofSeconds(refill)));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        int maxKeys = Math.toIntExact(number(properties, KEY_MAX_KEYS, DEFAULT_MAX_KEYS));
        return new RateLimitFilterSettings(groups, list(properties.get(KEY_TRUSTED_PROXIES)), maxKeys);
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String item : value.split(",")) {
                if (!item.isBlank()) {
                    out.add(item.strip());
                }
            }
        }
        return out;
    }

    private static long number(Map<String, String> properties, String key, long fallback) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " is not an integer", e);
        }
    }
}
