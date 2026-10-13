package eu.exeris.caps.ratelimit;

import eu.exeris.caps.ratelimit.api.RateLimitPolicy;
import eu.exeris.sdk.annotation.capability.CapabilityModule;
import eu.exeris.sdk.annotation.capability.Provides;

/**
 * The capability declaration for {@code exeris-caps-rate-limiting}.
 *
 * <p>Carries the {@code @CapabilityModule} and {@code @Provides} contract annotations
 * for the build-time codegen pipeline and composition conductor.
 *
 * @since 0.1.0
 */
@CapabilityModule
@Provides(service = RateLimitPolicy.class, version = "1.0.0")
public final class RateLimitModule {

    private RateLimitModule() {
        // Declaration carrier only — never constructed.
    }
}
