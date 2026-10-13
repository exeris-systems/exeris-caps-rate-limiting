package eu.exeris.caps.ratelimit.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitPolicies Factory and Builder Tests")
class RateLimitPoliciesTest {

    @Test
    @DisplayName("Builds perSecond rate limiter")
    void buildsPerSecondLimiter() {
        RateLimitPolicy policy = RateLimitPolicies.perSecond(100);
        assertThat(policy).isNotNull();

        RateLimitDecision d = policy.evaluate("192.168.1.1");
        assertThat(d.allowed()).isTrue();
        assertThat(d.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("Builds perMinute rate limiter")
    void buildsPerMinuteLimiter() {
        RateLimitPolicy policy = RateLimitPolicies.perMinute(60);
        assertThat(policy).isNotNull();

        RateLimitDecision d = policy.evaluate("tenant_alpha", 1);
        assertThat(d.allowed()).isTrue();
        assertThat(d.limit()).isEqualTo(60);
    }

    @Test
    @DisplayName("Custom builder configuration")
    void customBuilderConfig() {
        RateLimitPolicy policy = RateLimitPolicies.builder()
                .rate(50, Duration.ofSeconds(10))
                .burst(10)
                .capacity(128)
                .build();

        assertThat(policy).isNotNull();
        RateLimitDecision d = policy.evaluate("custom_client");
        assertThat(d.allowed()).isTrue();
        assertThat(d.limit()).isEqualTo(10);
    }
}
