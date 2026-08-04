---
status: proposed
contact: anishi1222
date: 2026-08-04
deciders: anishi1222
consulted:
informed:
---

# Java platform and API conventions

## Context and Problem Statement

Agent Framework has .NET and Python implementations, but no Java implementation. The Java API needs a stable platform
baseline, an ownership model for its core types, and naming and namespace rules before modules and public types are
created. The design must remain recognizable to developers using the other implementations without importing another
Java agent framework's type system into the core.

## Decision Drivers

- Use one explicit minimum Java compile and runtime version.
- Avoid preview APIs whose signatures or availability can change between JDK releases.
- Give the framework control over compatibility, serialization, and provider-neutral behavior.
- Follow Java conventions while keeping concepts discoverable across .NET, Python, and Java.
- Reserve stable Maven coordinates and Java package namespaces before publication.

## Considered Options

- Java 25 with framework-owned types and Java-idiomatic names
- Java 21 with framework-owned types
- Java 25 with preview APIs
- Reuse Spring AI or LangChain4j types as the core model
- Mirror the .NET or Python API names exactly

## Decision Outcome

Chosen option: **Java 25 with framework-owned types and Java-idiomatic names**.

Java 25 is the minimum compile and runtime version. Production sources compile with `--release 25`, and neither
production nor test compilation enables `--enable-preview`. A dependency that requires preview APIs cannot be used by
a published Java module.

Agent Framework owns every public model and service interface, but ownership is split by capability rather than placing
all framework types in `core`. `core` contains only provider-neutral value models and options, serialization contracts,
and concurrency/run-control primitives. `tools` owns tool metadata and the tool runtime. `agents` owns the provider-
neutral `ChatClient` SPI/API together with agents, sessions, middleware, execution context, and history. `workflows`
owns the workflow graph and checkpoint runtime, while `orchestrations` owns the higher-level sequential, concurrent,
handoff, group-chat, and Magentic patterns. Public shared-module signatures must not expose Spring AI, LangChain4j,
Reactor, or provider SDK types.

The Maven group ID is `com.microsoft.agents`. Published artifact IDs use the `agent-framework-<capability>` form, such
as `agent-framework-core`, `agent-framework-workflows`, and `agent-framework-openai`. Public Java packages use the
`com.microsoft.agents` root and capability subpackages:

- `com.microsoft.agents.core`
- `com.microsoft.agents.tools`
- `com.microsoft.agents.agents`
- `com.microsoft.agents.workflows`
- `com.microsoft.agents.orchestrations`
- `com.microsoft.agents.providers.<provider>`
- `com.microsoft.agents.protocols.<protocol>`
- `com.microsoft.agents.hosting`
- `com.microsoft.agents.storage.<adapter>`
- `com.microsoft.agents.adapters.<ecosystem>`
- `com.microsoft.agents.observability`

The public naming rules are:

- classes, records, enums, annotations, and interfaces use `PascalCase`;
- interfaces do not use an `I` prefix;
- methods and fields use `lowerCamelCase`;
- constants use `UPPER_SNAKE_CASE`;
- methods returning `CompletionStage<T>` use an `Async` suffix;
- methods returning `Flow.Publisher<T>` use a `Streaming` suffix;
- synchronous facade methods use the unsuffixed operation name; and
- established initialisms remain uppercase in type names, such as `A2A`, `AGUI`, `HTTP`, and `MCP`.

`com.microsoft.agents.agents.Agent` is the public agent interface. `BaseAgent` is the abstract convenience base for
implementers that want shared lifecycle and middleware behavior; it implements `Agent` but is not the public contract
type. Finite asynchronous methods are named `runAsync`, streaming methods are named `runStreaming`, and the synchronous
facade is named `run`. The same suffix rules apply to other operation verbs: for example, `completeAsync`,
`completeStreaming`, and `complete`. The Java surface does not add a synchronous suffix or combine an asynchronous
suffix with a publisher method.

The initial type mapping in
[`docs/java/api-mapping.md`](../java/api-mapping.md) is the naming source for individual concepts. Java may choose a
language-idiomatic name instead of mechanically matching .NET or Python, but the mapping document must be updated in
the same change as any public rename.

### Consequences

- Good, because the Java API can evolve without coupling its compatibility to another framework or provider SDK.
- Good, because Java users get familiar naming and standard JDK types.
- Good, because one namespace and artifact convention makes modules predictable.
- Good, because Java 25 features that are final can be used throughout the implementation.
- Neutral, because Java names will not always be textually identical to .NET or Python names.
- Bad, because consumers must run Java 25 or later and cannot use the framework on Java 21.
- Bad, because framework-owned models require explicit conversion code in every external adapter.

## Validation

- The build must compile and test all Java modules with a Java 25 toolchain and without `--enable-preview`.
- An architecture test must reject public shared-module signatures containing types from provider SDKs, Spring AI,
  LangChain4j, or Reactor.
- Published modules and public packages must follow the coordinate and naming rules in this decision.
- Architecture tests must enforce the `core`/`tools`/`agents`/`workflows`/`orchestrations` ownership split, including
  `ChatClient` in `agent-framework-agents` rather than `core`, and the `com.microsoft.agents.providers.<provider>` /
  `com.microsoft.agents.protocols.<protocol>` adapter namespaces.
- API signature tests must enforce `Agent` as the interface, `BaseAgent` as the convenience base, and the
  `Async`/`Streaming`/unsuffixed method-name rules.

## Pros and Cons of the Options

### Java 25 with framework-owned types and Java-idiomatic names

- Good, because it satisfies the requested platform baseline and keeps the core provider-neutral.
- Good, because Java conventions improve usability without losing cross-language traceability.
- Neutral, because adapters must map between framework-owned and external types.
- Bad, because it has a smaller runtime reach than an older Java baseline.

### Java 21 with framework-owned types

- Good, because Java 21 is available in more existing deployments.
- Neutral, because the ownership and naming design would be unchanged.
- Bad, because it contradicts the approved Java 25 minimum and prevents uniform use of Java 25 final APIs.

### Java 25 with preview APIs

- Good, because preview concurrency APIs can be concise.
- Neutral, because preview features are available to users who explicitly enable them.
- Bad, because every consumer build and runtime would need preview flags.
- Bad, because preview API changes could force avoidable public and binary compatibility breaks.

### Reuse Spring AI or LangChain4j types as the core model

- Good, because one ecosystem would require less conversion code.
- Neutral, because an adapter would still be needed for the other ecosystem and for provider SDKs.
- Bad, because core compatibility and release cadence would become coupled to a third party.
- Bad, because consumers would inherit a framework dependency even when they do not use it.

### Mirror the .NET or Python API names exactly

- Good, because source examples would look more similar across languages.
- Neutral, because concepts would remain recognizable.
- Bad, because prefixes such as `I` and non-Java casing reduce Java API quality.
- Bad, because exact name parity does not provide behavioral parity.

## More Information

- [Java terminology and API mapping](../java/api-mapping.md)
- [ADR-0005: Python naming conventions and renames](0005-python-naming-conventions.md)
