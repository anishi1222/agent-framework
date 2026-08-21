# Agent Framework for Java

This directory contains the Java 25 implementation of Microsoft Agent Framework. Framework behavior
is implemented incrementally: `agent-framework-core` provides the immutable provider-neutral model,
strict streaming aggregation, run cancellation, and safe versioned JSON foundation;
`agent-framework-tools` provides function invocation, approval continuation, and provider-neutral
invocation interception; `agent-framework-tools-shell` provides approval-gated local and
Docker-compatible shell execution plus shell-environment context; `agent-framework-agents` provides the chat-client SPI, agent execution,
sessions, optimistic storage, context/history providers, and middleware;
`agent-framework-harness` assembles bounded autonomous loops, todos, modes, file memory, shared
file access, skills, and background agents; and
`agent-framework-workflows` provides the immutable typed graph, deterministic superstep runtime,
experimental native-control-flow functional workflows, bounded events, state, and checkpoint/resume;
`agent-framework-orchestrations` provides typed
sequential, concurrent, handoff, group-chat, and Magentic patterns. `agent-framework-openai`,
`agent-framework-azure-openai`, `agent-framework-foundry`, `agent-framework-anthropic`,
`agent-framework-bedrock`, `agent-framework-gemini`, `agent-framework-mistral`,
`agent-framework-ollama`, `agent-framework-foundry-local`, `agent-framework-github-copilot`, and
`agent-framework-copilotstudio` provide external provider surfaces.
`agent-framework-azure-authentication`, `agent-framework-azure-ai-persistent`,
`agent-framework-foundry-hosting`, `agent-framework-foundry-evaluations`,
`agent-framework-azure-content-understanding`, `agent-framework-azure-cosmos`,
`agent-framework-azure-cosmos-memory`, `agent-framework-azure-ai-search`,
`agent-framework-valkey`, `agent-framework-mem0`, and `agent-framework-purview`
provide isolated Azure and storage
authentication, persistent-agent, cloud-hosting, evaluation, content-analysis, durable
session/history/checkpoint storage, bounded Valkey history, vector/full-text memory, scoped Azure AI
Search retrieval, Mem0 Platform context, and policy
surfaces.
Request-context compaction is implemented in agents, and the optional observability module provides
OpenTelemetry agent/chat/tool/workflow decorators. `agent-framework-mcp` provides the official MCP Java SDK
client/tool boundary, and `agent-framework-hosting-mcp` exposes framework tools and agents over
stdio or Streamable HTTP/SSE. `agent-framework-a2a` provides the framework-owned A2A v1 model,
secure JDK JSON-RPC/SSE client, and remote-agent adapter; `agent-framework-hosting-a2a` provides
principal-isolated Agent/Workflow hosting and an embedded loopback-first server.
`agent-framework-hosting`, `agent-framework-hosting-http`, and
`agent-framework-hosting-spring` provide generic framework-owned Agent/Workflow/Orchestration registration,
strict JSON, incremental SSE, the `agent-framework-hosting.v1` embedded WebSocket protocol, and an
optional Spring Boot 4.1 / Spring WebFlux 7.0 JSON/SSE adapter. `agent-framework-agui`,
`agent-framework-hosting-agui`, and `agent-framework-hosting-agui-spring` add the strict AG-UI
0.0.57 model/client plus principal-isolated Agent/Workflow/Orchestration HTTP/SSE hosting.
Declarative agent/workflow loading, provider-neutral evaluation, approval-gated CodeAct,
OpenAI Responses and Telegram hosting, a loopback development UI, and strict ChatKit transport
models are provided by their corresponding optional modules.

## Prerequisites

- JDK 25 on `PATH` or available to Gradle toolchain discovery.
- JDK 21 available to Gradle daemon toolchain discovery. The committed root and `build-logic`
  daemon criteria run Gradle and Palantir Java Format 2.97.0 on JDK 21, while compilation, build-logic
  functional tests, and project tests use JDK 25 toolchain launchers with `--release 25`. This avoids
  formatter/JDK-internal incompatibility on newer launcher JDKs without weakening Spotless.
- No system Gradle installation is required.

Start with the [Java getting-started guide](../docs/java/getting-started.md), the
[.NET/Python migration guide](../docs/java/migration-guide.md), and the
[offline executable samples](./samples/README.md).

## Bootstrap and validation

```bash
cd java
./gradlew --version
./gradlew -p build-logic build
./gradlew :agent-framework-conformance:test
./gradlew clean build
./gradlew checkArchitecture
./gradlew :samples:checkSamples
./gradlew generateSbom
./gradlew publishToTestRepository
./gradlew -p consumer-smoke clean run
```

The local publication smoke repository is written to `java/build/test-maven-repository`. To test
normal Maven-local publication instead, run `./gradlew publishToMavenLocal`.

Formatting changes can be applied with:

```bash
./gradlew spotlessApply
```

## Release engineering

`./gradlew generateSbom` writes a deterministic aggregate CycloneDX 1.6 document to
`build/reports/sbom/agent-framework-java.cdx.json`. The document inventories every published
first-party module and the resolved transitive runtime dependency graph; compile-only and test-only
dependencies are excluded. `./gradlew publishRelease` performs a local release-repository dry run
by default. An external signed release supplies:

- `frameworkVersion` with a non-SNAPSHOT version,
- `release=true` and `requireSigning=true`,
- `MAVEN_RELEASE_REPOSITORY_URL`, `MAVEN_RELEASE_REPOSITORY_USERNAME`, and
  `MAVEN_RELEASE_REPOSITORY_PASSWORD`,
