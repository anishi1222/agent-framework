# Java Port — Terminology & API Mapping

**Task ID:** `java-parity-baseline`
**Status:** Core model, tools, agents, sessions, context/history providers, context compaction,
middleware, approval continuation, workflow and orchestration runtimes, optional OpenTelemetry
observability, and the initial Responses providers are implemented; other external providers remain
proposed.
**Last updated:** 2026-08-06

This document provides the cross-language name mapping so that the Java implementation uses
consistent terminology and so that reviewers can navigate between .NET, Python, and Java surfaces
without ambiguity.  Every row is grounded in actual source symbols; repository-relative paths are
provided for each.

See [`feature-parity-matrix.md`](./feature-parity-matrix.md) for the full parity matrix and
initial/later-parity status.

---

## 1. Terminology Glossary

| Canonical concept | .NET term / symbol | Python term / symbol | Java (proposed) | Notes |
|---|---|---|---|---|
| **Agent** | `AIAgent` (abstract class) | `Agent` / `BaseAgent` | `Agent<T>` (interface) / `BaseAgent<T>` (abstract base) | Implemented. `Agent` is the Java public contract; `BaseAgent` is optional implementation convenience. |
| **Agent session** | `AgentSession` | `AgentSession` | `AgentSession` | Identical across all three languages. |
| **Agent response** | `AgentResponse<T>` | `AgentResponse` | `AgentResponse<T>` | .NET generic; Python untyped. Java uses generic. |
| **Response update (streaming)** | `AgentResponseUpdate` | `AgentResponseUpdate` | `AgentResponseUpdate` | One chunk of a streaming run. |
| **Run options** | `AgentRunOptions` | `AgentRunInputs` / `ChatOptions` | `RunOptions` | Java uses the concise framework-owned name. |
| **Chat message** | `ChatMessage` (from `Microsoft.Extensions.AI`) | `Message` | `Message` | External `Microsoft.Extensions.AI` in .NET; internal in Python. Java owns its own type. |
| **Message content** | `AIContent` | `Content` | `Content` | Java follows Python name; it is more concise. |
| **Message role** | `ChatRole` (external) | `Role` / `RoleLiteral` | `Role` | Java follows Python name. |
| **Chat client** | `IChatClient` (external interface) | `BaseChatClient` | `com.microsoft.agents.agents.ChatClient` (interface) | Implemented with immutable `ChatClientRequest`; Java uses no `I` prefix. |
| **OpenAI chat client** | `Microsoft.Agents.AI.OpenAI` adapter | `OpenAIChatClient` | `com.microsoft.agents.providers.openai.OpenAIChatClient` | Implemented against the official OpenAI Responses SDK with framework-owned options, cancellation, bounded streaming, and no SDK types in public signatures. |
| **Context provider** | `AIContextProvider` | `ContextProvider` | `ContextProvider` | Implemented; Java follows Python name. |
| **History provider** | `ChatHistoryProvider` | `HistoryProvider` | `HistoryProvider` | Implemented; Java follows Python name. |
| **In-memory history** | `InMemoryChatHistoryProvider` | `InMemoryHistoryProvider` | `InMemoryHistoryProvider` | Implemented as the default session-backed history provider. |
| **Session store** | `AgentSessionStore` | `SessionStore` | `SessionStore` | Implemented CAS SPI; Java follows Python name. |
| **Versioned snapshot** | store-specific ETag/version | store-specific | `VersionedSnapshot<T>` | Immutable snapshot plus opaque optimistic revision. |
| **Run cancellation** | `CancellationToken` | task cancellation | `RunCancellation`, `RunHandle<T>` | Explicit per-run cancellation; `CompletionStage` alone is not the cancellation controller. |
| **Session context** | `AgentRunContext` | `SessionContext` | `AgentRunContext` | Implemented with explicit session, contribution, run, and cancellation propagation. |
| **Session state bag** | `AgentSessionStateBag` | `AgentSession.state` (dict-like) | `AgentSessionStateBag` | Java follows .NET. |
| **Agent metadata** | `AIAgentMetadata` | `Agent.id`, `Agent.description` properties | `AgentMetadata` | Simplified name in Java. |
| **Function tool** | `AIFunction` | `FunctionTool` | `FunctionTool` | Java follows Python name. |
| **Tool annotation** | `AIFunction` constructor | `@tool` decorator | `@ToolMethod` annotation | Java keeps the `Tool` interface name and uses `@ToolMethod` for public methods. |
| **Tool mode** | `FunctionInvokingChatClient` options | `ToolMode` | `ToolMode` | Java follows Python. |
| **Tool capability** | `AITool` sub-interfaces | `Supports*` protocols | `ToolCapability` interfaces | Java introduces explicit capability interfaces. |
| **Middleware (agent)** | `IAIAgentMiddleware` / extensions | `AgentMiddleware` | `AgentMiddleware` | Java follows Python name. |
| **Middleware (chat)** | `IChatClientMiddleware` / MEAI | `ChatMiddleware` | `ChatMiddleware` | Java follows Python name. |
| **Middleware (function)** | `FunctionInvokingChatClient` pipeline | `FunctionMiddleware` | `FunctionMiddleware` | Java follows Python name. |
| **Middleware termination** | — | `MiddlewareTermination` | `MiddlewareTermination` | Java follows Python. |
| **Workflow** | `Workflow` | `Workflow` | `Workflow<I,O>` | Implemented as an immutable, reusable, strongly typed graph and run surface. |
| **Workflow builder** | `WorkflowBuilder` | `WorkflowBuilder` | `WorkflowBuilder<I,O>` | Implemented with typed node/edge methods plus complete graph validation. |
| **Workflow agent** | `WorkflowHostAgent` | `WorkflowAgent` | `WorkflowAgent` | Java follows Python name. |
| **Executor** | `Executor` | `Executor` | `Executor<I,O>` | Implemented with runtime input/output types and optional checkpoint codecs. |
| **Function executor** | `FunctionExecutor` | `FunctionExecutor` | `FunctionExecutor<I,O>` | Implemented synchronous and asynchronous adapters. |
| **Edge** | `Edge` | `Edge` | `Edge` | Identical. |
| **Fan-in / fan-out** | `FanInEdgeData` / `FanOutEdgeData` | `FanInEdgeGroup` / `FanOutEdgeGroup` | `FanInEdge` / `FanOutEdge` | Simplified Java names. |
| **Checkpoint storage** | `CheckpointManager` + `CheckpointInfo` | `CheckpointStorage` | `CheckpointStorage` | Implemented CAS SPI with stable capabilities and atomic checkpoint/ledger commit contract. |
| **Workflow event** | `WorkflowEvent` | `WorkflowEvent` | `WorkflowEvent` | Implemented with deterministic sequence, run/node/superstep/correlation IDs. |
| **Orchestration participant** | `AIAgent` bound by orchestration builders | `SupportsAgentRun` participant | `OrchestrationParticipant` | Implemented immutable descriptor with a stable explicit ID and caller-owned `Agent<?>`. |
| **Orchestration run/result/event** | workflow run plus orchestration outputs | workflow run plus output/intermediate events | `Orchestration<O>`, `OrchestrationRunOptions`, `OrchestrationResult<O>`, `OrchestrationEvent`, `OrchestrationResumeInput` | Implemented finite/streaming/sync/`RunHandle` run and one-time process-local resume views, deterministic event IDs, typed outcomes, and bounded continuation retention. |
| **Sequential orchestration** | `SequentialWorkflowBuilder` | `SequentialBuilder` | `SequentialOrchestration` | Implemented shared/previous-response history, typed transform, stop/continue policies, and duplicate-safe transcript. |
| **Concurrent orchestration** | `ConcurrentWorkflowBuilder` | `ConcurrentBuilder` | `ConcurrentOrchestration<O>` | Implemented declaration-ordered aggregation, fail-fast sibling cancellation/skipped events, race-independent error-over-input precedence, collect-errors outcome, and isolated sessions. |
| **Handoff orchestration** | `HandoffWorkflowBuilder` | `HandoffBuilder` | `HandoffOrchestration` | Implemented typed function-call routing, registered targets/transitions, independent unknown/disallowed/self/loop policies, and resumable input-required outcomes. |
| **Group-chat orchestration** | `GroupChatWorkflowBuilder`, `GroupChatManager` | `GroupChatBuilder`, orchestrators | `GroupChatOrchestration`, `GroupChatManager`, `GroupChatSelector` | Implemented round-robin/agent selectors, exact-ID validation, transitions, repetition policy, transcript, and turn bound. |
| **Magentic orchestration** | `MagenticWorkflowBuilder`, `MagenticProgressLedger` | `MagenticBuilder`, `MagenticContext` | `MagenticOrchestration`, `MagenticManager`, `MagenticLedger` | Implemented typed plan/task/assessment state, stall detection, bounded replanning, and solved/unsolved outcomes. |
| **Skills provider** | `Microsoft.Agents.AI.Mcp.Skills` | `SkillsProvider`, `SkillsSource` | `SkillsProvider`, `SkillsSource` | Java follows Python name. |
| **Skill** | (MCP-oriented in .NET) | `Skill`, `ClassSkill`, `InlineSkill`, `FileSkill`, `MCPSkill` | `Skill` hierarchy | Java follows Python. |
| **Harness agent** | `HarnessAgent` | `create_harness_agent` | `HarnessAgent` | Java follows .NET class name. |
| **Compaction strategy** | `CompactionStrategy` | `CompactionStrategy` | `CompactionStrategy` | Implemented as an immutable asynchronous SPI with result/audit metadata. |
| **Token estimator** | tokenizer delegate | `TokenizerProtocol` | `TokenEstimator` (interface) | Implemented with deterministic heuristic and provider override. |
| **OpenTelemetry observability** | `OpenTelemetryAgent` + MEAI/workflow telemetry | `AgentTelemetryLayer`, `ChatTelemetryLayer` | `OpenTelemetryAgent`, `OpenTelemetryChatClient`, `OpenTelemetryFunctionMiddleware`, `OpenTelemetryWorkflow` | Optional outward module; implemented against stable OTel Java `1.64.0`. |
| **A2A agent (client)** | `A2AAgent` | `A2AAgent` | `A2AAgent` | Identical. |
| **A2A executor (server)** | `A2AAgentSession` bridging | `A2AExecutor` | `A2AExecutor` | Java follows Python name. |
| **AG-UI** | `AGUIEndpointRouteBuilderExtensions` | `AgentFrameworkAgent` (AG-UI wrapper) | `AGUIAdapter` | Java introduces adapter name. |
| **MCP tool (client)** | `TaskAwareMcpClientAIFunction` | `MCPStdioTool`, `MCPStreamableHTTPTool`, `MCPWebsocketTool` | `MCPTool` variants | Java follows Python naming pattern. |
| **Foundry agent** | `FoundryAgent` | `FoundryChatClient` + `FoundryAgent` | `FoundryAgent` | Identical to .NET. |
| **Evaluation** | `Evaluation/` (Foundry) | `evaluate_agent`, `evaluate_workflow`, `Evaluator` | `AgentEvaluator` | Java adapts .NET class-name convention. |
| **Feature stage** | — | `ExperimentalFeature`, `ReleaseCandidateFeature` | `@Experimental`, `@ReleaseCandidate` | Java annotation equivalents. |
| **User agent / telemetry** | `AgentFrameworkUserAgentPolicy` | `AGENT_FRAMEWORK_USER_AGENT`, `prepend_agent_framework_to_user_agent` | `UserAgentUtil.prepend()` | Java static utility. |
| **Secret string** | — | `SecretString` | `SecretString` | Java follows Python. |
| **Settings loader** | env-var convention (`UPPER_SNAKE_CASE`) | `load_settings`, `SecretString` | `SettingsLoader` | Java follows convention. |

