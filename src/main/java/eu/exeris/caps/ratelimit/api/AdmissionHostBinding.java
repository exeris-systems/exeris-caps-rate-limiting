package eu.exeris.caps.ratelimit.api;

/**
 * Where the capability's admission limit is installed: the Service Boundary {@code RequestFilterChain},
 * or the gateway policy chain when that host exists (ADR-099 obligation 4).
 *
 * <p>The capability module attaches its binding when it initialises and detaches it when it terminates.
 * A module with no binding refuses to initialise, because a limit that runs nowhere admits
 * everything unmetered.
 *
 * @since 0.1.0
 */
public interface AdmissionHostBinding {

    /**
     * Names the host the limit is installed in.
     *
     * @return the host's name, for diagnostics
     */
    String host();

    /** Installs the limit in the host. */
    void attach();

    /**
     * Validates that the host is ready to serve requests under this binding.
     *
     * <p>Called during the {@code ready()} phase of the capability lifecycle, after all modules
     * have initialized and registered their routes, but before the first request is served.
     *
     * @throws IllegalStateException if configured limits cannot be verified against the host
     * @since 0.1.0
     */
    default void ready() {
    }

    /** Removes the limit from the host. */
    void detach();
}
