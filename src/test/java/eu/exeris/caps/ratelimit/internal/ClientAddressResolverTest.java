package eu.exeris.caps.ratelimit.internal;

import eu.exeris.caps.ratelimit.api.ForwardedHeader;
import eu.exeris.caps.ratelimit.api.PeerAddressSource;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ClientAddressResolver")
class ClientAddressResolverTest {

    private static String client(List<String> trusted, String peer, HttpHeader... headers) {
        return client(ForwardedHeader.X_FORWARDED_FOR, trusted, peer, headers);
    }

    private static String client(ForwardedHeader mode, List<String> trusted, String peer, HttpHeader... headers) {
        ClientAddressResolver resolver = new ClientAddressResolver(
                trusted.stream().map(Cidr::parse).toList(), mode,
                exchange -> Optional.ofNullable(peer));
        return resolver.clientKey(new RecordingExchange(HttpMethod.POST, "/login", Arrays.asList(headers)));
    }

    private static HttpHeader xff(String value) {
        return new HttpHeader("X-Forwarded-For", value);
    }

    private static HttpHeader forwarded(String value) {
        return new HttpHeader("Forwarded", value);
    }

    @Test
    @DisplayName("by default no proxy is trusted, so a spoofed X-Forwarded-For is ignored")
    void defaultTrustsNone() {
        assertThat(client(List.of(), "203.0.113.7", xff("198.51.100.1"))).isEqualTo("203.0.113.7");
        assertThat(client(ForwardedHeader.FORWARDED, List.of(), "203.0.113.7", forwarded("for=198.51.100.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("headers from an untrusted peer are ignored even when proxies are configured")
    void untrustedPeerIgnored() {
        assertThat(client(List.of("10.0.0.0/8"), "203.0.113.7", xff("198.51.100.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("from a trusted proxy the rightmost untrusted hop is the client; hops left of it are ignored")
    void rightmostUntrusted() {
        assertThat(client(List.of("10.0.0.0/8"), "10.0.0.2", xff("1.1.1.1, 198.51.100.9, 10.0.0.5")))
                .isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("several header lines form one list, in order")
    void multipleHeaderLines() {
        assertThat(client(List.of("10.0.0.0/8"), "10.0.0.2", xff("1.1.1.1"), xff("198.51.100.9")))
                .isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("when every hop is trusted the leftmost is the client")
    void allTrusted() {
        assertThat(client(List.of("10.0.0.0/8"), "10.0.0.2", xff("10.1.1.1, 10.0.0.5"))).isEqualTo("10.1.1.1");
    }

    @Test
    @DisplayName("a hop that is not an address ends the walk at the last trusted proxy")
    void garbageHop() {
        assertThat(client(List.of("10.0.0.0/8"), "10.0.0.2", xff("198.51.100.9, evil.example, 10.0.0.5")))
                .isEqualTo("10.0.0.5");
        assertThat(client(ForwardedHeader.FORWARDED, List.of("10.0.0.0/8"), "10.0.0.2", forwarded("for=unknown"))).isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("forwarded mode reads Forwarded, with quoted IPv6 and ports, and ignores X-Forwarded-For")
    void forwardedHeader() {
        assertThat(client(ForwardedHeader.FORWARDED, List.of("10.0.0.0/8"), "10.0.0.2",
                forwarded("for=192.0.2.60;proto=https, for=\"192.0.2.43:47011\""), xff("198.51.100.1")))
                .isEqualTo("192.0.2.43");
        assertThat(client(ForwardedHeader.FORWARDED, List.of("10.0.0.0/8"), "10.0.0.2",
                forwarded("For=\"[2001:db8:cafe::17]:4711\"")))
                .isEqualTo("v6:20010db8cafe0000/64");
    }

    @Test
    @DisplayName("x-forwarded-for mode reads X-Forwarded-For and ignores Forwarded")
    void xffModeIgnoresForwarded() {
        assertThat(client(ForwardedHeader.X_FORWARDED_FOR, List.of("10.0.0.0/8"), "10.0.0.2",
                forwarded("for=192.0.2.60"), xff("198.51.100.1")))
                .isEqualTo("198.51.100.1");
    }

    @Test
    @DisplayName("a client-sent Forwarded cannot override the X-Forwarded-For the trusted proxy appended")
    void forwardedSpoofFails() {
        for (String random : List.of("192.0.2.1", "192.0.2.2", "192.0.2.3")) {
            assertThat(client(ForwardedHeader.X_FORWARDED_FOR, List.of("10.0.0.0/8"), "10.0.0.2",
                    forwarded("for=" + random), xff("198.51.100.9")))
                    .isEqualTo("198.51.100.9");
        }
    }

    @Test
    @DisplayName("a client-sent X-Forwarded-For cannot override the Forwarded the trusted proxy appended")
    void xffSpoofFailsInForwardedMode() {
        assertThat(client(ForwardedHeader.FORWARDED, List.of("10.0.0.0/8"), "10.0.0.2",
                xff("192.0.2.1"), forwarded("for=198.51.100.9")))
                .isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("the unselected header is never a fallback when the selected one is absent")
    void noFallback() {
        assertThat(client(ForwardedHeader.X_FORWARDED_FOR, List.of("10.0.0.0/8"), "10.0.0.2", forwarded("for=192.0.2.1")))
                .isEqualTo("10.0.0.2");
        assertThat(client(ForwardedHeader.FORWARDED, List.of("10.0.0.0/8"), "10.0.0.2", xff("192.0.2.1")))
                .isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("malformed values end the walk at the last trusted proxy")
    void malformedValues() {
        List<String> trusted = List.of("10.0.0.0/8");
        for (String bad : List.of("", "for=", "for=\"unterminated", "by=192.0.2.1", "for=[2001:db8::1", "for=1.2.3.4:port", "for=_hidden")) {
            assertThat(client(ForwardedHeader.FORWARDED, trusted, "10.0.0.2", forwarded(bad))).as(bad).isEqualTo("10.0.0.2");
        }
        for (String bad : List.of("", " , ", "999.1.1.1", "1.2.3.4:port", "[2001:db8::1", "unknown")) {
            assertThat(client(ForwardedHeader.X_FORWARDED_FOR, trusted, "10.0.0.2", xff(bad))).as(bad).isEqualTo("10.0.0.2");
        }
    }

    @Test
    @DisplayName("the forwarded-header setting parses either name, case-insensitively, and refuses others")
    void headerParsing() {
        assertThat(ForwardedHeader.parse("X-Forwarded-For")).isEqualTo(ForwardedHeader.X_FORWARDED_FOR);
        assertThat(ForwardedHeader.parse(" forwarded ")).isEqualTo(ForwardedHeader.FORWARDED);
        assertThatThrownBy(() -> ForwardedHeader.parse("x-real-ip")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IPv6 clients are keyed by /64 and IPv4-mapped addresses as IPv4")
    void addressKeys() {
        assertThat(client(List.of(), "2001:db8:1:2:aaaa::1")).isEqualTo(client(List.of(), "2001:db8:1:2:bbbb::9"));
        assertThat(client(List.of(), "2001:db8:1:2::1")).isNotEqualTo(client(List.of(), "2001:db8:1:3::1"));
        assertThat(client(List.of(), "::ffff:192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    @DisplayName("an IPv6 trusted network matches only IPv6 peers")
    void ipv6Cidr() {
        assertThat(client(List.of("2001:db8::/32"), "2001:db8::1", xff("198.51.100.4"))).isEqualTo("198.51.100.4");
        assertThat(client(List.of("2001:db8::/32"), "10.0.0.1", xff("198.51.100.4"))).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("without a peer address every request shares one key")
    void unknownPeer() {
        ClientAddressResolver resolver = new ClientAddressResolver(List.of(Cidr.parse("0.0.0.0/0")), ForwardedHeader.X_FORWARDED_FOR, PeerAddressSource.NONE);
        assertThat(resolver.clientKey(new RecordingExchange(HttpMethod.POST, "/login", List.of(xff("198.51.100.1")))))
                .isEqualTo(ClientAddressResolver.UNKNOWN);
    }

    @Test
    @DisplayName("CIDR parsing refuses names and bad prefixes")
    void cidrParsing() {
        assertThat(Cidr.parse("10.0.0.0/8").contains(Cidr.literal("10.255.0.1").orElseThrow())).isTrue();
        assertThat(Cidr.parse("10.0.0.0/8").contains(Cidr.literal("11.0.0.1").orElseThrow())).isFalse();
        assertThat(Cidr.parse("192.0.2.0/25").contains(Cidr.literal("192.0.2.200").orElseThrow())).isFalse();
        assertThat(Cidr.parse("192.0.2.9").contains(Cidr.literal("192.0.2.9").orElseThrow())).isTrue();
        assertThatThrownBy(() -> Cidr.parse("proxy.internal/8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }
}
