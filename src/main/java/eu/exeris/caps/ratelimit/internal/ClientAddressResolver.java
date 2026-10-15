package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.ForwardedHeader;
import eu.exeris.caps.ratelimit.api.PeerAddressSource;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Derives the client a rate-limit bucket belongs to.
 *
 * <p>The connection's peer address is the starting point. The configured {@link ForwardedHeader}, and only
 * that one, is read, and only when the peer lies in a configured trusted-proxy network; with no trusted
 * networks configured, the default, no header is read. The other forwarding header is never consulted,
 * not even when the configured one is absent: a proxy writes one of them, and the other passes through
 * from the client. The header's hops are walked from the right, skipping trusted proxies, and the first
 * untrusted hop is the client: every hop to its left was written by that client and proves nothing. A
 * hop that is not an address literal ends the walk at the last trusted proxy.
 *
 * <p>IPv6 clients are keyed by their /64 prefix, which is what one subscriber is normally given. Without a
 * peer address every request maps to one shared key, so the limit still applies, to all callers together.
 *
 * @since 0.1.0
 */
public final class ClientAddressResolver {

    /** Key used when the host supplies no peer address. */
    public static final String UNKNOWN = "unknown";

    private final List<Cidr> trustedProxies;
    private final ForwardedHeader forwardedHeader;
    private final PeerAddressSource peerAddress;

    public ClientAddressResolver(List<Cidr> trustedProxies, ForwardedHeader forwardedHeader, PeerAddressSource peerAddress) {
        this.trustedProxies = List.copyOf(trustedProxies);
        this.forwardedHeader = Objects.requireNonNull(forwardedHeader, "forwardedHeader");
        this.peerAddress = Objects.requireNonNull(peerAddress, "peerAddress");
    }

    /**
     * Derives the bucket key of the request's client, so clients behind one /64 share a bucket.
     *
     * @param exchange the request
     * @return the client key: an IPv4 address, an IPv6 /64 prefix, or {@link #UNKNOWN}
     */
    public String clientKey(HttpExchange exchange) {
        return resolve(exchange).map(ClientAddressResolver::key).orElse(UNKNOWN);
    }

    /**
     * Resolves the client address: the peer address, or, when the peer is a trusted proxy, the first
     * untrusted hop of the selected forwarding header read from the right.
     *
     * @param exchange the request
     * @return the client address, or empty when the host supplies no usable peer address
     */
    public Optional<InetAddress> resolve(HttpExchange exchange) {
        Optional<InetAddress> peer = peerAddress.peerAddress(exchange).flatMap(Cidr::literal);
        if (peer.isEmpty() || !trusted(peer.get())) {
            return peer;
        }
        List<String> hops = forwardedHops(exchange.request().headers(), forwardedHeader);
        InetAddress current = peer.get();
        for (int i = hops.size() - 1; i >= 0; i--) {
            Optional<InetAddress> hop = hopAddress(hops.get(i));
            if (hop.isEmpty()) {
                return Optional.of(current);
            }
            if (!trusted(hop.get())) {
                return hop;
            }
            current = hop.get();
        }
        return Optional.of(current);
    }

    private boolean trusted(InetAddress address) {
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** The hops of the selected header, left to right; the other forwarding header is not read. */
    static List<String> forwardedHops(List<HttpHeader> headers, ForwardedHeader selected) {
        List<String> hops = new ArrayList<>();
        for (HttpHeader header : headers) {
            if (!header.nameEqualsIgnoreCase(selected.headerName())) {
                continue;
            }
            for (String element : header.value().split(",")) {
                hops.add(selected == ForwardedHeader.FORWARDED ? forParameter(element) : element.strip());
            }
        }
        return hops;
    }

    private static String forParameter(String element) {
        for (String pair : element.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).strip().toLowerCase(Locale.ROOT).equals("for")) {
                String value = pair.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return "";
    }

    /** An address hop, with an optional port: {@code 192.0.2.1}, {@code 192.0.2.1:80}, {@code [2001:db8::1]:80}, {@code 2001:db8::1}. */
    static Optional<InetAddress> hopAddress(String hop) {
        String value = hop.strip();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            String rest = value.substring(close + 1);
            if (!rest.isEmpty() && !rest.matches(":[0-9]{1,5}")) {
                return Optional.empty();
            }
            return Cidr.literal(value.substring(1, close));
        }
        int colon = value.indexOf(':');
        if (colon >= 0 && colon == value.lastIndexOf(':')) {
            if (!value.substring(colon + 1).matches("[0-9]{1,5}")) {
                return Optional.empty();
            }
            value = value.substring(0, colon);
        }
        return Cidr.literal(value);
    }

    static String key(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] prefix = java.util.Arrays.copyOf(address.getAddress(), 8);
            return "v6:" + HexFormat.of().formatHex(prefix) + "/64";
        }
        return address.getHostAddress();
    }
}