---

## 2. Module / Package Name Mapping

| Concept | .NET NuGet ID | Python pip ID | Java artifact and public package |
|---|---|---|---|
| Neutral models / serialization / concurrency | `Microsoft.Agents.AI.Abstractions` | `agent-framework-core` | `com.microsoft.agents:agent-framework-core` · `com.microsoft.agents.core` |
| Tool metadata / runtime | `Microsoft.Extensions.AI` + `Microsoft.Agents.AI` | `agent-framework-core` | `com.microsoft.agents:agent-framework-tools` · `com.microsoft.agents.tools` |
| Agent / session runtime | `Microsoft.Agents.AI.Abstractions` + `Microsoft.Agents.AI` | `agent-framework-core` | `com.microsoft.agents:agent-framework-agents` · `com.microsoft.agents.agents` |
| OpenAI provider | `Microsoft.Agents.AI.OpenAI` | `agent-framework-openai` (bundled in core) | `com.microsoft.agents:agent-framework-openai` · `com.microsoft.agents.providers.openai` (implemented; `JCF-PROVIDERS-001` bound) |
| Azure OpenAI provider | `Microsoft.Agents.AI.OpenAI` Azure configuration | OpenAI-compatible Azure configuration | `com.microsoft.agents:agent-framework-azure-openai` · `com.microsoft.agents.providers.azureopenai` (implemented) |
| Foundry / Azure AI provider | `Microsoft.Agents.AI.Foundry` | `agent-framework-foundry` | `com.microsoft.agents:agent-framework-foundry` · `com.microsoft.agents.providers.foundry` (implemented; `JCF-PROVIDERS-002` bound) |
| Anthropic provider | `Microsoft.Agents.AI.Anthropic` | `agent-framework-anthropic` | `com.microsoft.agents:agent-framework-anthropic` · `com.microsoft.agents.providers.anthropic` |
| AWS Bedrock provider | — | `agent-framework-bedrock` | `com.microsoft.agents:agent-framework-bedrock` · `com.microsoft.agents.providers.bedrock` |
| Workflow engine | `Microsoft.Agents.AI.Workflows` | `agent-framework-core` (built-in) | `com.microsoft.agents:agent-framework-workflows` · `com.microsoft.agents.workflows` |
| Orchestrations | `Microsoft.Agents.AI.Workflows` builders | `agent-framework-orchestrations` | `com.microsoft.agents:agent-framework-orchestrations` · `com.microsoft.agents.orchestrations` (implemented) |
| Observability | `Microsoft.Agents.AI` + MEAI/workflow telemetry | core observability layers | `com.microsoft.agents:agent-framework-observability` · `com.microsoft.agents.observability` |
| Hosting abstractions | `Microsoft.Agents.AI.Hosting` | `agent-framework-hosting` | `com.microsoft.agents:agent-framework-hosting` · `com.microsoft.agents.hosting` |
| A2A protocol | `Microsoft.Agents.AI.A2A` + `Hosting.A2A` | `agent-framework-a2a` + `agent-framework-hosting-a2a` | `com.microsoft.agents:agent-framework-a2a` · `com.microsoft.agents.protocols.a2a` |
| AG-UI protocol | `Microsoft.Agents.AI.Hosting.AGUI.AspNetCore` | `agent-framework-ag-ui` | `com.microsoft.agents:agent-framework-agui` · `com.microsoft.agents.protocols.agui` |
| MCP protocol/tools adapter | `Microsoft.Agents.AI.Mcp` | `agent-framework-hosting-mcp` | `com.microsoft.agents:agent-framework-mcp` · `com.microsoft.agents.protocols.mcp` |
| Cosmos storage | `Microsoft.Agents.AI.CosmosNoSql` | `agent-framework-azure-cosmos` | `com.microsoft.agents:agent-framework-azure-cosmos` · `com.microsoft.agents.storage.cosmos` |
| Redis / Valkey storage | `Microsoft.Agents.AI.Valkey` | `agent-framework-redis` | `com.microsoft.agents:agent-framework-valkey` · `com.microsoft.agents.storage.valkey` |
| Mem0 memory | `Microsoft.Agents.AI.Mem0` | `agent-framework-mem0` | `com.microsoft.agents:agent-framework-mem0` · `com.microsoft.agents.storage.mem0` |
| Harness / autonomous loop | `Microsoft.Agents.AI.Harness` | `agent-framework-core` (built-in) | `com.microsoft.agents:agent-framework-harness` |
| Evaluation | `Microsoft.Agents.AI.Foundry` (sub-module) | `agent-framework-core` (built-in) | `com.microsoft.agents:agent-framework-evaluation` |
| Declarative agents | `Microsoft.Agents.AI.Declarative` | `agent-framework-declarative` | `com.microsoft.agents:agent-framework-declarative` |
| Developer UI | `Microsoft.Agents.AI.DevUI` | `agent-framework-devui` | `com.microsoft.agents:agent-framework-devui` |
| Conformance test support | test projects | package tests | non-published Gradle project `agent-framework-conformance` · `com.microsoft.agents.conformance` |