- `MAVEN_SIGNING_KEY` and `MAVEN_SIGNING_PASSWORD`.

The [`Java - Release`](../.github/workflows/java-release.yml) workflow builds with Java 25, runs all
quality and sample gates, signs every Maven publication, uploads the SBOM, publishes to the
configured staging repository, and compiles/runs `consumer-smoke` against that repository.

## Modules

The shared-runtime dependency direction is:

`workflows -> agents -> tools -> core`

`orchestrations -> agents -> tools -> core`

`harness -> agents -> tools -> core`

`agent-framework-tools-shell`, `agent-framework-observability`, and
`agent-framework-reactor-adapter` are optional outward adapters
that depend inward on the shared runtime. `agent-framework-mcp` and
`agent-framework-hosting-mcp`, plus `agent-framework-a2a`,
`agent-framework-hosting-a2a`, `agent-framework-agui`, `agent-framework-hosting-agui`, and
`agent-framework-hosting-agui-spring`, plus generic `agent-framework-hosting`,
`agent-framework-hosting-http`, `agent-framework-hosting-spring`,
`agent-framework-hosting-openai`, `agent-framework-hosting-telegram`,
`agent-framework-devui`, and `agent-framework-chatkit` are outward
protocol/hosting adapters. `agent-framework-openai`,
`agent-framework-azure-openai`, `agent-framework-foundry`, `agent-framework-anthropic`,
`agent-framework-bedrock`, `agent-framework-gemini`, `agent-framework-mistral`,
`agent-framework-ollama`, `agent-framework-foundry-local`,
`agent-framework-github-copilot`, `agent-framework-copilotstudio`, and
`agent-framework-azure-ai-persistent` are outward provider adapters. Foundry hosting/evaluations,
Content Understanding, Cosmos DB storage/memory, Azure AI Search, Valkey history, Mem0 Platform
context, Purview, and the shared Azure authentication module are outward integration adapters. Every adapter depends inward on the
smallest shared module it needs; the shared runtime never depends on an adapter.
Declarative agents, declarative workflows, and evaluation remain shared provider-neutral modules;
CodeAct is an outward shell-tool adapter.
`agent-framework-bom` aligns all published module
versions and contains no runtime classes. `agent-framework-conformance` contains versioned,
language-neutral fixtures and test-support APIs; it is not published, is not in the BOM, and cannot
be a production dependency of published modules.

## Structured output and agent decorators

`ChatOptions.structuredOutput` carries a provider-neutral JSON Schema. OpenAI, Azure OpenAI,
Foundry Responses, Anthropic, Bedrock, Gemini, Mistral, Ollama, and Foundry Local map it to their
native structured-output request shape. GitHub Copilot and Copilot Studio reject the option
explicitly because those session protocols do not guarantee it.

```java
StateValue.ObjectValue schema = StateValue.object(Map.of(
        "type", StateValue.string("object"),
        "properties", StateValue.object(Map.of(
                "answer", StateValue.object(Map.of("type", StateValue.string("integer"))))),
        "required", StateValue.array(List.of(StateValue.string("answer"))),
        "additionalProperties", StateValue.bool(false)));

ChatOptions chatOptions = ChatOptions.builder()
        .structuredOutput(new StructuredOutputOptions(
                "answer_payload", "One structured answer.", schema, true))
        .build();

try (ChatAgent chatAgent = new ChatAgent(
                chatClient, AgentMetadata.create(), chatOptions, List.of());
        StructuredOutputAgent<StateValue> agent =
                new StructuredOutputAgent<>(chatAgent, StructuredOutputDecoder.stateValue())) {
    StateValue answer = agent.run("Return the answer as JSON.").value();
}
```

Finite structured responses parse the last non-empty assistant text with the framework serialization
limits; malformed, duplicate-key, trailing, non-finite, or oversized JSON fails explicitly.
`DelegatingAgent<T>` provides transparent metadata/run/stream forwarding. Decorators do not close
their inner agent by default; use the ownership constructor only when the decorator owns it.

`MessageInjectionMiddleware` can be registered in a `ChatAgent` chat-middleware collection. Use its
`enqueueMessages` overloads to atomically queue session messages before or during a run. The agent
drains them at the next safe model turn, waits until function results exist for actionable tool
calls, and preserves the same behavior for finite and streaming execution.

Experimental and release-candidate APIs use runtime `@Experimental` and `@ReleaseCandidate`
annotations. `FeatureStages.describe` exposes their stage and feature ID, while
`FeatureStages.warnOnce` lets application entry points emit a deduplicated runtime warning without
requiring bytecode interception.

`WorkflowViz` renders immutable workflow topology as deterministic Graphviz DOT or Mermaid source,
including conditional, fan-out, and fan-in routes. It escapes labels, generates collision-safe
Mermaid aliases, and writes UTF-8 source without requiring Graphviz or another external process.

## Autonomous agent harness

`agent-framework-harness` provides an opt-in autonomous layer over `ChatAgent`. The default
assembly adds history, todo, mode, and session-isolated file-memory providers. Shared file access,
skills, background agents, AI judging, and autonomous looping remain disabled until configured.

