package eu.exeris.caps.ratelimit;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;

import java.util.Set;

/**
 * Declares that production execution of {@code exeris-caps-rate-limiting} needs a commercial entitlement
 * (ADR-023 {@code commercial}, ADR-089).
 *
 * <p>Registered in {@code META-INF/services/eu.exeris.kernel.spi.contract.EntitlementRequirement}, so the
 * kernel's Phase 0 contract gate discovers it before any subsystem starts. In a production-class
 * environment the gate then refuses a boot with no license manifest ({@code EX-LIC-0005}), and with a
 * manifest it requires {@link #CAPABILITY_ID} among the entitled capabilities, so a manifest that entitles
 * other capabilities does not entitle this one.
 */
public final class RateLimitEntitlement implements EntitlementRequirement {

    /**
     * Identifier a license manifest lists in {@code entitlement.capabilities} to entitle this capability:
     * the capability's Maven artifactId, which is its identity in the ecosystem.
     */
    public static final String CAPABILITY_ID = "exeris-caps-rate-limiting";

    private static final Set<String> REQUIRED = Set.of(CAPABILITY_ID);

    /** ServiceLoader constructor. */
    public RateLimitEntitlement() {
        // stateless
    }

    @Override
    public Set<String> requiredCapabilities() {
        return REQUIRED;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@link EntitlementClass#COMMERCIAL} on every invocation.
     */
    @Override
    public EntitlementClass entitlementClass() {
        return EntitlementClass.COMMERCIAL;
    }
}