`ChatClient` is part of `agent-framework-agents` / `com.microsoft.agents.agents`; `agent-framework-core` remains limited
to neutral models and options, serialization contracts, and concurrency/run-control primitives. Generic Java
HTTP/SSE/WebSocket hosting is later-parity, while ASP.NET Core-specific route-extension APIs are `n/a`.

---

## 3. Async / Streaming API Shape

### .NET
- Async: `Task<T>` / `ValueTask<T>`, `Async` method-name suffix.
- Streaming: `IAsyncEnumerable<T>`.
- DI: `Microsoft.Extensions.DependencyInjection`.

### Python
- Async: `async def` / `await`, `asyncio`.
- Streaming: `AsyncIterator[T]`.
- DI: manual / `poe`-based.

### Java (implemented execution surface — no preview APIs, Java 25+)
- Async: `CompletionStage<T>` / `CompletableFuture<T>`.  `Async` suffix on methods.
- Streaming: `Flow.Publisher<T>` (JDK 9+ reactive streams). `Streaming` suffix on methods.
- Sync facade: unsuffixed operation names such as `run(...)` and `complete(...)`.
- Cancellation: `RunHandle<T>` owns `resultAsync()`, `cancellation()`, and `cancel()`; finite convenience overloads accept
  `RunCancellation`. A returned `CompletionStage` is completion-only and does not itself guarantee cancellation.
