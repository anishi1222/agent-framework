# Agent Framework for Java

This directory contains the Java 25 implementation of Microsoft Agent Framework. Framework behavior
is implemented incrementally; `agent-framework-core` now provides the immutable provider-neutral
model, strict streaming aggregation, run cancellation, and safe versioned JSON foundation. Other
runtime modules still establish approved dependency, quality, and publication boundaries until their
features are implemented.

## Prerequisites

- JDK 25 on `PATH` or available to Gradle toolchain discovery.
- No system Gradle installation is required.

## Bootstrap and validation

```bash
cd java
./gradlew --version
./gradlew -p build-logic build
./gradlew :agent-framework-conformance:test
./gradlew clean build
./gradlew checkArchitecture
./gradlew publishToTestRepository
```

The local publication smoke repository is written to `java/build/test-maven-repository`. To test
normal Maven-local publication instead, run `./gradlew publishToMavenLocal`.

Formatting changes can be applied with:

```bash
./gradlew spotlessApply
```

## Modules

The dependency direction is:

`orchestrations -> workflows -> agents -> tools -> core`

`agent-framework-observability` and `agent-framework-reactor-adapter` are optional outward adapters
that depend inward on the shared runtime. `agent-framework-bom` aligns all published module
versions and contains no runtime classes. `agent-framework-conformance` contains versioned,
language-neutral fixtures and test-support APIs; it is not published, is not in the BOM, and cannot
be a production dependency of published modules.

## Core API

`com.microsoft.agents.core` owns:

- `Role`, `FinishReason`, `Message`, and the sealed `Content` hierarchy;
- `ChatResponse` / `ChatResponseUpdate` and `AgentResponse<T>` / `AgentResponseUpdate`;
- immutable `RunOptions`, `ChatOptions`, and arbitrary-precision `UsageDetails`;
- `ResponseAggregator`, `RunCancellation`, `RunHandle<T>`, and `RunHandleSource<T>`; and
- `StateValue`, versioned envelopes, explicit `StateCodec<T>` registration, and
  `JsonStateSerializer`.

Jackson is an implementation dependency of core serialization and does not appear in public model
or SPI signatures. Session and workflow snapshot schemas remain owned by their later runtime modules.