```java
HarnessAgentOptions options = HarnessAgentOptions.builder()
        .fileMemoryStore(new InMemoryAgentFileStore())
        .loopEvaluators(List.of(new CompletionMarkerLoopEvaluator("TASK_COMPLETE")))
        .loopOptions(LoopAgentOptions.builder()
                .maxIterations(5)
                .returnFinalOnly(true)
                .build())
        .build();

try (HarnessAgent agent = HarnessAgents.create(chatClient, options)) {
    AgentResponse<Void> response = agent.run(
            "Investigate the issue and end with TASK_COMPLETE when finished.");
}
```

Every loop has a positive hard iteration cap. Streaming emits each iteration and synthesized
continuation message immediately; `returnFinalOnly` affects finite results only. Evaluators run in
order, and the first evaluator requesting continuation supplies the next message. Approval-required
runs escape the loop rather than being reinvoked automatically.

The default file-memory store is rooted at `./agent-file-memory`; production applications should
usually configure an explicit `AgentFileStore`. `FileSystemAgentFileStore` rejects absolute paths,
traversal, and symbolic-link escape. Shared file tools require approval by default. Background task
metadata is session-persisted, but task futures and child sessions are process-local; an orphaned
persisted `RUNNING` task restores as `LOST`. AI judging sends the original request and latest
response to a separate caller-owned client, so use only a judge endpoint trusted with that content.
See [`agent-framework-harness/README.md`](./agent-framework-harness/README.md).

## Model Context Protocol

The MCP modules pin the official stable `io.modelcontextprotocol.sdk:mcp:2.0.0` implementation.
Their public APIs contain only framework-owned types and JDK contracts:

```java
try (MCPClient client = MCPClient.create(
        MCPStreamableHTTPTransport.builder(URI.create("https://mcp.example.com/mcp"))
                .allowedHosts(Set.of("mcp.example.com"))
                .build())) {
    List<FunctionTool> tools = client.asFunctionToolsAsync("docs")
            .toCompletableFuture()
            .join();
}
```

```java
MCPServerHandle server = MCPServer.builder("local-tools-mcp", "1.0.0")
        .tools(functionTools)
        .build()
        .startStdio();
```

Stdio uses an argument vector and allowlisted child environment. Remote clients require HTTPS and
disable redirects; the embedded HTTP server is loopback-only unless explicitly placed behind a
trusted TLS proxy. See [`agent-framework-mcp/README.md`](./agent-framework-mcp/README.md) and
[`agent-framework-hosting-mcp/README.md`](./agent-framework-hosting-mcp/README.md) for lifecycle,
limits, callback, approval, and unsupported-resume details.

## Agent2Agent protocol

The A2A modules target protocol release **v1.0.1** and wire version **`1.0`**. Interoperability tests
pin the latest stable official Java SDK, `org.a2aproject.sdk:*:1.2.0.Final`. Production transport is
protocol-neutral JDK HTTP/SSE so official SDK, Proto, Quarkus, and Jackson types never enter public
signatures.

```java
try (A2AClient client = A2AClient.create(
        A2AClientOptions.builder(URI.create("https://agent.example/a2a"))
                .allowedHosts(Set.of("agent.example"))
                .build())) {
    AgentCard card = client.fetchAgentCardAsync().toCompletableFuture().join();
}
```

JSON-RPC finite/streaming sends, task get/list/cancel/resubscribe, extended cards, and push-config
CRUD are supported. REST, gRPC, outbound push delivery, Last-Event-ID replay, OAuth acquisition, and
card-signature verification are explicit limitations. See
[`agent-framework-a2a/README.md`](./agent-framework-a2a/README.md) and
[`agent-framework-hosting-a2a/README.md`](./agent-framework-hosting-a2a/README.md).

## Agent–User Interaction protocol

The AG-UI model and encoder goldens pin **`@ag-ui/core` / `@ag-ui/encoder` 0.0.57** and compare the
official .NET **`AGUI.*` 0.0.5** shapes. The AG-UI repository now publishes
**`com.ag-ui.community:java-{core,client,server}:0.1.0`**, but labels that community SDK under
development, so this repository keeps framework-owned Java public types and does not depend on it.

`AGUIClient` uses redirect-free JDK HTTP/SSE. The configurable hosted route defaults to `/ag-ui`;
AG-UI itself mandates no fixed URL. All 33 current schema events are supported, including interrupt
outcomes and encrypted reasoning. Draft `META_EVENT` is rejected; deprecated `THINKING_*` events
remain decode/encode compatibility only.

See [`agent-framework-agui/README.md`](./agent-framework-agui/README.md),
[`agent-framework-hosting-agui/README.md`](./agent-framework-hosting-agui/README.md), and
[`agent-framework-hosting-agui-spring/README.md`](./agent-framework-hosting-agui-spring/README.md).

## Generic Java hosting

The generic hosting modules expose registered Agent, Workflow, and Orchestration targets through the
nonstandard wire version **`java-hosting-2026-08-01`**:

- JSON and SSE routes live under `/v1/agents`, `/v1/workflows`, and `/v1/orchestrations`;
- embedded WebSocket uses `/v1/ws` with exact subprotocol
  **`agent-framework-hosting.v1`**; and
- the optional Spring adapter pins Spring Boot **4.1.0**, Spring Framework **7.0.8**, and Reactor
  **3.8.6**, but supports JSON/SSE only in this release.

The embedded listener is loopback-first. Remote binding requires a trusted TLS-terminating proxy,
an application authenticator, HTTPS advertised origin, and explicit Host/Origin allowlists. Neither
the embedded host nor Spring adapter claims direct TLS termination, historical `Last-Event-ID`
replay, or cross-process continuation. Approval continuation tokens are one-time, expiring,
process-local, and bound to principal, isolation, route, and run.