- DI: none mandated by core; adapters for Spring / Quarkus / plain CDI kept in optional modules.

| Operation | .NET | Python | Java |
|---|---|---|---|
| Single agent turn | `Task<AgentResponse<T>> RunAsync(...)` | `async def run(...) -> AgentResponse` | `CompletionStage<AgentResponse<T>> runAsync(...)` |
| Streaming agent turn | `IAsyncEnumerable<AgentResponseUpdate> RunStreamingAsync(...)` | `AsyncIterator[AgentResponseUpdate]` | `Flow.Publisher<AgentResponseUpdate> runStreaming(...)` |
| Cancellable agent turn | `RunAsync(..., CancellationToken)` | task + cancellation scope | `RunHandle<AgentResponse<T>> startRun(...)` or `runAsync(..., RunCancellation)` |
| Sync facade | — | — | `AgentResponse<T> run(...)` (waits on `RunHandle.resultAsync()`) |
| Chat client single | `Task<ChatCompletion> CompleteAsync(...)` (MEAI external) | `async def get_response(...)` | `CompletionStage<ChatResponse> completeAsync(...)` |
| Chat client streaming | `IAsyncEnumerable<StreamingChatCompletionUpdate>` | `AsyncIterator[ChatResponseUpdate]` | `Flow.Publisher<ChatResponseUpdate> completeStreaming(...)` |
| Workflow run | `Task<WorkflowOutputEvent> RunAsync(...)` | `async def run(...)` | `CompletionStage<WorkflowRunResult> runAsync(...)` |
| Workflow streaming | streaming runner | `AsyncIterator[WorkflowEvent]` | `Flow.Publisher<WorkflowEvent> runStreaming(...)` |

### Exact Java lifecycle and storage contracts

| Type | Public methods |
|---|---|
| `com.microsoft.agents.core.RunCancellation` | `cancel()`, `isCancellationRequested()`, `cancelledAsync()` |
| `com.microsoft.agents.core.RunHandle<T>` | `resultAsync()`, `cancellation()`, `cancel()` |
| `com.microsoft.agents.agents.ChatClient` | `completeAsync(...)`, `completeStreaming(...)`, `complete(...)`, `startCompletion(...)` |
| `com.microsoft.agents.providers.openai.OpenAIChatClient` | `builder()`, `options()`, provider-neutral finite/streaming `ChatClient` methods, and `close()` |
| `com.microsoft.agents.providers.openai.OpenAIChatClientOptions` | immutable `builder()` for API key, model, base URL, organization, project, timeout, retries, bounded updates, and response defaults |
| `com.microsoft.agents.providers.openai.OpenAITransport` | injected framework-owned finite/streaming boundary; nested request/response/event values contain no SDK types |
| `com.microsoft.agents.agents.Agent<T>` | `runAsync(...)`, `runStreaming(...)`, `run(...)`, `startRun(...)` for `String`, `Message`, and `List<Message>` inputs |
| `com.microsoft.agents.agents.AgentRunContext` | immutable `runId`, agent metadata, start time, input messages, run options, cancellation, and metadata |
| `com.microsoft.agents.agents.AgentSession` | immutable `sessionId`; thread-safe state/history mutation; detached `state()`, `messages()`, and `snapshot()` views |
| `com.microsoft.agents.agents.SessionStore` | `loadAsync(SessionKey)`, `saveAsync(SessionKey, AgentSessionSnapshot, long expectedRevision)`, `deleteAsync(SessionKey, long expectedRevision)` |
| `com.microsoft.agents.agents.ContextProvider` / `HistoryProvider` | ordered `provideAsync(...)` / `completedAsync(...)`; chronological `loadMessagesAsync(...)` / `appendMessagesAsync(...)` |
| `com.microsoft.agents.agents.context.CompactionStrategy` | `compactAsync(CompactionRequest)` returning immutable `CompactionResult` / `CompactionAudit`; built-in sliding-window, truncation, token-budget, and summarization strategies |
| `com.microsoft.agents.agents.context.TokenEstimator` | `estimateTokens(Message)` plus saturating ordered-list default; `heuristic()` supplies the deterministic provider-neutral fallback |
| `com.microsoft.agents.agents.context.CompactingHistoryProvider` / `PersistedHistoryCompactor` | request-only history projection by default; explicit one-load/one-CAS persisted replacement |
| `com.microsoft.agents.agents.AgentMiddleware` / `ChatMiddleware` / `FunctionMiddleware` | finite and streaming interception with immutable contexts and single-use `next` contracts |
| `com.microsoft.agents.agents.ChatAgent` session continuation | `createSessionAsync()`, `loadSessionAsync(...)`, session-aware `runAsync(...)` / `runStreaming(...)`, `pendingContinuation(...)`, `resumeAsync(...)`, `resumeStreaming(...)`, and synchronous `resume(...)` |
| `com.microsoft.agents.workflows.StorageCapability` | `ATOMIC_CHECKPOINT_AND_LEDGER` |
| `com.microsoft.agents.workflows.CheckpointStorage` | `capabilities()`, `loadAsync(CheckpointKey)`, `saveAsync(CheckpointKey, WorkflowCheckpoint, long expectedRevision)`, `deleteAsync(CheckpointKey, long expectedRevision)`, `commitAsync(CheckpointCommit, long expectedRevision)` |
| `com.microsoft.agents.workflows.Workflow<I,O>` | `runAsync(...)`, `runStreaming(...)`, `run(...)`, `startRun(...)`, `resumeAsync(...)`, `resumeStreaming(...)`, `resume(...)`, and `startResume(...)` |
| `com.microsoft.agents.workflows.StateKey<T>` / `WorkflowState` | explicit `StateCodec<T>`, optional deterministic reducer, immutable boundary snapshots, and typed `get(...)` |
| `com.microsoft.agents.tools.ToolInvocationLedger` | `lookupAsync(InvocationId)`, `recordPendingAsync(InvocationRecord, long expectedRevision)`, `recordOutcomeAsync(InvocationOutcome, long expectedRevision)` |
| `com.microsoft.agents.core.StateCodec<T>` | `typeId()`, `currentVersion()`, `encode(T)`, `migrate(StateValue, int fromVersion, int toVersion)`, `decode(StateValue, int version)` |
| `com.microsoft.agents.core.SerializationLimits` | `maxDocumentBytes()`, `maxNestingDepth()`, `maxStringLength()`, `maxNumericTokenLength()`, `maxCollectionEntries()` |
| `com.microsoft.agents.observability.AgentFrameworkTelemetry` | application-owned `OpenTelemetry`, provider name, identifier policy, content policy, and no global SDK mutation |
| `com.microsoft.agents.observability.OpenTelemetryAgent` / `OpenTelemetryChatClient` / `OpenTelemetryFunctionMiddleware` / `OpenTelemetryWorkflow` | GenAI spans, events, metrics, async/Flow context propagation, operation-specific duplicate suppression, and exactly-once stream closure |

