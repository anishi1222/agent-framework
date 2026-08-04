# Agent Framework for Java

This directory contains the Java 25 foundation for Microsoft Agent Framework. Framework behavior is
implemented incrementally; the current modules establish the approved build, dependency, quality,
and publication boundaries.

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
