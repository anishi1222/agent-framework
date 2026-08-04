# Java contributor instructions

These instructions apply to all files under `java/`.

## Required commands

Run commands from `java/`:

```bash
./gradlew -p build-logic build
./gradlew :agent-framework-conformance:test
./gradlew clean build
./gradlew check
./gradlew checkArchitecture
./gradlew spotlessApply
./gradlew publishToTestRepository
```

Use the committed wrapper; do not require a system Gradle installation. Java 25 is the minimum
compile and runtime version. Production and test compilation use `--release 25`.

## Project structure

- `build-logic/`: included build containing convention and architecture plugins.
- `agent-framework-core`: provider-neutral models, options, run control, and serialization SPI.
- `agent-framework-tools`: tool metadata and execution; depends on core.
- `agent-framework-agents`: agents, chat client, sessions, middleware, context, and history; depends
  on tools and core.
- `agent-framework-workflows`: workflow graph and checkpoint runtime; depends on agents.
- `agent-framework-orchestrations`: higher-level orchestration patterns; depends on workflows.
- `agent-framework-observability`: optional telemetry decorators; depends inward on agents.
- `agent-framework-reactor-adapter`: optional Reactor bridge; depends inward on agents.
- `agent-framework-conformance`: non-published, implementation-neutral fixtures and test support;
  production modules must not depend on it.
- `agent-framework-bom`: Java Platform constraints for the shared release version.

Never reverse the dependency direction. Provider, protocol, hosting, storage, observability, and
ecosystem adapters must depend on the smallest shared module they require; shared runtime modules
must never depend on adapters.

## Java and API conventions

- Public packages use `com.microsoft.agents.<capability>`.
- Types use `PascalCase`, methods and fields use `lowerCamelCase`, and constants use
  `UPPER_SNAKE_CASE`. Interfaces do not use an `I` prefix.
- Finite asynchronous methods returning `CompletionStage<T>` use an `Async` suffix.
- Streaming methods returning `Flow.Publisher<T>` use a `Streaming` suffix.
- Synchronous facades use an unsuffixed operation name and derive from the same execution core.
- Do not use preview APIs, add preview compiler/runtime flags, or reference `StructuredTaskScope`.
- Shared public APIs must not expose provider SDK, Spring AI, LangChain4j, Reactor, or
  OpenTelemetry types.
- Do not add `module-info.java` unless a later approved ADR requires JPMS.

## Tests and quality

- Put tests under `src/test/java` and suffix test classes with `Test`.
- Use JUnit 5, AssertJ, and Mockito. Keep tests independent and use Arrange/Act/Assert comments.
- Run `./gradlew check`; it includes compilation, tests, Checkstyle, Spotless, JaCoCo reports, the
  no-preview guard, dependency-direction checks, package ownership, and shared API isolation.
- Every `.java` production and test file starts with
  `// Copyright (c) Microsoft. All rights reserved.`
- Every public or protected API requires Javadoc. The first sentence must be a complete summary.