Store loads return `CompletionStage<Optional<VersionedSnapshot<T>>>`; writes return
`CompletionStage<VersionedSnapshot<T>>`. Session expected revision `-1` is create-only; positive
revisions are opaque compare-and-set versions and conflicts never silently retry. `commitAsync` is part of every
`CheckpointStorage` SPI. Callers inspect `capabilities()` before effects and choose transactional ledger
(`ATOMIC_CHECKPOINT_AND_LEDGER` + `commitAsync`), provider idempotency, or the documented at-least-once path. An adapter
without `ATOMIC_CHECKPOINT_AND_LEDGER` fails `commitAsync` with `UnsupportedStorageCapabilityException` before writing
the checkpoint, ledger, or any other effect; callers do not invoke it speculatively and fall back afterward.

The runtime binds `JCF-AGENTS-001` through `JCF-AGENTS-003`, `JCF-SESSIONS-001` through
`JCF-SESSIONS-003`, and `JCF-PROVIDERS-001` / `JCF-PROVIDERS-002` to production execution, provider
ordering, middleware nesting/termination, version-1 session serialization, optimistic conflict
handling, detached in-memory storage, and the real OpenAI and Foundry
client/request/response/continuation mapping paths.
Persisted approval continuation is at-least-once for external effects after restart unless a durable
invocation ledger or provider idempotency capability is configured; the current `ChatAgent` session
surface does not claim exactly-once restart behavior.

---

## 4. Naming Conventions

| Convention | .NET | Python | Java |
|---|---|---|---|
| Class names | `PascalCase` | `PascalCase` | `PascalCase` |
| Interface names | `IPascalCase` | Protocol classes (no `I`) | `PascalCase` (no `I` prefix) |
| Method names | `PascalCase` + `Async` suffix | `snake_case` | `camelCase`; finite async uses `Async`, publishers use `Streaming`, sync is unsuffixed |
| Constants | `UPPER_SNAKE_CASE` env vars; `PascalCase` fields | `UPPER_SNAKE_CASE` | `UPPER_SNAKE_CASE` static finals |
| Packages | `Microsoft.Agents.AI.*` | `agent_framework.*` | `com.microsoft.agents.*` |
| Test class suffix | `UnitTests` / `IntegrationTests` | `test_*.py` | `Test` suffix (e.g., `AgentTest`) |
| Copyright header | `// Copyright (c) Microsoft. All rights reserved.` | `# Copyright (c) Microsoft. All rights reserved.` | `// Copyright (c) Microsoft. All rights reserved.` |

---

## 5. Key Type Cross-Reference

### Core models and SPIs

