---
status: proposed
contact: anishi1222
date: 2026-08-04
deciders: anishi1222
consulted:
informed:
---

# Java modules, dependencies, and distribution

## Context and Problem Statement

The Java implementation will contain a provider-neutral runtime, workflows, optional ecosystem integrations, and many
provider and protocol adapters. Its build and publication topology must prevent dependencies from flowing back into the
core, isolate unstable SDKs, and let consumers select only the integrations they use. The project also needs a
consistent release and Maven Central consumption model.

## Decision Drivers

- Enforce dependency direction mechanically.
- Keep application frameworks, reactive libraries, and provider SDKs out of core.
- Allow providers and protocols to evolve without leaking their types across shared APIs.
- Centralize Java 25, dependency, quality, and publication configuration.
- Publish coherent, signed artifacts that consumers can align with a BOM.
- Keep optional integrations independently selectable.

## Considered Options

- Gradle Kotlin DSL multi-module build with isolated adapters and one release train
- A single Java artifact
- Maven multi-module build
- Provider SDKs and ecosystem frameworks in core
- Independently version every module

## Decision Outcome

Chosen option: **Gradle Kotlin DSL multi-module build with isolated adapters and one release train**.

The Java build uses a Gradle wrapper and Kotlin DSL. Root convention plugins, an included `build-logic` build, and a
version catalog centralize the Java 25 toolchain, dependency versions, formatting, analysis, testing, signing, and
publication rules. Individual modules do not redefine those policies.

The initial module families and published artifact IDs are:

| Layer | Gradle project / Maven artifact | Public package root | Responsibility |
|---|---|---|---|
| Foundation | `agent-framework-core` | `com.microsoft.agents.core` | Provider-neutral immutable models and options, concurrency/run-control primitives, and serialization SPI |
| Runtime | `agent-framework-tools` | `com.microsoft.agents.tools` | Tool metadata, argument binding, approval/resume, invocation ledger, and function-call loop |
| Runtime | `agent-framework-agents` | `com.microsoft.agents.agents` | `ChatClient` SPI/API, `Agent`/`BaseAgent`, execution, sessions and stores, middleware, context, and history |
| Workflow | `agent-framework-workflows` | `com.microsoft.agents.workflows` | Workflow graph, execution, state, events, checkpoint storage, and checkpoint/resume |
| Workflow | `agent-framework-orchestrations` | `com.microsoft.agents.orchestrations` | Sequential, concurrent, handoff, group chat, and Magentic patterns |
| Integration | `agent-framework-<provider>` | `com.microsoft.agents.providers.<provider>` | One provider SDK adapter per artifact |
| Integration | `agent-framework-<protocol>` | `com.microsoft.agents.protocols.<protocol>` | MCP, A2A, AG-UI, and other protocol adapters |
| Integration | `agent-framework-hosting` | `com.microsoft.agents.hosting` | Provider-neutral hosting support |
| Integration | `agent-framework-<storage-or-memory>` | `com.microsoft.agents.storage.<adapter>` | Storage, search, and memory adapters |
| Cross-cutting | `agent-framework-observability` | `com.microsoft.agents.observability` | Optional OpenTelemetry instrumentation |
| Ecosystem | `agent-framework-spring-ai-adapter` | `com.microsoft.agents.adapters.springai` | Optional Spring AI conversion and integration |
| Ecosystem | `agent-framework-langchain4j-adapter` | `com.microsoft.agents.adapters.langchain4j` | Optional LangChain4j conversion and integration |
| Ecosystem | `agent-framework-reactor-adapter` | `com.microsoft.agents.adapters.reactor` | Optional Reactor conversion |
| Distribution | `agent-framework-bom` | none | Version constraints for all published Java artifacts |

Samples and conformance fixtures are Gradle modules but are not published.

Dependency arrows below mean "the item on the left may depend on the item on the right":

`workflows -> agents -> tools -> core`

`orchestrations -> agents -> tools -> core`

Additional rules are:

- provider modules may depend on `core`, `tools`, and `agents` as required, but shared runtime modules never depend on a
  provider module;
- protocol, hosting, storage, memory, observability, and ecosystem adapters depend inward on the smallest required
  shared module; shared modules never depend on them;
- `workflows` does not depend on `orchestrations`, hosting, protocols, or providers;
- `core` has no dependency on Spring AI, LangChain4j, Reactor, OpenTelemetry, or a provider SDK;
- provider SDK types are confined to their adapter module and do not appear in public signatures of shared modules;
- conversion between provider and framework models happens at the adapter boundary; and
- adapters may expose a provider-specific advanced API in their own package, but provider-neutral interfaces return
  framework-owned types.