See [`agent-framework-hosting/README.md`](./agent-framework-hosting/README.md),
[`agent-framework-hosting-http/README.md`](./agent-framework-hosting-http/README.md), and
[`agent-framework-hosting-spring/README.md`](./agent-framework-hosting-spring/README.md) for the
exact route, frame, security, limit, and lifecycle contracts.

## Core API

`com.microsoft.agents.core` owns:

- `Role`, `FinishReason`, `Message`, and the sealed `Content` hierarchy;
- `ChatResponse` / `ChatResponseUpdate` and `AgentResponse<T>` / `AgentResponseUpdate`;
- immutable `RunOptions`, `ChatOptions`, and arbitrary-precision `UsageDetails`;
- `ResponseAggregator`, `RunCancellation`, `RunHandle<T>`, `RunHandleSource<T>`,
  `RunCancellations`, and `RunHandles`; and
- `StateValue`, versioned envelopes, explicit `StateCodec<T>` registration, and
  `JsonStateSerializer`.

Jackson is an implementation dependency of core serialization and does not appear in public model
or SPI signatures. The agents module owns the version-1 session schema; workflow snapshot schemas
remain owned by the workflow runtime.

## Tool runtime safety contracts

- Tool bodies use `ToolUserException` only for expected, user-correctable failures. The loop converts
  that exception to a sanitized correlated result; unexpected runtime failures, framework failures,
  cancellation, and `Error` values propagate.
- Input binding and output-schema validation are separate outcomes. Output mismatches use
  `outputValidationFailed` and the sanitized message
  `Error: Tool output schema validation failed.`.
- `FunctionInvocationOptions.maxBufferedUpdates` is a positive finite update-buffer limit for runs
  that expose an update stream. The default is 256; overflow fails the publisher and run with
  `StreamingBufferOverflowException` and requests run cancellation. Finite convenience calls discard
  update emissions at their source because they return only a terminal result. This bound limits
  framework memory retention; it does not provide end-to-end transport throttling to a provider.
- Core cancellation implementations support removable listeners through
  `ObservableRunCancellation`. The tool loop uses one run-scoped upstream registration and removes
  per-turn and per-tool registrations when work completes.

## Shell tools

`agent-framework-tools-shell` provides `LocalShellExecutor`, `DockerShellExecutor`, and
`ShellEnvironmentProvider`. Local and container executors support persistent/stateless modes,
per-stream head/tail output bounds, timeout, cancellation, and deny-first `ShellPolicy` evaluation.
Their `FunctionTool` adapters expose `FUNCTION` and `SHELL` capabilities and require approval by
default. Host-local approval opt-out additionally requires `acknowledgeUnsafe(true)`.

Docker-compatible defaults disable networking, use a non-root identity and read-only root
filesystem, drop capabilities, enable `no-new-privileges`, and apply memory, process, and `/tmp`
bounds. These controls are a restrictive baseline, not a security guarantee. See
[`agent-framework-tools-shell/README.md`](./agent-framework-tools-shell/README.md) for configuration,
ownership, and isolation details. `JCF-TOOLS-014` binds the production safety, execution,
cancellation, policy, command-argument, and cached environment-context contracts.

## Skills

`agent-framework-agents` provides immutable `Skill`/`SkillFrontmatter` contracts, inline,
subclass-defined, and filesystem-backed skills, case-insensitive resource/script lookup, source
aggregation/filtering/deduplication, keyed single-flight caching, and `SkillsProvider`. The provider
advertises `load_skill`, `read_skill_resource`, and `run_skill_script` as approval-required
`FunctionTool` instances. Directly supplied context-independent skill lists are cached and
deduplicated; caller-owned `SkillsSource` pipelines are never automatically cached because their
results may be tenant- or session-specific.

Filesystem discovery accepts `SKILL.md` roots at depth two, requires the frontmatter name to match
the directory name, bounds resource/script scanning, and skips symbolic links. Scripts execute only
through an explicitly configured `FileSkillScriptRunner`.

`agent-framework-mcp` adds SEP-2640 `skill://index.json` discovery. `skill-md` documents and sibling
resources are fetched lazily, while ZIP/TAR/TAR.GZ archive skills are materialized under a
source-owned or caller-provided directory with compressed/uncompressed byte, file-count, link, and
path-traversal controls. Archive scripts are readable resources only and are never executable.
Parameterized `mcp-resource-template` entries remain deferred until their upstream contract is
stable. `JCF-SKILLS-001` through `JCF-SKILLS-003` bind these production paths.

## Agent execution runtime

`com.microsoft.agents.agents` owns:

- the provider-neutral `ChatClient` SPI and immutable `ChatClientRequest`;
- the public `Agent<T>` interface, abstract `BaseAgent<T>`, and concrete `ChatAgent`;
- immutable `AgentMetadata` and explicitly propagated `AgentRunContext`; and
- finite `runAsync`, streaming `runStreaming`, synchronous `run`, and cancellable `startRun`
  families for text, one message, or ordered message lists.