| Java (proposed) | .NET source | Python source |
|---|---|---|
| `com.microsoft.agents.core.AgentResponse<T>` | [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponse.cs`](../../dotnet/src/Microsoft.Agents.AI.Abstractions/AgentResponse.cs) | [`python/packages/core/agent_framework/_types.py`](../../python/packages/core/agent_framework/_types.py) |
| `com.microsoft.agents.core.Message` | `Microsoft.Extensions.AI.ChatMessage` (external) | [`python/packages/core/agent_framework/_types.py`](../../python/packages/core/agent_framework/_types.py) · `Message` |
| `com.microsoft.agents.core.RunHandle<T>` / `RunCancellation` | `CancellationToken` + task | task cancellation |
| `com.microsoft.agents.core.StateCodec<T>` / `VersionedSnapshot<T>` | store-specific serializers and versions | [`python/packages/core/agent_framework/_sessions.py`](../../python/packages/core/agent_framework/_sessions.py) |

### Tools and agents

| Java (proposed) | .NET source | Python source |
|---|---|---|
| `com.microsoft.agents.tools.FunctionTool` / `Tool` / `@ToolMethod` | `Microsoft.Extensions.AI.AIFunction` (external) | [`python/packages/core/agent_framework/_tools.py`](../../python/packages/core/agent_framework/_tools.py) · `FunctionTool` |
| `com.microsoft.agents.tools.ToolApprovalRequest` / `ToolApprovalDecision` | approval middleware state | [`python/packages/core/agent_framework/_middleware.py`](../../python/packages/core/agent_framework/_middleware.py) · `ToolApprovalState` |
| `com.microsoft.agents.tools.ToolInvocationLedger` / `InvocationId` | function-invocation state | function-invocation state |
| `com.microsoft.agents.agents.ChatClient` / `ChatClientRequest` | `Microsoft.Extensions.AI.IChatClient` (external) | [`python/packages/core/agent_framework/_clients.py`](../../python/packages/core/agent_framework/_clients.py) · `BaseChatClient` |
| `com.microsoft.agents.agents.Agent<T>` / `BaseAgent<T>` / `ChatAgent` | [`dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs`](../../dotnet/src/Microsoft.Agents.AI.Abstractions/AIAgent.cs) and `ChatClientAgent` | [`python/packages/core/agent_framework/_agents.py`](../../python/packages/core/agent_framework/_agents.py) |
| `com.microsoft.agents.agents.AgentSession` | [`dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs`](../../dotnet/src/Microsoft.Agents.AI.Abstractions/AgentSession.cs) | [`python/packages/core/agent_framework/_sessions.py`](../../python/packages/core/agent_framework/_sessions.py) |
| `com.microsoft.agents.agents.ContextProvider` | [`dotnet/src/Microsoft.Agents.AI.Abstractions/AIContextProvider.cs`](../../dotnet/src/Microsoft.Agents.AI.Abstractions/AIContextProvider.cs) | [`python/packages/core/agent_framework/_sessions.py`](../../python/packages/core/agent_framework/_sessions.py) · `ContextProvider` |
| `com.microsoft.agents.agents.HistoryProvider` | [`dotnet/src/Microsoft.Agents.AI.Abstractions/ChatHistoryProvider.cs`](../../dotnet/src/Microsoft.Agents.AI.Abstractions/ChatHistoryProvider.cs) | [`python/packages/core/agent_framework/_sessions.py`](../../python/packages/core/agent_framework/_sessions.py) · `HistoryProvider` |
| `com.microsoft.agents.agents.AgentMiddleware` | (extension pattern) | [`python/packages/core/agent_framework/_middleware.py`](../../python/packages/core/agent_framework/_middleware.py) · `AgentMiddleware` |
| `com.microsoft.agents.agents.SessionStore` | [`dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs`](../../dotnet/src/Microsoft.Agents.AI.Hosting/AgentSessionStore.cs) | [`python/packages/core/agent_framework/_sessions.py`](../../python/packages/core/agent_framework/_sessions.py) · `SessionStore` |
| `com.microsoft.agents.agents.context.*` | [`dotnet/src/Microsoft.Agents.AI/Compaction/`](../../dotnet/src/Microsoft.Agents.AI/Compaction/) | [`python/packages/core/agent_framework/_compaction.py`](../../python/packages/core/agent_framework/_compaction.py) |
| `com.microsoft.agents.observability.*` | [`dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs`](../../dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs) | [`python/packages/core/agent_framework/observability.py`](../../python/packages/core/agent_framework/observability.py) |

### Workflow

| Java (proposed) | .NET source | Python source |
|---|---|---|
| `com.microsoft.agents.workflows.Workflow` | [`dotnet/src/Microsoft.Agents.AI.Workflows/Workflow.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/Workflow.cs) | [`python/packages/core/agent_framework/_workflows/_workflow.py`](../../python/packages/core/agent_framework/_workflows/_workflow.py) |
| `com.microsoft.agents.workflows.WorkflowBuilder` | [`dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowBuilder.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowBuilder.cs) | [`python/packages/core/agent_framework/_workflows/_workflow_builder.py`](../../python/packages/core/agent_framework/_workflows/_workflow_builder.py) |
| `com.microsoft.agents.workflows.Executor` | [`dotnet/src/Microsoft.Agents.AI.Workflows/Executor.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/Executor.cs) | [`python/packages/core/agent_framework/_workflows/_executor.py`](../../python/packages/core/agent_framework/_workflows/_executor.py) |
| `com.microsoft.agents.workflows.FunctionExecutor` | [`dotnet/src/Microsoft.Agents.AI.Workflows/FunctionExecutor.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/FunctionExecutor.cs) | [`python/packages/core/agent_framework/_workflows/_function_executor.py`](../../python/packages/core/agent_framework/_workflows/_function_executor.py) |
| `com.microsoft.agents.workflows.WorkflowEvent` | [`dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowEvent.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/WorkflowEvent.cs) | [`python/packages/core/agent_framework/_workflows/_events.py`](../../python/packages/core/agent_framework/_workflows/_events.py) |
| `com.microsoft.agents.workflows.CheckpointStorage` | [`dotnet/src/Microsoft.Agents.AI.Workflows/CheckpointManager.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/CheckpointManager.cs) | [`python/packages/core/agent_framework/_workflows/_checkpoint.py`](../../python/packages/core/agent_framework/_workflows/_checkpoint.py) |

### Orchestrations

| Java (implemented) | .NET source | Python source |
|---|---|---|
| `Orchestration<O>` / `OrchestrationResult<O>` / `OrchestrationEvent` | [`dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/OrchestrationBuilderBase.cs) | [`python/packages/orchestrations/agent_framework_orchestrations/_workflow_builder.py`](../../python/packages/orchestrations/agent_framework_orchestrations/_workflow_builder.py) |
| `SequentialOrchestration` | [`dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/SequentialWorkflowBuilder.cs) | [`python/packages/orchestrations/agent_framework_orchestrations/_sequential.py`](../../python/packages/orchestrations/agent_framework_orchestrations/_sequential.py) |
| `ConcurrentOrchestration<O>` | [`dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/ConcurrentWorkflowBuilder.cs) | [`python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py`](../../python/packages/orchestrations/agent_framework_orchestrations/_concurrent.py) |
| `HandoffOrchestration` / `HandoffRequest` / `HandoffTarget` | [`dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/HandoffWorkflowBuilder.cs) | [`python/packages/orchestrations/agent_framework_orchestrations/_handoff.py`](../../python/packages/orchestrations/agent_framework_orchestrations/_handoff.py) |
| `GroupChatOrchestration` / `GroupChatManager` / selectors | [`dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/GroupChatWorkflowBuilder.cs) | [`python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py`](../../python/packages/orchestrations/agent_framework_orchestrations/_group_chat.py) |
| `MagenticOrchestration` / `MagenticManager` / `MagenticLedger` | [`dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs`](../../dotnet/src/Microsoft.Agents.AI.Workflows/MagenticWorkflowBuilder.cs) | [`python/packages/orchestrations/agent_framework_orchestrations/_magentic.py`](../../python/packages/orchestrations/agent_framework_orchestrations/_magentic.py) |