The first Java milestone includes `core`, `tools` (including tool approval and resume), `agents` (including session
storage), the OpenAI and Azure OpenAI/Foundry providers, and `workflows` (including checkpoint/resume).
Azure OpenAI and Microsoft Foundry are published as distinct `agent-framework-azure-openai` and
`agent-framework-foundry` artifacts because their endpoints, authentication, service versioning, and agent-reference
semantics are distinct even though both reuse the OpenAI Responses protocol mapping.
`orchestrations` remains a distinct module layered directly above `agents`; it does not depend on
`workflows` unless an orchestration implementation actually uses a workflow type. Protocol and hosting
modules are later work and are scheduled only after the workflow milestone passes; they cannot be used
to satisfy that milestone.

Spring AI, LangChain4j, and Reactor integrations are optional adapter artifacts, not core dependencies. OpenTelemetry
instrumentation is likewise optional and follows the wrapper/decorator and semantic-convention direction of
[ADR-0003](0003-agent-opentelemetry-instrumentation.md). Sensitive prompts, tool arguments, results, and credentials are
not recorded by default.

All published modules use one repository version and semantic versioning. Release builds publish source and Javadoc
JARs, signed POMs, and Gradle module metadata to Maven Central under `com.microsoft.agents`. Pre-release versions use a
qualifier on the shared version. `com.microsoft.agents:agent-framework-bom:<version>` is a Java Platform artifact that
constrains every published Agent Framework module to that version; it contains no runtime classes and does not force
optional adapters onto a consumer's classpath.

### Consequences

- Good, because dependency cycles and SDK leakage can be caught by the build.
- Good, because consumers install only the providers and ecosystem integrations they use.
- Good, because a shared version and BOM prevent unsupported combinations of framework modules.
- Good, because Gradle convention plugins keep module configuration consistent.
- Good, because provider SDK upgrades are localized to adapter modules.
- Neutral, because the repository contains more projects and publications than a monolith.
- Bad, because coordinated releases publish multiple artifacts even when only one module changes.
- Bad, because mapping code and adapter contract tests are required for each provider.
- Bad, because Maven Central signing and publication increase release-engineering work.

## Validation

- Gradle dependency checks must reject reverse dependencies and cycles.
- Package ownership checks must reject `ChatClient`, agents, sessions, middleware, context, or history in `core`; tool
  metadata or runtime in `core`; orchestration patterns in `workflows`; and provider/protocol packages outside
  `com.microsoft.agents.providers.<provider>` / `com.microsoft.agents.protocols.<protocol>`.
- An API-signature check must reject provider SDK, Spring AI, LangChain4j, Reactor, and OpenTelemetry types in shared
  modules.
- A consumer smoke test must import the BOM and use at least core plus one provider without specifying individual
  framework versions.
- Publication verification must inspect signed POM, source, Javadoc, and module metadata for every released artifact.
- OpenTelemetry tests must verify semantic conventions and that sensitive payloads are disabled by default.

## Pros and Cons of the Options

### Gradle Kotlin DSL multi-module build with isolated adapters and one release train

- Good, because module boundaries express the intended architecture.
- Good, because Kotlin DSL and convention plugins provide typed, centralized build configuration.
- Good, because one BOM and version make supported combinations unambiguous.
- Neutral, because changes to one module can trigger a coordinated release.
- Bad, because the build and release graph is more complex than a single artifact.

### A single Java artifact

- Good, because it is simple to build and consume.
- Neutral, because package boundaries can still organize source.
- Bad, because all provider SDKs and frameworks would share one classpath and compatibility surface.
- Bad, because optional integrations could not evolve or be selected independently.

### Maven multi-module build

- Good, because Maven Central publication and Java dependency management are familiar.
- Neutral, because Maven can enforce the same logical module boundaries.
- Bad, because it contradicts the approved Gradle Kotlin DSL direction.
- Bad, because shared configuration is more verbose than convention plugins for this module count.

### Provider SDKs and ecosystem frameworks in core

- Good, because common integrations need fewer adapter artifacts.
- Neutral, because their types could be convenient for users of that specific ecosystem.
- Bad, because unrelated consumers inherit dependencies and version conflicts.
- Bad, because external types constrain core API and release compatibility.

### Independently version every module

- Good, because a provider can release without changing unrelated module versions.
- Neutral, because a BOM can still describe a tested set.
- Bad, because compatibility testing and support matrices grow combinatorially.
- Bad, because consumers can easily select combinations that were never tested.

## More Information

- [Java module and package mapping](../java/api-mapping.md#2-module--package-name-mapping)
- [ADR-0003: Agent OpenTelemetry instrumentation](0003-agent-opentelemetry-instrumentation.md)
