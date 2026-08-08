# Agent Framework for Java

This directory contains the Java 25 implementation of Microsoft Agent Framework. Framework behavior
is implemented incrementally: `agent-framework-core` provides the immutable provider-neutral model,
strict streaming aggregation, run cancellation, and safe versioned JSON foundation;
`agent-framework-tools` provides function invocation, approval continuation, and provider-neutral
invocation interception; `agent-framework-agents` provides the chat-client SPI, agent execution,
sessions, optimistic storage, context/history providers, and middleware; and
`agent-framework-workflows` provides the immutable typed graph, deterministic superstep runtime,
bounded events, state, and checkpoint/resume; `agent-framework-orchestrations` provides typed
sequential, concurrent, handoff, group-chat, and Magentic patterns. `agent-framework-openai`,
`agent-framework-azure-openai`, and `agent-framework-foundry` provide the initial external provider
surfaces through their official SDKs. Request-context compaction is implemented in agents, and the
optional observability module provides OpenTelemetry agent/chat/tool/workflow decorators; other
external providers remain later work. `agent-framework-mcp` provides the official MCP Java SDK
client/tool boundary, and `agent-framework-hosting-mcp` exposes framework tools and agents over
stdio or Streamable HTTP/SSE.

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

The shared-runtime dependency direction is:

`workflows -> agents -> tools -> core`

`orchestrations -> agents -> tools -> core`

`agent-framework-observability` and `agent-framework-reactor-adapter` are optional outward adapters
that depend inward on the shared runtime. `agent-framework-mcp` and
`agent-framework-hosting-mcp` are outward protocol/hosting adapters. `agent-framework-openai`,
`agent-framework-azure-openai`, and `agent-framework-foundry` are outward provider adapters. The
Azure modules reuse the implemented OpenAI Responses protocol boundary, and every adapter depends
inward on the smallest shared module it needs; the shared runtime never depends on an adapter.
`agent-framework-bom` aligns all published module
versions and contains no runtime classes. `agent-framework-conformance` contains versioned,
language-neutral fixtures and test-support APIs; it is not published, is not in the BOM, and cannot
be a production dependency of published modules.

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

`JCF-AGENTS-001` through `JCF-AGENTS-003` are bound to production lifecycle, ordered provider, and
middleware paths.

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

## Azure OpenAI and Microsoft Foundry providers

`agent-framework-azure-openai` is a distinct Azure OpenAI Responses adapter. It pins the latest
published official Azure client, `com.azure:azure-ai-openai:1.0.0-beta.16`, because Maven Central
has no GA release for that artifact. Its immutable options validate endpoint, deployment, API
version, exactly-one key/token authentication, timeout, retry, and stream bounds. API keys and
authorization values are redacted. The adapter maps finite/streaming Responses and local parallel
function-call round trips through `ChatAgent`.

`agent-framework-foundry` pins the GA `com.azure:azure-ai-projects:2.2.0` and
`com.azure:azure-ai-agents:2.2.0` SDKs. It supports direct project model Responses and invocation of
an existing versioned agent reference. Existing-agent calls remove fields owned by the service-side
definition while local function tools dispatch returned calls and send results on the next turn.
Foundry conversation-versus-previous-response interpretation remains provider-owned.
`JCF-PROVIDERS-002` binds production client, request mapping, continuation, local tool-loop, and
shared-API isolation paths.

Both modules expose only framework models plus the optional Azure `TokenCredential` abstraction;
Azure/OpenAI service models remain internal. Their injected framework transports make offline tests
deterministic, and their `liveTest` tasks are opt-in and excluded from `check`. Persistent Foundry
thread/run semantics, hosted sessions, embeddings, evaluations, and provider resource management are
explicitly deferred. See each module README for exact configuration and ownership.

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

`JCF-SESSIONS-001` through `JCF-SESSIONS-003` bind the codec, CAS store, and detached-copy behavior.

## Context and history compaction

`com.microsoft.agents.agents.context` provides immutable `CompactionStrategy`,
`CompactionRequest`, `CompactionResult`, and `CompactionAudit` contracts. Built-in strategies cover:

- logical-turn `SlidingWindowCompactionStrategy`;
- message-threshold `TruncationCompactionStrategy`;
- estimator-driven `TokenBudgetCompactionStrategy`; and
- injected-`ChatClient` `SummarizationCompactionStrategy`.

`MessageGroupAnnotator` keeps function calls/results atomic, protects unresolved approval-related
content, and preserves system/developer instructions. `TokenEstimator.heuristic()` is the
deterministic four-code-point default; provider-specific estimators can be supplied explicitly.
Oversized required groups are retained and reported as
`CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT`.

`CompactingHistoryProvider` changes only the request projection; it never rewrites caller or session
history. Durable replacement is a separate explicit operation through `PersistedHistoryCompactor`,
which performs one detached load and one compare-and-set save with no conflict retry.
Summarization calls the injected chat client directly with cancellation and instrumentation
suppression, and creates a replacement only after a successful non-empty response.

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

`JCF-WORKFLOWS-001` through `JCF-WORKFLOWS-005` are bound to production graph execution,
fan-out/fan-in, failure/cancellation, checkpoint resume, canonical encoding, and the workflow
serialization rejection corpus.

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