### Protocols

| Java (proposed) | .NET source | Python source |
|---|---|---|
| `com.microsoft.agents.protocols.a2a.A2AAgent` | [`dotnet/src/Microsoft.Agents.AI.A2A/A2AAgent.cs`](../../dotnet/src/Microsoft.Agents.AI.A2A/A2AAgent.cs) | [`python/packages/a2a/`](../../python/packages/a2a/) · `A2AAgent` |
| `com.microsoft.agents.protocols.a2a.A2AExecutor` | [`dotnet/src/Microsoft.Agents.AI.A2A/A2AAgentSession.cs`](../../dotnet/src/Microsoft.Agents.AI.A2A/A2AAgentSession.cs) | [`python/packages/a2a/`](../../python/packages/a2a/) · `A2AExecutor` |
| `com.microsoft.agents.protocols.agui.AGUIAdapter` | [`dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/`](../../dotnet/src/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore/) | [`python/packages/ag-ui/`](../../python/packages/ag-ui/) · `AgentFrameworkAgent` |
| `com.microsoft.agents.protocols.mcp.MCPTool` | [`dotnet/src/Microsoft.Agents.AI.Mcp/`](../../dotnet/src/Microsoft.Agents.AI.Mcp/) | [`python/packages/core/agent_framework/_mcp.py`](../../python/packages/core/agent_framework/_mcp.py) |

### Providers

| Java (proposed) | .NET source | Python source |
|---|---|---|
| `com.microsoft.agents.providers.openai.OpenAIChatClient`, `OpenAIChatClientOptions`, `OpenAIResponseOptions`, and `OpenAITransport` (implemented) | [`dotnet/src/Microsoft.Agents.AI.OpenAI/`](../../dotnet/src/Microsoft.Agents.AI.OpenAI/) | [`python/packages/openai/`](../../python/packages/openai/) |
| `com.microsoft.agents.providers.azureopenai.AzureOpenAIChatClient`, `AzureOpenAIChatClientOptions`, and `AzureOpenAITransport` (implemented) | [`dotnet/src/Microsoft.Agents.AI.OpenAI/`](../../dotnet/src/Microsoft.Agents.AI.OpenAI/) | [`python/packages/openai/`](../../python/packages/openai/) Azure tests |
| `com.microsoft.agents.providers.foundry.FoundryChatClient`, `FoundryChatClientOptions`, `FoundryAgent`, and `FoundryTransport` (implemented) | [`dotnet/src/Microsoft.Agents.AI.Foundry/FoundryAgent.cs`](../../dotnet/src/Microsoft.Agents.AI.Foundry/FoundryAgent.cs) | [`python/packages/foundry/`](../../python/packages/foundry/) |
| `com.microsoft.agents.providers.anthropic.AnthropicChatClient` | [`dotnet/src/Microsoft.Agents.AI.Anthropic/`](../../dotnet/src/Microsoft.Agents.AI.Anthropic/) | [`python/packages/anthropic/`](../../python/packages/anthropic/) |

---

## 6. Exception Hierarchy

| Java (proposed) | Parent | Completion/synchronous mapping | .NET / Python equivalent |
|---|---|---|---|
| `AgentFrameworkException` | `RuntimeException` | Root for framework failures | `agent_framework.exceptions.AgentFrameworkException` |
| `ValidationException` | `AgentFrameworkException` | Invalid framework-owned value or incompatible aggregation update | `ValueError` / `ContentError` |
| `AgentExecutionException` | `AgentFrameworkException` | Async stage cause; sync wrapper cause | agent run failure |
| `RunCancelledException` | `AgentExecutionException` | Async stage cause and explicit cancellation classification | cancellation token / task cancellation |
| `SynchronousExecutionException` | `AgentExecutionException` | Thrown only by unsuffixed blocking facades; preserves typed cause | synchronous wrapper |
| `SerializationException` | `AgentFrameworkException` | Codec/envelope/version/limit failure | serialization or validation error |
| `StorageConflictException` | `AgentFrameworkException` | Failed optimistic revision/CAS write | ETag/version conflict |
| `UnsupportedStorageCapabilityException` | `AgentFrameworkException` | Effect-free failure when a storage SPI operation requires an unadvertised capability | unsupported storage operation |
| `MiddlewareException` | `AgentExecutionException` | Middleware failure | `agent_framework.exceptions.MiddlewareException` |
| `OpenAIProviderException` and typed subclasses | `AgentExecutionException` | Sanitized provider status/request ID/error code; credentials and response bodies are not retained | OpenAI SDK/provider errors |
| `OpenAIUnsupportedContentException` | `ValidationException` | Explicit pre-transport content/media/role rejection | provider content error |
| `OpenAIStreamingBufferOverflowException` | `AgentExecutionException` | Bounded stream failure with upstream cancellation | provider stream overflow |
| `AzureOpenAIProviderException` | `AgentExecutionException` | Sanitized kind/status/request/correlation/service code; no body or credential retention | Azure OpenAI SDK/provider errors |
| `FoundryProviderException` | `AgentExecutionException` | Sanitized kind/status/request/correlation/service code, including unsupported initial surfaces | Foundry SDK/provider errors |
| `UserInputRequiredException` | `AgentExecutionException` | Approval/input suspension | `agent_framework.exceptions.UserInputRequiredException` |
| `WorkflowException` | `AgentExecutionException` | Workflow root | `agent_framework.exceptions.WorkflowException` |
| `WorkflowCheckpointException` | `WorkflowException` | Checkpoint encode/store failure; cause is retained | `agent_framework.exceptions.WorkflowCheckpointException` |
| `WorkflowConvergenceException` | `WorkflowException` | Convergence failure | `agent_framework.exceptions.WorkflowConvergenceException` |
| `WorkflowRunnerException` | `WorkflowException` | Runner failure | `agent_framework.exceptions.WorkflowRunnerException` |
| `OrchestrationExecutionException` | `AgentFrameworkException` | Orchestration runtime/manager failure; finite stage cause and sync wrapper cause | orchestration workflow failure |
| `OrchestrationStreamingBufferOverflowException` | `OrchestrationExecutionException` | Bounded orchestration event stream failure with run cancellation | orchestration stream overflow |