`BaseAgent` creates one run identity and execution owner per invocation. Synchronous calls wait on the
same `RunHandle` used by finite asynchronous calls, cancel on interruption, restore the interrupt
flag, and throw `SynchronousExecutionException`. Streaming publishers are cold, bounded,
single-subscriber publishers with positive-demand validation and subscription-to-provider
cancellation propagation. The default executor uses virtual threads; caller-provided executors and
chat clients are never closed by the agent. Do not invoke a synchronous agent call from a task
running on the same caller-owned bounded executor when that executor is saturated: the synchronous
caller can block the worker needed to start or complete its run. Use an asynchronous call, reserve
executor capacity, or invoke the synchronous facade from a different thread.

`ChatAgent` adapts `ChatClient` to `FunctionInvocationLoop`, including the no-tools single-turn path.
Provider updates and generated tool-result updates retain their observed order, while the terminal
`AgentResponse` contains the tool-loop history after caller input, folded usage, and the latest
provider finish reason and metadata.

`JCF-AGENTS-001` through `JCF-AGENTS-006` are bound to production lifecycle, ordered provider,
middleware, delegation, message injection, typed metadata, extension, session-history, and
message-source attribution paths.

## OpenAI Responses provider

`agent-framework-openai` publishes `com.microsoft.agents.providers.openai.OpenAIChatClient`, immutable
`OpenAIChatClientOptions` / `OpenAIResponseOptions`, redacting `OpenAISecret`, typed sanitized
exceptions, and the framework-owned `OpenAITransport` injection boundary. The implementation pins
the official stable `com.openai:openai-java:4.50.0` SDK as an implementation dependency; architecture
checks reject `com.openai.*` types in public or protected provider signatures.

Finite and streaming paths map framework messages, text/image/file content, OpenAI-compatible chat
options, function tools/calls/results, reasoning, usage, finish reasons, continuation, and sanitized
errors. Streams are cold, single-subscriber, cancellation-propagating, and bounded. Unsupported
content and Responses-incompatible chat options fail explicitly before transport execution.
`JCF-PROVIDERS-001` is bound to the production client and mapping paths through a deterministic
offline transport. See [`agent-framework-openai/README.md`](./agent-framework-openai/README.md) for
configuration, supported mappings, ownership, and the explicit `liveTest` task.

## External providers

