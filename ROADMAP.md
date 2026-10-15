---
title: "exeris-caps-rate-limiting — Roadmap"
type: roadmap
visibility: public
owning-repo: exeris-caps-rate-limiting
status: active
last-verified: 2026-10-15
---

# exeris-caps-rate-limiting — Roadmap

This repository is a proof of concept. It provides `RateLimitPolicy` and the Service Boundary admission filter that meters credential routes for the Identity SKU (ADR-099 obligation 4). This file records what the proof of concept does not cover, so that it can be read next to the code. A pull request that closes a gap updates the item in the same change.

Milestones are versions, not dates.

An item under **Before 0.1.0** has a tracking issue in the `0.1.0` milestone, so the milestone shows what blocks the first release. An item under **Later** has no issue until it is scheduled.

---

## Before 0.1.0

### A configured route that no registered route declares is never limited

Tracked in [#11](https://github.com/exeris-systems/exeris-caps-rate-limiting/issues/11). The filter builds its table from the configured `METHOD /path/{template}` strings and looks a request up by the matched route's method and path pattern; a route that is not in the table passes untouched. A typo in a group's `routes`, a renamed route or a template written differently from the registered pattern leaves a credential route without a limit, while the module initialises and reports a live limit. The binding receives only a `RequestFilterChain`, which does not list routes, so it cannot compare. Closing the gap means the binding takes the registered routes from `ApiSurfaceRegistry` and refuses to start, naming the route, when a configured route is not among them. Code: `src/main/java/eu/exeris/caps/ratelimit/internal/RateLimitAdmissionFilter.java`, `src/main/java/eu/exeris/caps/ratelimit/api/RateLimitServiceBoundaryBinding.java`.

---

## Later

### Bucket state is per process

Buckets live in memory in one process, are not shared between nodes and do not survive a restart. With *n* nodes behind a balancer a client's effective burst is up to *n* times the configured capacity, and a restart gives every client a full bucket. The limit is still correct for one node. Shared or persistent buckets need a store contract that the kernel or another capability would provide, and a rule for what happens when the store is unreachable; this capability waits on that contract. Code: `src/main/java/eu/exeris/caps/ratelimit/internal/TokenBucketLimiter.java`.

### No binding for the gateway policy chain

`AdmissionHostBinding` names two possible hosts, the Service Boundary `RequestFilterChain` and the gateway's `policy-chain`. Only the first has an implementation, because no published capability provides `PolicyChain`. A gateway composition cannot attach the limit until that capability publishes its contract and a second binding is written against it. Code: `src/main/java/eu/exeris/caps/ratelimit/api/AdmissionHostBinding.java`.

### The peer address is not reachable from the HTTP exchange

The client address starts from the connection's peer address, which the composition supplies through `PeerAddressSource`. The kernel `HttpExchange` does not expose the connection, so every composition has to wire the address from its transport; the transport SPI has a remote address on `TransportConnection`, but nothing connects it to the exchange. Without a source that returns an address, all callers share one bucket per route: the limit holds, but one client can exhaust it for everyone. This waits on a kernel change that exposes the peer address on the exchange. Code: `src/main/java/eu/exeris/caps/ratelimit/api/PeerAddressSource.java`, `src/main/java/eu/exeris/caps/ratelimit/internal/ClientAddressResolver.java`.

### The filter and the published policy use different engines

The filter meters with `TokenBucketLimiter`, which compares keys exactly. The published `RateLimitPolicy` is implemented by `DefaultRateLimitPolicy`, a GCRA over a fixed table whose keys are identified by a 64-bit hash, so two keys with equal hashes share one budget; a credential route metered through the policy could be exhausted by another caller. The two share no code and report different state. A consumer that needs exact keys must use the filter, and a consumer of the policy accepts hash identity. Unifying them, or documenting the policy as hash-keyed in its contract, is a design question for the contract's first stable version. Code: `src/main/java/eu/exeris/caps/ratelimit/internal/RateLimitAdmissionFilter.java`, `src/main/java/eu/exeris/caps/ratelimit/internal/DefaultRateLimitPolicy.java`.

### Policy rejections under contention carry a coarse retry time

When the compare-and-set budget is exhausted or the table has no slot for the key, `DefaultRateLimitPolicy` refuses with a retry-after of one cost interval, which can be shorter than the real wait. Separately, when two threads reclaim the same idle slot for one key, the loser can claim a second slot, so one request is counted against a duplicate fresh slot; the effect is bounded by the requests in flight at the reclaim. Re-reading the key after a failed value update closes it. Code: `src/main/java/eu/exeris/caps/ratelimit/internal/DefaultRateLimitPolicy.java`, `src/main/java/eu/exeris/caps/ratelimit/internal/LockFreePrimitiveTable.java`.

### A full bucket table refuses new clients

When a group's table holds `max-keys` buckets and none has refilled, a new client is refused instead of admitted without a limit. That keeps the protection when an attacker floods the table, but the flood also locks out legitimate new clients until buckets refill. The eviction sweep visits every bucket, so it costs O(`max-keys`) once per refill period. A cheaper structure for eviction, and a metric that signals a full table, would make this operable; neither exists. Code: `src/main/java/eu/exeris/caps/ratelimit/internal/TokenBucketLimiter.java`.

### No limit per account or identifier

The key is the client address and the route. A value from the request body or query never enters it, because the caller chooses it. The cost is that an attacker with many addresses can still try many passwords against one account within each address's budget. A per-account limit needs a server-verified identifier, for example one the Identity SKU resolves after the credential lookup, and a rule on how it combines with the address limit. This waits on that rule, which is a decision for the Identity SKU rather than for this capability.

### The Service Boundary dependency is pinned by commit

`exeris-caps-service-boundary-core` has no release, so the build checks it out at a pinned commit and a change on its main reaches this repository only when the commit is bumped in `.github/workflows/build.yml`. No artefact of this capability is published either. Both wait on the first release of `exeris-caps-service-boundary-core`.