An asynchronous method completes its stage exceptionally with the typed failure above. An unsuffixed synchronous
facade throws `SynchronousExecutionException` and retains that typed failure as its cause; interruption also uses
`SynchronousExecutionException`, after cancelling the run and restoring the interrupt flag. Storage CAS conflicts and
serialization failures remain identifiable causes and are not flattened into a generic execution error.

Python source: [`python/packages/core/agent_framework/exceptions.py`](../../python/packages/core/agent_framework/exceptions.py)

---

## 7. Configuration & Secrets Convention

| Language | Convention |
|---|---|
| .NET | Environment variables `UPPER_SNAKE_CASE`; `IConfiguration` (optional) |
| Python | `load_settings(MySettings)`, `SecretString`; env vars `UPPER_SNAKE_CASE` |
| Java (proposed) | Environment variables `UPPER_SNAKE_CASE`; `SettingsLoader` utility; `SecretString` wrapper; no framework DI mandated |

Python source: [`python/packages/core/agent_framework/_settings.py`](../../python/packages/core/agent_framework/_settings.py)

---

## 8. Test Convention Mapping

| Aspect | .NET | Python | Java (proposed) |
|---|---|---|---|
| Unit test framework | xUnit / MSTest | pytest | JUnit 5 |
| Mocking | Moq | pytest-mock / `unittest.mock` | Mockito |
| Async test | `async Task` + `Async` suffix | `@pytest.mark.asyncio` | `@Test` with `CompletableFuture.get()` or project reactor test |
| Test arrangement comments | `// Arrange`, `// Act`, `// Assert` | `# Arrange`, `# Act`, `# Assert` | `// Arrange`, `// Act`, `// Assert` |
| Integration tests | Separate `*.IntegrationTests` project | `python/tests/` | Separate `*IntegrationTest` class / module |
| Conformance fixtures | protocol/unit conformance tests | core/provider/workflow tests | `java/agent-framework-conformance/src/main/resources/conformance/v1/`; exact `JCF-*-NNN` registrations in `conformance/manifest-v1.json` |

---

## 9. ADR Cross-Reference

The following ADRs are particularly relevant to the Java port design and are located at
[`docs/decisions/`](../decisions/):

| ADR | Title | Relevance to Java |
|---|---|---|
| `0001` | Agent run response | Core `AgentResponse<T>` shape |
| `0002` | Agent tools | Tool system design |
| `0003` | OpenTelemetry instrumentation | Telemetry conventions |
| `0005` | Python naming conventions | Informs Java cross-language alignment |
| `0006` | User approval | `ToolApprovalMiddleware` design |
| `0007` | Agent filtering middleware | Middleware pipeline pattern |
| `0009` | Long-running operations | `CompletionStage` / streaming applicability |
| `0010` | AG-UI support | AG-UI protocol port |
| `0011` | Create/Get agent API | Agent instantiation API |
| `0015` | Agent run context | `AgentRunContext` shape |
| `0018` | AgentThread serialization | Session/state serialization (no cross-language wire compat initially) |
| `0021` | Agent skills design | Skills system |
| `0022` | Chat history persistence | `HistoryProvider` + `SessionStore` |
| `0027` | Hosting channels | Hosting abstraction design |
| `0033` | Feature usage bitmask | Telemetry bitmask port |
| `0034` | Python session store serialization | Session serialization reference |
| [`0035`](../decisions/0035-java-platform-and-api-conventions.md) | Java platform and API conventions | Java 25, framework-owned model, naming, Maven coordinates, and package namespaces |
| [`0036`](../decisions/0036-java-execution-and-streaming-model.md) | Java execution and streaming model | `CompletionStage`, `Flow.Publisher`, sync facade, virtual threads, and stable concurrency APIs |
| [`0037`](../decisions/0037-java-modules-dependencies-and-distribution.md) | Java modules, dependencies, and distribution | Gradle modules, dependency direction, adapter isolation, versioning, Maven Central, and BOM |
| [`0038`](../decisions/0038-java-state-serialization-and-compatibility.md) | Java state serialization and compatibility | Jackson JSON state/checkpoints and behavioral rather than initial wire compatibility |

---

## 10. Not Mapped (Intentionally Excluded from Initial Scope)

| Surface | Reason |
|---|---|
| Cross-language session/checkpoint wire compatibility | Explicitly excluded per confirmed constraints |
| Generic Java HTTP/SSE/WebSocket hosting | `later-parity` — framework-specific adapters implement the portable transport behavior |
| ASP.NET Core route extensions (`MapOpenAIResponses`, `MapA2A`, `MapAGUI`) | `n/a` — the ASP.NET-specific API shape has no Java counterpart |
| Aspire hosting integration | `n/a` — no Aspire for Java |
| PowerFx in declarative workflows | `sdk-gap` — no PowerFx Java SDK |
| Hyperlight sandboxed execution | `sdk-gap` — no Hyperlight Java SDK |
| Claude Agent SDK integration | `sdk-gap` — Anthropic has a Java Messages SDK, but no Java Claude Agent SDK equivalent |
| Mistral provider | `later-parity` — implement through Mistral's standard HTTP/JSON API adapter |
| Lab / experimental package | `n/a` — experimental only |
