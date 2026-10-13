package eu.exeris.caps.ratelimit.api;

import java.util.Locale;

/**
 * The one request header a trusted proxy writes the client address into.
 *
 * <p>Exactly one is read. A proxy typically appends to one header and passes the other through
 * untouched, so a header the proxy did not write is client-controlled and decides nothing.
 *
 * @since 0.1.0
 */
public enum ForwardedHeader {

    /** {@code X-Forwarded-For}, a comma-separated list of addresses. */
    X_FORWARDED_FOR("X-Forwarded-For"),
    /** {@code Forwarded} (RFC 7239), whose {@code for} parameter carries the address. */
    FORWARDED("Forwarded");

    private final String headerName;

    ForwardedHeader(String headerName) {
        this.headerName = headerName;
    }

    /** @return the HTTP header name */
    public String headerName() {
        return headerName;
    }

    /**
     * @param value a header name, case-insensitive: {@code x-forwarded-for} or {@code forwarded}
     * @return the matching constant
     * @throws IllegalArgumentException if the value names neither header
     */
    public static ForwardedHeader parse(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (ForwardedHeader header : values()) {
            if (header.headerName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return header;
            }
        }
        throw new IllegalArgumentException("forwarded-header must be x-forwarded-for or forwarded, not '" + value + "'");
    }
}
