# exeris-caps-rate-limiting

Per-key request rate limiting as an Exeris capability. It provides `RateLimitPolicy`, a decision function that answers whether a request identified by a key (a `CharSequence` or a precomputed `long` hash) may proceed at a given cost, using the Generic Cell Rate Algorithm over a fixed-capacity, in-process table. It also provides the Service Boundary admission filter that meters credential routes — login, register, password reset, email verification, token refresh — per client and route. `RateLimitPolicy` is transport-free; only the Service Boundary admission filter (ADR-099 obligation 4) touches HTTP, building the 429 response through the kernel HTTP SPI types.

Status: proof of concept, built against `exeris-kernel` 0.13.0-SNAPSHOT. The API is not stable and no artefact has been published.

## Where it sits

- **Layer:** Layer 3 — Gateway policy, in the Tier 2 capability map of [`high-level-architecture.md`](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) §3.2.
- **Depends on:** `exeris-kernel-spi`, `exeris-sdk-composition-lifecycle` and `exeris-caps-service-boundary-core`, all at `provided` scope, so the jar carries no runtime dependency.
- **Provides** (`@Provides`, version 0.1.0): `RateLimitPolicy`.
- **Requires** (`@Requires`, optional): `RequestFilterChain` `[0.1.0,0.2.0)` from `exeris-caps-service-boundary-core`. ADR-099 obligation 4 also names the gateway's `policy-chain` as an optional host; no published capability provides it yet, so that binding does not exist.
- **Wall (ADR-055):** `eu.exeris.caps.ratelimit.api` is the cross-cap surface; `eu.exeris.caps.ratelimit.internal` is private, and a sibling cap reaching into it fails the build. No Spring, servlet, Netty or reactive type may appear (ADR-006).

Behaviour worth knowing before composing it:

- `RateLimitPolicies.perSecond(...)`, `perMinute(...)` and `builder()` (rate, period, burst, table capacity) construct a policy.
- `RateLimitDecision` carries allowed/rejected, the remaining budget, the limit and the time until reset or retry.
- State lives in memory in one process; it is not shared between nodes and does not survive a restart.

## Service Boundary filter

`RateLimitServiceBoundaryBinding.of(chain, settings, peerAddress)` builds the binding, and `new RateLimitModule(binding)` attaches it at `initialize()`; a module with no binding refuses to initialise. The filter runs in the `RATE_LIMITING` phase, before authentication, so a throttled request costs no signature or password work.

- **Buckets.** Each configured route group has a capacity (burst) and a refill period (time to regain one request). A bucket is keyed by the client address **and** the matched route, so routes are isolated from each other, also within one group. Keys are compared exactly, never by hash. When a group's bucket table is full, fully refilled buckets are dropped; if none can be, a new client is refused rather than admitted unmetered.
- **No body-derived key.** An account name or email address from the request body or query never enters the key: the caller chooses those, so keying on them would let a client spread its attempts over invented identifiers or exhaust someone else's budget. The limit is per address and route.
- **Client address.** The starting point is the connection's peer address, which the composition supplies through `PeerAddressSource` because the kernel HTTP SPI does not expose it. Exactly one forwarding header, chosen by `exeris.caps.ratelimit.forwarded-header` (`x-forwarded-for`, the default, or `forwarded` for RFC 7239), is read, and only when that peer lies in a configured trusted-proxy CIDR; the other header is never consulted, not even as a fallback, because a proxy writes one and passes the other through from the client. By default no proxy is trusted and no header is read. From a trusted proxy the hops are walked from the right, and the first untrusted hop is the client. IPv6 clients are keyed by their /64. Without a peer address every request shares one bucket per route.
- **Rejection.** `429 Too Many Requests` with `Retry-After` in seconds and a fixed `application/problem+json` body.

## Configuration

`RateLimitFilterSettings.fromProperties(properties)` reads:

| Key | Default | Meaning |
|---|---|---|
| `exeris.caps.ratelimit.groups` | none | comma-separated group names, e.g. `login,register,password-reset,verify,token-refresh` |
| `exeris.caps.ratelimit.group.<name>.routes` | required per group | comma-separated `METHOD /path/{template}` routes; a route belongs to one group only |
| `exeris.caps.ratelimit.group.<name>.capacity` | `5` | burst per client and route |
| `exeris.caps.ratelimit.group.<name>.refill-seconds` | `60` | seconds to regain one request |
| `exeris.caps.ratelimit.trusted-proxies` | none | comma-separated CIDRs whose forwarding header is honoured |
| `exeris.caps.ratelimit.forwarded-header` | `x-forwarded-for` | the one header trusted proxies write the client address into: `x-forwarded-for` or `forwarded` |
| `exeris.caps.ratelimit.max-keys` | `100000` | bound on live buckets per group |

## Build & test

Requires JDK 25 and Maven 3.9+.

```bash
mvn compile -Dexeris.codegen.skip=true   # pass 1 — the processor writes capability metadata
mvn verify                               # pass 2 — generate cap-manifest.json, validate the graph, scan the Wall
```

Both passes are needed after a `clean`: `exeris:generate` runs before `compile`, and `compile` is when the processor writes the metadata it reads. `src/main/generated/java/cap-manifest.json` is committed, so a clean `git status` after `mvn verify` shows the manifest matches the declaration.

The upstreams are not on Maven Central. `exeris-sdk` v0.10.0 and `exeris-tooling` v0.7.0 are built from source at those tags, `exeris-caps-service-boundary-core` is built from its `main` branch, and `exeris-kernel` 0.13.0-SNAPSHOT is resolved from GitHub Packages, which needs a token with `read:packages`. [`.github/workflows/build.yml`](.github/workflows/build.yml) is the exact sequence, and [`.github/maven-settings.xml`](.github/maven-settings.xml) the repository configuration it uses.

## Documentation

- [`AGENTS.md`](AGENTS.md) — the repository's operating contract and invariants.
- [Capability Author Guide](https://github.com/exeris-systems/exeris-docs/blob/main/cap-author-guide.md) — how a cap declares `@CapabilityModule`, `@Provides`, `@Requires` and `@CapabilityLifecycle`.
- [Capability licence registry](https://github.com/exeris-systems/exeris-docs/blob/main/cap-license-registry.md) — the licence value of every cap.

Where they disagree, the ADRs below and the HLA outrank this README.

## Governing ADRs

- [ADR-006](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-006-spring-free-kernel-boundary.md) — Spring-free kernel boundary.
- [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md) — capability licensing taxonomy.
- [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) — capability composition model.
- [ADR-053](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-053-sku-composition-manifest-format.md) — composition manifest format.
- [ADR-055](https://github.com/exeris-systems/exeris-tooling/blob/main/docs/adr/ADR-055-cap-tier-wall-guard.md) — cap-tier Wall guard.
- [ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md) — signed licence manifest.
- [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md) — capability entitlement enforcement.
- [ADR-099](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-099-compose-identity-as-a-tier-3-platform-sku.md) — the Identity SKU, which lists this cap.

Registry: [`adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md).

## License

Exeris Commercial License 1.1 — see [`LICENSE`](LICENSE). `exeris-caps-rate-limiting` is a `commercial` capability under [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md): the source is available to read, build, test and evaluate, and production use requires a commercial licence.

The cap enforces that at boot. It registers a kernel `EntitlementRequirement` whose capability id is `exeris-caps-rate-limiting`, and in a production-class environment the kernel's contract gate refuses to start unless a signed licence manifest lists that id among its entitlements ([ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md), [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md)).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Report a suspected vulnerability as described in [`SECURITY.md`](SECURITY.md), never in a public issue.
