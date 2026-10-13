# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

`exeris-caps-rate-limiting` is a single-module **capability** in the Exeris cap tier (Layer 3 — Gateway Policy,
ADR-024 / HLA §3.2). It publishes one service: `RateLimitPolicy`. It provides a pure decision function
and stateful lock-free rate tracking over client keys/hashes.

Two properties make it load-bearing:

1. **Lock-free, zero-allocation hot path**: Uses GCRA (Generic Cell Rate Algorithm) with a single 64-bit
   `long` state per slot and a pre-allocated primitive array table. Evaluation allocates `0 B` per request.
2. **The jar carries zero runtime dependencies**: Every Exeris annotation is `@Retention(SOURCE)`, so
   `exeris-sdk-annotations` stays at `provided` scope and `exeris-processor` stays inside
   `annotationProcessorPaths`. Nothing survives to a consumer.

## Build & test

```bash
mvn compile -Dexeris.codegen.skip=true   # pass 1 — seed: the processor writes capability metadata
mvn verify                               # pass 2 — generate, validate the graph, scan the Wall
```

## Hard constraints

- **The cap-tier Wall (ADR-055), build-enforced**: `..ratelimit.api` is the entire cross-cap surface;
  `..ratelimit.internal` is private. Sibling caps cannot access internal classes.
- **Zero runtime dependencies**: No external logging, no netty/servlet/spring imports.
- **JDK 25 LTS, no preview**: `maven.compiler.release` is 25 preview-clean.
- **Strictly 0 bytes allocated per token check**: No object allocations on hot path.
- **Test contract (ADR-058)**: JUnit 5 + AssertJ only, no mocking frameworks.