| Module | API / dependency | Verified version |
|---|---|---|
| `agent-framework-anthropic` | official Anthropic Messages Java SDK | `com.anthropic:anthropic-java:2.53.0` |
| `agent-framework-bedrock` | AWS SDK v2 Bedrock Runtime `Converse` / `ConverseStream` | `software.amazon.awssdk:bedrockruntime:2.51.4` |
| `agent-framework-gemini` | official Google Gen AI Java SDK | `com.google.genai:google-genai:1.65.0` |
| `agent-framework-mistral` | Mistral `/v1/chat/completions` JSON/SSE | strict JDK HTTP |
| `agent-framework-ollama` | Ollama `/api/chat` JSON/NDJSON | strict JDK HTTP |
| `agent-framework-foundry-local` | Foundry Local Chat Completions/status/catalog REST | process-neutral REST; no Java native SDK |
| `agent-framework-github-copilot` | [official GitHub Copilot SDK for Java](https://github.github.io/copilot-sdk-java/latest/) ([source](https://github.com/github/copilot-sdk/tree/main/java)) | `com.github:copilot-sdk-java:1.0.9`; CLI `1.0.55+`; SDK owns protocol/RPC/session/event semantics |
| `agent-framework-copilotstudio` | Power Platform Copilot Studio Direct-to-Engine HTTP/SSE | `2022-03-01-preview`; no official Java client |

Every provider exposes only framework-owned/JDK public types, inherits sync and explicit
`RunHandle` semantics from `ChatClient`, bounds streaming and wire data, disables redirects, redacts
credentials/errors, and documents unsupported content before transport. GitHub Copilot additionally
enforces canonical executable/workspace policies and deny-by-default tool permissions; Copilot
Studio uses redirect-free HTTPS, expiring-token refresh, SSE `Last-Event-ID`, and explicit card
continuations. See each module README for
the exact capability and limitation table.

Dependency license verification against the published Maven POMs: Anthropic Java is **MIT**; AWS
SDK v2, Google Gen AI, and Jackson are **Apache-2.0**; GitHub Copilot SDK for Java is **MIT**.
Mistral, Ollama, Foundry Local, and Copilot Studio add no provider SDK dependency.
`checkExternalProviderDependencyConvergence` verifies exact SDK alignment,
one resolved version per Jackson/Netty artifact, and no Reactor dependency in these providers.

## Azure OpenAI and Microsoft Foundry providers

`agent-framework-azure-openai` is a distinct Azure OpenAI Responses adapter. It pins the latest
published official Azure client, `com.azure:azure-ai-openai:1.0.0-beta.16`, because Maven Central
has no GA release for that artifact. Its immutable options validate endpoint, deployment, API
version, exactly-one key/token authentication, timeout, retry, and stream bounds. API keys and
authorization values are redacted. The adapter maps finite/streaming Responses and local parallel
function-call round trips through `ChatAgent`.

`agent-framework-foundry` pins the GA `com.azure:azure-ai-projects:2.3.0` and
`com.azure:azure-ai-agents:2.3.0` SDKs. It supports direct project model Responses and invocation of
an existing versioned agent reference. Existing-agent calls remove fields owned by the service-side
definition while local function tools dispatch returned calls and send results on the next turn.
Foundry conversation-versus-previous-response interpretation remains provider-owned.
`JCF-PROVIDERS-002` binds production client, request mapping, continuation, local tool-loop, and
shared-API isolation paths.

Both modules expose only framework models plus the optional Azure `TokenCredential` abstraction;
Azure/OpenAI service models remain internal. Their injected framework transports make offline tests
deterministic, and their `liveTest` tasks are opt-in and excluded from `check`. Embeddings and broader
provider resource management remain deferred. See each module README for exact configuration and
ownership.

## Azure and Foundry service integrations

| Module | Verified SDK / API | Contract and explicit limitation |
|---|---|---|
| `agent-framework-azure-authentication` | `com.azure:azure-identity:1.18.4` | Framework-owned token provider; Azure Identity types stay internal. |
| `agent-framework-azure-ai-persistent` | `com.azure:azure-ai-agents-persistent:1.0.0-beta.2` / `2025-05-15-preview` | Agent/thread/message/run lifecycle, native SSE, requires-action tool output, bounded polling/cancel; MCP and input continuation are unsupported by the pinned preview SDK. |
| `agent-framework-foundry-hosting` | generic `HostingRegistry` / `HostingDispatcher` | Responses and Persistent bridge with route/principal/isolation/conversation partitioning; default sessions and resume handles are process-local. |
| `agent-framework-foundry-evaluations` | `com.azure:azure-ai-projects:2.3.0`, `com.openai:openai-java:4.50.0`, project `/openai/v1/evals` | Evaluation lifecycle/poll/cancel/output pages and project discovery; evaluator management is an explicit beta opt-in. |
| `agent-framework-azure-content-understanding` | `com.azure:azure-ai-contentunderstanding:1.0.0` / `2025-11-01` | URL/byte analysis LROs and analyzer CRUD/list; remote LRO cancel and framework result-file deletion are not exposed. |
| `agent-framework-azure-cosmos` | `com.azure:azure-cosmos:4.81.0` | Tenant-isolated session/history/checkpoint persistence, ETag CAS, versioned Java envelopes, ordered exactly-once history, atomic checkpoint/ledger, deterministic snapshot listing, and bounded key-scoped purge; no cross-language wire claim. |
| `agent-framework-azure-cosmos-memory` | `com.azure:azure-cosmos:4.81.0` | Provider-neutral memory contracts plus scoped vector/full-text/hybrid retrieval, primitive score compatibility with a no-score sentinel, required list composite index, and untrusted citations; vector policy changes require a new container. |
| [`agent-framework-azure-ai-search`](./agent-framework-azure-ai-search/README.md) | `com.azure:azure-search-documents:12.0.1` / `2026-04-01` | Read-only full-text/vector/hybrid/semantic and existing-knowledge-base retrieval with mandatory tenant/scope pre-filters, schema/vector validation, bounded untrusted citations, and no document mutation or `MemoryStore` CAS claim. |
| [`agent-framework-valkey`](./agent-framework-valkey/README.md) | `io.valkey:valkey-glide:2.5.1` universal jar | Standalone/TLS/ACL bounded history with hashed same-slot keys, strict version-1 messages, atomic idempotent append/trim/dedup/TTL, bounded tail load, clear/count, cancellation/deadline, and internal GLIDE types. |
| [`agent-framework-mem0`](./agent-framework-mem0/README.md) | Mem0 Platform REST V3/V1 through JDK `HttpClient` | Explicit app/user/agent/run scopes, partition-safe V3 batched add/search/list, V1 scoped clear/event operations, bounded polling/retries/cancellation, and untrusted cited context; no unscoped item mutation, Java SDK, or `MemoryStore` CAS claim. |
| `agent-framework-purview` | Microsoft Graph `v1.0` data-security-and-governance APIs | Agent/chat prompt and finite-response enforcement, ETag scope cache, bounded offline activity; streaming egress is not post-evaluated. |

All public signatures use framework/JDK types. Production authentication should use managed identity
or `AzureAuthenticationProviders.productionDefaultCredential()` with least privilege. The
integrations perform data-plane operations only, never provision Azure/Graph resources or role
assignments, never close caller credentials/executors/stores, and never auto-delete remote resources.
`checkAzureSdkDependencyConvergence` verifies exact Azure/OpenAI SDK pins plus selected
Jackson/Reactor/Netty convergence. Module tests inject Azure/JDK HTTP clients and make no live cloud
calls.

## Sessions, context, and history

- `AgentSession` has immutable identity and thread-safe state/history mutation; `state()`,
  `messages()`, and `snapshot()` return immutable detached views containing only `StateValue` data.
- `SessionStore` uses `loadAsync`, CAS `saveAsync`, and CAS `deleteAsync`. Expected revision `-1` is
  create-only; positive revisions are opaque. `InMemorySessionStore` acknowledges process-memory
  durability and returns detached snapshots.
- `AgentSessionCodec` defines the Java version-1 `agent-session` envelope through
  `JsonStateSerializer`; it accepts unknown additive properties but rejects missing required data,
  unsafe discriminators, wrong kinds/versions, and every portable parser-limit rejection case.
- `ContextProvider` instances execute in registration order. `HistoryProvider` loads chronological
  history before caller input and appends caller input then response messages after success.
  `InMemoryHistoryProvider` is the default and stores messages in the session snapshot.
- A mutable session has a deterministic single-run gate. Concurrent runs fail with
  `SessionBusyException`; storage conflicts and save failures propagate without retry.

`JCF-SESSIONS-001` through `JCF-SESSIONS-004` bind the codec, CAS store, detached-copy behavior,
and Cosmos partition/ETag/history contract.

## Context and history compaction

`com.microsoft.agents.agents.context` provides immutable `CompactionStrategy`,
`CompactionRequest`, `CompactionResult`, and `CompactionAudit` contracts. Built-in strategies cover:

- logical-turn `SlidingWindowCompactionStrategy`;
- message-threshold `TruncationCompactionStrategy`;
- estimator-driven `TokenBudgetCompactionStrategy`;
- injected-`ChatClient` `SummarizationCompactionStrategy`;
- `SelectiveToolCallCompactionStrategy` and bounded `ToolResultCompactionStrategy`;
- ordered `TokenBudgetComposedStrategy`; and
- threshold-derived `ContextWindowCompactionStrategy`.

`MessageGroupAnnotator` keeps function calls/results atomic, protects unresolved approval-related
content and pre-user preamble groups, and preserves system/developer instructions.
`TokenEstimator.heuristic()` is the deterministic four-code-point default; provider-specific
estimators can be supplied explicitly. Oversized required groups are retained and reported as
`CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT`. Audit metadata exposes every generated
summary ID while retaining the singular convenience field for one-summary operations.

`CompactingHistoryProvider` changes only the request projection; it never rewrites caller or session
history. Durable replacement is a separate explicit operation through `PersistedHistoryCompactor`,
which performs one detached load and one compare-and-set save with no conflict retry.
Summarization calls the injected chat client directly with cancellation and instrumentation
suppression, and creates a replacement only after a successful non-empty response.

`JCF-CONTEXT-001` binds the production grouping, selective tool removal, tool-result summary,
composition, estimator override, context-window, audit, and protected-preamble paths.

## Workflow execution runtime

`com.microsoft.agents.workflows` owns:

- immutable `Workflow<I,O>` graphs built by `WorkflowBuilder<I,O>`, typed `Executor<I,O>` and
  `FunctionExecutor<I,O>` nodes, direct/conditional edges, and fan-out/fan-in groups;
- a deterministic concurrent superstep runner with stable node/event ordering, explicit loop
  opt-in, a max-superstep guard, atomic state boundaries, typed `StateKey<T>` codecs, deterministic
  reducers, explicit `WorkflowValueEncoder` event payload encoding, and conflict failure instead of
  completion-order last-writer-wins;
- finite `CompletionStage<WorkflowRunResult<O>>`, bounded cold `Flow.Publisher<WorkflowEvent>`,
  synchronous, and explicit `RunHandle` surfaces derived from one run core;
- `CheckpointStorage` load/save/delete CAS, explicit storage capabilities, effect-free unsupported
  atomic-commit failure, `InMemoryCheckpointStorage`, and version-1 `WorkflowCheckpointCodec`; and
- optional `AgentExecutor` message adaptation while the graph and runner remain agent-independent.

The experimental functional runtime is separate from graph execution. `FunctionalWorkflow<I,O>`
executes a user function directly, while `FunctionalStep<I,O>` adds typed `(stepName, callIndex)`
result caching, bypass events, and per-step checkpoints. `FunctionalRunContext` is passed explicitly
across asynchronous continuations and exposes typed workflow state, custom events, cancellation, and
deterministic `requestInfo` interruption/resume. Completed-step replay advances cached generated
request counts so `auto::<index>` identifiers remain stable; final checkpoints can be restored by a
new workflow instance after fingerprint validation. `FunctionalWorkflow.asAgent(...)` adds an
explicitly mapped `FunctionalWorkflowAgent<I,O>`: information requests appear as informational
`request_info` function calls, correlated function results resume the pending invocation without
re-running the input mapper, and workflow events remain live bounded agent updates. See ADR 0039 and
`JCF-WORKFLOWS-008`.

Fan-out branches run concurrently on stable Java 25 `ExecutorService` APIs. Completion timing never
controls state merge, routing, or event order. Failed supersteps cancel sibling branches and do not
commit state. Default branch and control executors use virtual threads and are closed with the
workflow; caller-provided executors are never closed.

Checkpoint boundaries contain the next pending executors and buffered inputs, so resume does not
replay nodes completed before that boundary. They also persist each fan-in target's next epoch to
release, preserving monotonic `FanInInput.epoch()` values for completed and incomplete epochs across
resume. Resume rejects workflow identity, schema, graph fingerprint, and revision mismatches. Graph
fingerprints use type-tagged, length-prefixed structural data and intentionally exclude executor
behavior and conditional predicates. The built-in generic path uses ordinary checkpoint CAS and is
therefore explicitly at-least-once for external effects after a crash. Exactly-once claims require a
storage provider advertising `ATOMIC_CHECKPOINT_AND_LEDGER` and `commitAsync`, or provider
idempotency using a durable invocation ID.

Only one direct or conditional route may connect a source/target pair. Combine conditions that lead
to the same target into one predicate with boolean OR; this guarantees that one source output cannot
schedule the target twice. Default event encoding accepts `StateValue`, strings, booleans, finite
numbers, lists, string-keyed maps, and fan-in inputs. Other strongly typed values require a
`WorkflowValueEncoder` configured through `WorkflowRunOptions.Builder.valueEncoder(...)`; unsupported
values fail explicitly instead of being replaced by a class-name string.

`JCF-WORKFLOWS-001` through `JCF-WORKFLOWS-008` are bound to production graph execution,
fan-out/fan-in, failure/cancellation, checkpoint resume, canonical encoding, durable Cosmos
checkpoint storage, visualization, functional replay/HITL, and the workflow serialization rejection
corpus.

## Multi-agent orchestrations

`com.microsoft.agents.orchestrations` owns:

- immutable `OrchestrationParticipant`, `OrchestrationRunOptions`, typed
  `OrchestrationResult<O>`, deterministic `OrchestrationEvent`, termination/error outcomes, and
  explicit approval or human-input continuations;
- `SequentialOrchestration` with shared-transcript or previous-response history, an injectable
  output-to-input transform, stop/continue failure handling, and duplicate-safe transcript assembly;
- `ConcurrentOrchestration<O>` with virtual-thread or caller-owned dispatch, declaration-ordered
  results independent of completion timing, fail-fast sibling cancellation, collect-errors semantics,
  and a typed successful-result aggregator;
- registry-constrained `HandoffOrchestration` with typed function-call routing, allowed transitions,
  independent unknown/disallowed/self/loop policies, maximum turn/handoff guards, and explicit
  input-required outcomes;
- `GroupChatOrchestration` with manager and selector contracts, deterministic round-robin and
  strongly validated agent-based selection, allowed transitions, repetition rules, a race-free shared
  transcript, termination predicates, and a maximum-turn guard; and
- `MagenticOrchestration` with typed plans/tasks/progress assessments, an immutable ledger, registered
  assignment validation, stall detection, bounded replanning/iterations, injectable framework-owned
  manager prompts, and explicit solved/unsolved/failure outcomes.

Every orchestration implements `Orchestration<O>` and derives finite `CompletionStage`, cold bounded
`Flow.Publisher`, synchronous, and `RunHandle` cancellation surfaces from one run core. Session-aware
`ChatAgent` participants use run-local shared or isolated sessions according to
`OrchestrationSessionPolicy`; concurrent runs reject a shared session. Orchestration, run, event,
correlation, and participant IDs are added to underlying `RunOptions.metadata`, allowing wrapped
agents and optional observability adapters to retain context without a reverse dependency.
Framework-owned run executors use virtual threads and close at the run boundary; caller-provided
executors and all participant agents remain caller-owned.
Suspended phases resume through one-time process-local `resumeAsync`, `resumeStreaming`, `resume`, and
`startResume` APIs. Pattern state, transcript, results, event sequence, sessions, and the logical run ID
are retained without replaying completed turns. Continuations expire under configured TTL and entry
bounds and are invalidated on close; they are not cross-process checkpoints.

See [`agent-framework-orchestrations/README.md`](./agent-framework-orchestrations/README.md) for
concise, compile-checked usage patterns and continuation boundaries.

## Middleware and approval continuation

`BaseAgent` applies agent middleware, `MiddlewareChatClient` applies chat middleware to finite and
streaming calls, and `ChatAgent` adapts function middleware to the tools-owned
`ToolInvocationInterceptor` SPI. Pipelines preserve nesting order, isolate metadata per invocation,
allow a valid short circuit, propagate/translate failures, honor cancellation, and reject a second
`next` invocation.

Session-aware `ChatAgent` runs return `AgentRunResult`: either a terminal response or an
`AgentContinuation`. Pending approval state stores only safe call/history/digest data. Decisions are
bound to session, logical run, invocation, and request digest and are consumable once; stale,
cross-session, or replayed decisions fail. A no-session continuation is explicitly process-local.
Persisted restart resume is documented as at-least-once for external effects unless a durable ledger
or provider idempotency capability is configured; the built-in session surface does not claim
exactly-once restart behavior.

## User-Agent and feature-usage telemetry

`UserAgentUtil` composes the base `agent-framework-java/{version}` product contribution from the
published JAR version and deterministic hosting prefixes. `FeatureUsageRegistry.global()` records a
process-lifetime 128-bit set of coarse framework capabilities that were actually exercised. It
never records prompts, arguments, identifiers, endpoints, counts, or payload values.

The live `(feat=v1.<lowercase-hex>)` comment is not added to general third-party traffic. The Azure
OpenAI transport evaluates the actual HTTPS origin on every pipeline attempt and stamps the token
only for reviewed Azure OpenAI/Foundry suffixes; custom gateways are default-deny and have any stale
token removed. The base framework User-Agent remains independent from the feature token.

- `AGENT_FRAMEWORK_FEATURE_MASK_DISABLED=true|1` disables only feature marking and emission.
- `AGENT_FRAMEWORK_USER_AGENT_DISABLED=true|1` disables the complete Agent Framework User-Agent
  contribution and implicitly disables the feature mask.
- `FOUNDRY_HOSTING_ENVIRONMENT=<non-empty>` adds the `foundry-hosting` prefix.

## Optional OpenTelemetry observability

`agent-framework-observability` pins the stable OpenTelemetry Java API at `1.64.0`; SDK and in-memory
exporter artifacts are test-only. `AgentFrameworkTelemetry` accepts an application-owned
`OpenTelemetry` instance and never mutates the global SDK. `OpenTelemetryAgent`,
`OpenTelemetryChatClient`, `OpenTelemetryFunctionMiddleware`, and `OpenTelemetryWorkflow` emit the
current Development GenAI span, event, and metric names with explicit async/virtual-thread/Flow
context propagation and duplicate-span suppression.

Sensitive message and tool payloads are off by default. `TelemetryContentPolicy` requires explicit
opt-in, always redacts credential-like keys, replaces control characters, and bounds captured scalar
values. Identifier recording defaults to omit and can be configured to hash or record sanitized
values. See [`agent-framework-observability/README.md`](./agent-framework-observability/README.md)
for configuration and a complete sample.
