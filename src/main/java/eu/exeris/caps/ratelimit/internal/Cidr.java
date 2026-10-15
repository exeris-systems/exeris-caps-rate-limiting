package eu.exeris.caps.ratelimit.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;

/**
 * An IPv4 or IPv6 network in CIDR notation, matched without name resolution.
 *
 * @param network network address bytes
 * @param prefixLength prefix length in bits
 *
 * @since 0.1.0
 */
public record Cidr(byte[] network, int prefixLength) {

    public Cidr {
        Objects.requireNonNull(network, "network");
        network = network.clone();
    }

    /**
     * Parses {@code 10.0.0.0/8}, {@code 2001:db8::/32}, or a bare address (a single host).
     *
     * @param text CIDR text
     * @return the network
     * @throws IllegalArgumentException if the text is not an address literal with a valid prefix
     */
    public static Cidr parse(String text) {
        String value = text.strip();
        int slash = value.indexOf('/');
        InetAddress address = literal(slash < 0 ? value : value.substring(0, slash))
                .orElseThrow(() -> new IllegalArgumentException("Not an IP address literal: " + text));
        byte[] bytes = address.getAddress();
        int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1));
        if (prefix < 0 || prefix > bytes.length * 8) {
            throw new IllegalArgumentException("Invalid prefix length: " + text);
        }
        return new Cidr(bytes, prefix);
    }

    /**
     * Tests whether an address lies in this network; an address of the other IP family never does.
     *
     * @param address address to test
     * @return whether the address lies in this network; an IPv4 address never matches an IPv6 network
     */
    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int full = prefixLength / 8;
        for (int i = 0; i < full; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int rest = prefixLength % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (candidate[full] & mask) == (network[full] & mask);
    }

    /**
     * Parses an IP address literal, never resolving a name. An IPv4-mapped IPv6 address becomes IPv4.
     *
     * @param text candidate literal, an IPv6 one with or without brackets
     * @return the address, or empty when the text is not a literal
     */
    public static Optional<InetAddress> literal(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.strip();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.indexOf('%') >= 0) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.ofLiteral(value);
            if (address instanceof Inet6Address v6) {
                byte[] b = v6.getAddress();
                boolean mapped = true;
                for (int i = 0; i < 10; i++) {
                    mapped &= b[i] == 0;
                }
                if (mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                    return Optional.of(Inet4Address.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                }
            }
            return Optional.of(address);
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr that && prefixLength == that.prefixLength && java.util.Arrays.equals(network, that.network);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Arrays.hashCode(network) + prefixLength;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
        } catch (java.net.UnknownHostException e) {
            return "invalid/" + prefixLength;
        }
    }
}
