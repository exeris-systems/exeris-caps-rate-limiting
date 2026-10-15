package eu.exeris.caps.ratelimit.api;

import eu.exeris.kernel.spi.http.HttpExchange;

import java.util.Optional;

/**
 * Supplies the address of the connection's peer, the host that opened the TCP or QUIC connection.
 *
 * <p>The kernel's HTTP SPI does not expose it on {@link HttpExchange}, so the composition that owns the
 * transport provides it. It must come from the connection, never from a request header.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface PeerAddressSource {

    /** A source that knows no peer: every request then shares one bucket per route. */
    PeerAddressSource NONE = exchange -> Optional.empty();

    /**
     * Returns the IP address literal of the connection's peer, or empty when the transport does not
     * know it.
     *
     * @param exchange the request
     * @return the peer's IP address literal, or empty when it is not known
     */
    Optional<String> peerAddress(HttpExchange exchange);
}
