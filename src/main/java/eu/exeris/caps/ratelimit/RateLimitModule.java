package eu.exeris.caps.ratelimit;

import eu.exeris.caps.ratelimit.api.AdmissionHostBinding;
import eu.exeris.caps.ratelimit.api.RateLimitPolicy;
import eu.exeris.caps.serviceboundarycore.api.RequestFilterChain;
import eu.exeris.sdk.annotation.capability.CapabilityLifecycle;
import eu.exeris.sdk.annotation.capability.CapabilityModule;
import eu.exeris.sdk.annotation.capability.Provides;
import eu.exeris.sdk.annotation.capability.Requires;
import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;

import java.time.Duration;

/**
 * The capability declaration and lifecycle owner for {@code exeris-caps-rate-limiting}.
 *
 * <p>The limit runs in an admission host (ADR-099 obligation 4): {@code service-boundary-core}'s
 * {@link RequestFilterChain}, bound through {@code RateLimitServiceBoundaryBinding}, or the gateway policy
 * chain. Both are optional edges; with no host bound the module refuses to initialise, since a limit that
 * runs nowhere admits every request unmetered.
 *
 * <p>The module's own signatures name no type of an optional capability, so it loads whichever host is
 * absent.
 *
 * @since 0.1.0
 */
@CapabilityModule
@CapabilityLifecycle
@Provides(service = RateLimitPolicy.class, version = "0.1.0")
@Requires(service = RequestFilterChain.class, versionRange = "[0.1.0,0.2.0)", optional = true)
public final class RateLimitModule implements CapabilityLifecycleHooks {

    private final AdmissionHostBinding binding;

    /**
     * Reflective constructor of the composition runtime. It binds no host, so {@link #initialize()} refuses;
     * a composition supplies the binding through {@link #RateLimitModule(AdmissionHostBinding)}.
     */
    public RateLimitModule() {
        this(null);
    }

    /**
     * @param binding where the limit is installed; null leaves the module unable to initialise
     */
    public RateLimitModule(AdmissionHostBinding binding) {
        this.binding = binding;
    }

    @Override
    public void initialize() {
        if (binding == null) {
            throw new IllegalStateException(
                    "exeris-caps-rate-limiting has no admission host: bind it into service-boundary-core's "
                            + "RequestFilterChain (RateLimitServiceBoundaryBinding) or into the gateway policy chain");
        }
        binding.attach();
    }

    @Override
    public void ready() {
        // The limit is live from initialize(); nothing further to do.
    }

    @Override
    public void drain(Duration remaining) {
        // Requests still in flight are metered until terminate() detaches the limit.
    }

    @Override
    public void terminate() {
        if (binding != null) {
            binding.detach();
        }
    }
}
