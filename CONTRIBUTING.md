# Contributing to exeris-caps-rate-limiting

This repository is offered under the Exeris Commercial License 1.1 (`commercial` under ADR-023, source-available); the terms are in [`LICENSE`](LICENSE). It is a
proof of concept, so open an issue before starting a non-trivial change.

## Build & test

```bash
mvn compile -Dexeris.codegen.skip=true   # pass 1 — seed the capability metadata
mvn verify                               # pass 2 — generate, validate, scan the Wall
```

Requires JDK 25 and Maven 3.9+. `README.md` explains how the upstreams are obtained; CI builds JDK 25
and 26, and both rows must be green.

## What a change has to preserve

- **The Wall.** `eu.exeris.caps.ratelimit.internal` is private to this cap. The package root
  `eu.exeris.caps.ratelimit` must not be renamed or given an extra segment: the Wall guard derives the
  cap's identity from it (ADR-055). No Spring, servlet, Netty or reactive type anywhere (ADR-006).
- **The committed manifest.** `src/main/generated/java/cap-manifest.json` is tracked. A change to the
  module declaration commits the regenerated manifest in the same commit; a clean `git status` after
  `mvn verify` is the check.
- **The JDK baseline.** `maven.compiler.release` stays 25, and no preview flag appears anywhere.
- **Tests.** JUnit 5 and AssertJ only, no mocking framework: test doubles are written by hand. A bug fix
  lands with the test that fails without it.

## Conventions

- Code, identifiers, comments, commit messages, pull-request titles and bodies, and documentation are
  in English.
- Conventional-commit prefixes (`feat:`, `fix:`, `build:`, `ci:`, `docs:`, `chore:`).
- External contributions carry a `Signed-off-by:` trailer (`git commit -s`), a Developer Certificate
  of Origin sign-off. Contributor terms beyond the sign-off have not been published for this repository
  yet; ask in the issue before submitting a large change.
- AI-assisted work follows the organisation's
  [AI provenance standard](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md).

The documentation and commit standards live in
[`exeris-docs/standards/`](https://github.com/exeris-systems/exeris-docs/tree/main/standards).
Architecture decisions use the ecosystem-wide ADR registry in
[`exeris-docs/adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md);
reserve a number there before writing one.

## Conduct and security

This repository follows the
[Exeris Code of Conduct](https://github.com/exeris-systems/.github/blob/main/CODE_OF_CONDUCT.md).
Report vulnerabilities as described in [`SECURITY.md`](SECURITY.md), never in a public issue.
