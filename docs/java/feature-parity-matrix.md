# Java Port — Feature-Parity Matrix

**Task ID:** `java-parity-baseline`
**Status:** Core model foundation and tools runtime implemented; remaining initial-scope runtime modules are not yet implemented.
**Last updated:** 2026-08-05

This document captures every major public surface in the .NET and Python implementations, its
proposed Java module/API destination, the expected contract-test source, and the initial-scope
status.  It is the single authoritative reference for "what Java must eventually cover" and for
tracking which pieces are in the initial milestone versus later parity work.

The `agent-framework-core` implementation binds `JCF-CORE-001` through `JCF-CORE-005` to
framework-owned message/content, response aggregation, options, and cancellation types. Its generic
state reader also executes the raw serialization acceptance/rejection corpus. The
`agent-framework-tools` implementation binds `JCF-TOOLS-001` through `JCF-TOOLS-012` to production
tool contracts, safe schema binding, approvals, invocation ownership, and provider-neutral loop
types. Agents, sessions, workflow snapshots, providers, and later-parity rows remain pending.

---

## Confirmed Constraints

| Constraint | Detail |
|---|---|
| Java minimum | Java 25, no preview APIs |
| Async model | `CompletionStage<T>` for async, `Flow.Publisher<T>` for streaming, plus thin sync facade |
| Build system | Gradle Kotlin DSL |
| Core ownership | Framework-owned (not a thin wrapper of another SDK) |
| Behavioral compatibility | Prioritised over API shape identity |
| Cross-language wire compat | No initial cross-language session/checkpoint wire compatibility |
| ADRs | [`0035`](../decisions/0035-java-platform-and-api-conventions.md), [`0036`](../decisions/0036-java-execution-and-streaming-model.md), [`0037`](../decisions/0037-java-modules-dependencies-and-distribution.md), and [`0038`](../decisions/0038-java-state-serialization-and-compatibility.md) define the Java platform/API, execution, module/distribution, and serialization/compatibility decisions |
| Build files | Gradle Kotlin DSL foundation and non-published `agent-framework-conformance` module are scaffolded |

---

## How to Read This Table

| Column | Meaning |
|---|---|
| **Area / Group** | Logical feature cluster |
| **.NET project** | NuGet-packaged project under `dotnet/src/` |
| **Python package** | pip package under `python/packages/` |
| **Proposed Java module** | Maven group / Gradle module name (advisory) |
| **Expected contract-test source** | Existing test project(s) to port or inspire Java tests |
| **Status** | `initial-scope` · `later-parity` · `sdk-gap` · `n/a` |

**Status legend**

- `initial-scope` — must be in the first Java milestone.
- `later-parity` — needed for full parity but deferred.
- `sdk-gap` — semantic parity requires a vendor SDK or runtime capability for which no official/supported Java
  equivalent or standards-based adapter exists. An available official Java SDK, including an isolated preview SDK, is
  `later-parity`, not `sdk-gap`.
- `n/a` — the surface has no meaningful Java counterpart (e.g., ASP.NET Core-specific helpers).

**Planned Java conformance suites**

Suite IDs are stable prefixes. Concrete cases use a three-digit suffix (for example,
`JCF-TOOLS-007`). Initial-scope rows below name exact cases. Registrations live in
[`manifest-v1.json`](../../java/agent-framework-conformance/src/main/resources/conformance/manifest-v1.json);
[`ConformanceManifestCoverageTest`](../../java/agent-framework-conformance/src/test/java/com/microsoft/agents/conformance/ConformanceManifestCoverageTest.java)
mechanically checks the matrix, manifest, and fixture directory.

| Suite ID | Manifest case prefix | Fixture/test location |
|---|---|---|
| `JCF-CORE` | `JCF-CORE-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/core/` |
| `JCF-TOOLS` | `JCF-TOOLS-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/tools/` |
| `JCF-AGENTS` | `JCF-AGENTS-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/agents/` |
| `JCF-SESSIONS` | `JCF-SESSIONS-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/sessions/` |
| `JCF-WORKFLOWS` | `JCF-WORKFLOWS-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/workflows/` |
| `JCF-ORCHESTRATIONS` | `JCF-ORCHESTRATIONS-*` | Reserved for later-parity manifest cases |
| `JCF-PROTOCOLS` | `JCF-PROTOCOLS-*` | Reserved for later-parity manifest cases |
| `JCF-HOSTING` | `JCF-HOSTING-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/hosting/` |
| `JCF-HOSTING-TRANSPORT` | `JCF-HOSTING-TRANSPORT-*` | Reserved for later-parity manifest cases |
| `JCF-HOSTING-ASPNET-SURFACE` | `JCF-HOSTING-ASPNET-SURFACE-*` | Reserved for Java `n/a` classification cases |
| `JCF-PROVIDERS` | `JCF-PROVIDERS-*` | `java/agent-framework-conformance/src/main/resources/conformance/v1/providers/` |
| `JCF-CONTEXT` | `JCF-CONTEXT-*` | Reserved for later-parity manifest cases |
| `JCF-SKILLS` | `JCF-SKILLS-*` | Reserved for later-parity manifest cases |
| `JCF-HARNESS` | `JCF-HARNESS-*` | Reserved for later-parity manifest cases |
| `JCF-EVALUATION` | `JCF-EVALUATION-*` | Reserved for later-parity manifest cases |
| `JCF-TELEMETRY` | `JCF-TELEMETRY-*` | Reserved for later-parity manifest cases |
| `JCF-INTEGRATIONS` | `JCF-INTEGRATIONS-*` | Reserved for later-parity manifest cases |
| `JCF-SAMPLES` | `JCF-SAMPLES-*` | Reserved for later-parity manifest cases |

---

## 1. Core Abstractions

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Agent interface / base | `Microsoft.Agents.AI.Abstractions` · `AIAgent` | `agent-framework-core` · `Agent`, `BaseAgent`, `RawAgent`, `SupportsAgentRun` | `agent-framework-agents` · `com.microsoft.agents.agents.Agent` / `BaseAgent` | `dotnet/tests/Microsoft.Agents.AI.UnitTests` · `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-AGENTS-001` | `initial-scope` |
| Session / state | `Microsoft.Agents.AI.Abstractions` · `AgentSession`, `AgentSessionStateBag` | `agent-framework-core` · `AgentSession`, `SessionStore`, `SessionContext` | `agent-framework-agents` · `com.microsoft.agents.agents.AgentSession`, `SessionStore` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-SESSIONS-001` | `initial-scope` |
| Run options / response | `Microsoft.Agents.AI.Abstractions` · `AgentRunOptions`, `AgentResponse<T>`, `AgentResponseUpdate` | `agent-framework-core` · `AgentResponse`, `AgentResponseUpdate`, `AgentRunInputs` | `agent-framework-core` · `RunOptions`, response/update models (implemented); agent runtime integration remains in `agent-framework-agents` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-CORE-002`, `JCF-CORE-003` | `initial-scope` |
| Chat message / content | `Microsoft.Extensions.AI.Abstractions` (external) · `ChatMessage`, `AIContent` | `agent-framework-core` · `Message`, `Content`, `Role` | `agent-framework-core` · `Message`, sealed `Content` hierarchy (implemented) | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-CORE-001` | `initial-scope` |
| Context providers | `Microsoft.Agents.AI.Abstractions` · `AIContextProvider`, `ChatHistoryProvider`, `MessageAIContextProvider`, `InMemoryChatHistoryProvider` | `agent-framework-core` · `ContextProvider`, `HistoryProvider`, `InMemoryHistoryProvider`, `FileHistoryProvider` | `agent-framework-agents` · `com.microsoft.agents.agents.ContextProvider`, `HistoryProvider`, `InMemoryHistoryProvider` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-AGENTS-002` | `initial-scope` |
| Run context / metadata | `Microsoft.Agents.AI.Abstractions` · `AgentRunContext`, `AIAgentMetadata`, `AIContext` | `agent-framework-core` · `AgentContext`, `SessionContext` | `agent-framework-agents` · `com.microsoft.agents.agents.AgentRunContext`, `AgentMetadata` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-AGENTS-002` | `initial-scope` |
| Structured output | `Microsoft.Agents.AI.Abstractions` · `AIAgentStructuredOutput` | `agent-framework-core` · `ChatOptions.response_format` | `agent-framework-core` · `StructuredOutputOptions` | `JCF-CORE` | `later-parity` |
| Delegating agent | `Microsoft.Agents.AI.Abstractions` · `DelegatingAIAgent` | `agent-framework-core` · `BaseAgent` (subclass) | `agent-framework-agents` · `com.microsoft.agents.agents.DelegatingAgent` | `JCF-AGENTS` | `later-parity` |
| Additional properties / extensions | `Microsoft.Agents.AI.Abstractions` · `AdditionalPropertiesExtensions`, `AgentSessionExtensions`, `ChatMessageExtensions`, `AgentResponseExtensions` | `agent-framework-core` · `add_usage_details`, `normalize_messages` | `agent-framework-core` neutral model helpers + `agent-framework-agents` session helpers | `JCF-CORE` | `later-parity` |

**Key source references**

- `.NET`: [`dotnet/src/Microsoft.Agents.AI.Abstractions/`](../../dotnet/src/Microsoft.Agents.AI.Abstractions/)
- **Python**: [`python/packages/core/agent_framework/__init__.pyi`](../../python/packages/core/agent_framework/__init__.pyi) (canonical public API surface); [`python/packages/core/agent_framework/_agents.py`](../../python/packages/core/agent_framework/_agents.py)

---

## 2. Chat Client / Provider Abstraction

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Chat client interface | `Microsoft.Extensions.AI` (external) · `IChatClient`, `FunctionInvokingChatClient` | `agent-framework-core` · `BaseChatClient`, `SupportsChatGetResponse` | `agent-framework-agents` · `com.microsoft.agents.agents.ChatClient` (interface) | `dotnet/tests/Microsoft.Agents.AI.UnitTests`; `JCF-AGENTS-001` | `initial-scope` |
| Embedding client interface | `Microsoft.Extensions.AI` (external) · `IEmbeddingGenerator` | `agent-framework-core` · `BaseEmbeddingClient`, `SupportsGetEmbeddings` | `agent-framework-core` · `EmbeddingClient` (interface) | `JCF-CORE` | `later-parity` |
| Tool capability contracts | `Microsoft.Agents.AI.Abstractions` · `AITool`, `AIFunction` | `agent-framework-core` · `SupportsCodeInterpreterTool`, `SupportsFileSearchTool`, `SupportsImageGenerationTool`, `SupportsMCPTool`, `SupportsShellTool`, `SupportsWebSearchTool` | `agent-framework-tools` · `com.microsoft.agents.tools.Tool`, `ToolCapability` (implemented) | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-TOOLS-001` | `initial-scope` |
| Chat options / finish reason | `Microsoft.Extensions.AI` (external) | `agent-framework-core` · `ChatOptions`, `FinishReason`, `FinishReasonLiteral`, `UsageDetails` | `agent-framework-core` · `ChatOptions`, `FinishReason`, `UsageDetails` (implemented) | `JCF-CORE-004` | `initial-scope` |
| Streaming responses | `Microsoft.Extensions.AI` (external) · streaming APIs | `agent-framework-core` · `ResponseStream`, `ChatResponseUpdate`, `AgentResponseUpdate` | `agent-framework-core` update models and aggregation (implemented); `agent-framework-agents` publisher runtime remains pending | `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests`; `JCF-CORE-002`, `JCF-CORE-005`, `JCF-TOOLS-006` | `initial-scope` |

**Key source references**

- `.NET`: [`dotnet/src/Microsoft.Agents.AI/`](../../dotnet/src/Microsoft.Agents.AI/) (core + DI extensions, `ChatClientAgent`)
- **Python**: [`python/packages/core/agent_framework/_clients.py`](../../python/packages/core/agent_framework/_clients.py); [`python/packages/core/agent_framework/_types.py`](../../python/packages/core/agent_framework/_types.py)

---

## 3. Tool System

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Function tool / annotation | `Microsoft.Extensions.AI` · `AIFunction`, `AITool` | `agent-framework-core` · `FunctionTool`, `tool` decorator, `FunctionInvocationLayer`, `FunctionInvocationConfiguration` | `agent-framework-tools` · `com.microsoft.agents.tools.FunctionTool`, `FunctionInvocationLoop`, `@ToolMethod` (implemented) | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; [`docs/specs/004-python-function-calling-loop.md`](../specs/004-python-function-calling-loop.md); `JCF-TOOLS-002`, `JCF-TOOLS-003`, `JCF-TOOLS-004`, `JCF-TOOLS-005`, `JCF-TOOLS-006`, `JCF-TOOLS-009`, `JCF-TOOLS-012` | `initial-scope` |
| Tool mode / normalization | — | `agent-framework-core` · `ToolMode`, `normalize_tools`, `validate_tools` | `agent-framework-tools` · `com.microsoft.agents.tools.ToolMode`, `FunctionTools.normalize` (implemented) | `JCF-TOOLS-001` | `initial-scope` |
| Shell tool | `Microsoft.Agents.AI.Tools.Shell` | `agent-framework-core` · `SupportsShellTool` | `agent-framework-tools-shell` · `com.microsoft.agents.tools.shell` | `JCF-TOOLS` | `later-parity` |
| MCP tools (client) | `Microsoft.Agents.AI.Mcp` · `McpTaskOptions`, `TaskAwareMcpClientAIFunction`, MCP Skills | `agent-framework-core` · `MCPStdioTool`, `MCPStreamableHTTPTool`, `MCPWebsocketTool`, `MCPTaskOptions`, `SamplingApprovalCallback` | `agent-framework-mcp` · `com.microsoft.agents.protocols.mcp` | `dotnet/tests/Microsoft.Agents.AI.UnitTests` | `later-parity` |

**Key source references**

- `.NET`: [`dotnet/src/Microsoft.Agents.AI.Tools.Shell/`](../../dotnet/src/Microsoft.Agents.AI.Tools.Shell/); [`dotnet/src/Microsoft.Agents.AI.Mcp/`](../../dotnet/src/Microsoft.Agents.AI.Mcp/)
- **Python**: [`python/packages/core/agent_framework/_tools.py`](../../python/packages/core/agent_framework/_tools.py); [`python/packages/core/agent_framework/_mcp.py`](../../python/packages/core/agent_framework/_mcp.py)

---

## 4. Middleware Pipeline

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Agent middleware | `Microsoft.Agents.AI.Abstractions` (via `AIAgentExtensions`) | `agent-framework-core` · `AgentMiddleware`, `AgentMiddlewareLayer`, `agent_middleware` | `agent-framework-agents` · `com.microsoft.agents.agents.AgentMiddleware` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-AGENTS-003` | `initial-scope` |
| Chat middleware | `Microsoft.Extensions.AI` · `FunctionInvokingChatClient` | `agent-framework-core` · `ChatMiddleware`, `ChatMiddlewareLayer`, `chat_middleware` | `agent-framework-agents` · `com.microsoft.agents.agents.ChatMiddleware` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-AGENTS-003` | `initial-scope` |
| Function middleware | `Microsoft.Extensions.AI` · function-invocation pipeline | `agent-framework-core` · `FunctionMiddleware`, `FunctionInvocationContext`, `function_middleware` | `agent-framework-agents` · `com.microsoft.agents.agents.FunctionMiddleware` | `dotnet/tests/Microsoft.Agents.AI.Abstractions.UnitTests`; `JCF-AGENTS-003` | `initial-scope` |
| Middleware termination / context | — | `agent-framework-core` · `MiddlewareTermination`, `MiddlewareType`, `AgentContext`, `ChatContext`, `FunctionInvocationContext` | `agent-framework-agents` · `com.microsoft.agents.agents.MiddlewareContext` types | `JCF-AGENTS-003` | `initial-scope` |

**Key source references**

- **Python**: [`python/packages/core/agent_framework/_middleware.py`](../../python/packages/core/agent_framework/_middleware.py)
- Spec: [`docs/specs/004-python-function-calling-loop.md`](../specs/004-python-function-calling-loop.md)

---

## 5. Session Storage & History

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Session store interface | `Microsoft.Agents.AI.Hosting` · `AgentSessionStore`, `IsolationKeyScopedAgentSessionStore` | `agent-framework-core` · `SessionStore`, `FileSessionStore` | `agent-framework-agents` · `com.microsoft.agents.agents.SessionStore` | `dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests`; `JCF-SESSIONS-002` | `initial-scope` |
| In-memory session store | `Microsoft.Agents.AI.Hosting` · `NoopAgentSessionStore` + in-memory variant in Foundry.Hosting | `agent-framework-core` · `InMemoryHistoryProvider` | `agent-framework-agents` · `com.microsoft.agents.agents.InMemorySessionStore` | `JCF-SESSIONS-003` | `initial-scope` |
| Cosmos NoSQL history | `Microsoft.Agents.AI.CosmosNoSql` · `CosmosChatHistoryProvider`, `CosmosCheckpointStore` | `agent-framework-azure-cosmos` · (separate package) | `agent-framework-azure-cosmos` · `com.microsoft.agents.storage.cosmos` using `com.azure:azure-cosmos` | `dotnet/tests/Microsoft.Agents.AI.CosmosNoSql.UnitTests` | `later-parity` |
| Redis / Valkey history | `Microsoft.Agents.AI.Valkey` · `ValkeyChatHistoryProvider` | `agent-framework-redis` | `agent-framework-valkey` · `com.microsoft.agents.storage.valkey` using `io.valkey:valkey-glide` | `dotnet/tests/Microsoft.Agents.AI.Valkey.UnitTests` | `later-parity` |
| Message injection | — | `agent-framework-core` · `MessageInjectionMiddleware`, `enqueue_messages` | `agent-framework-agents` · `com.microsoft.agents.agents.MessageInjectionMiddleware` | `JCF-AGENTS` | `later-parity` |

**Key source references**

- `.NET`: [`dotnet/src/Microsoft.Agents.AI.CosmosNoSql/`](../../dotnet/src/Microsoft.Agents.AI.CosmosNoSql/); [`dotnet/src/Microsoft.Agents.AI.Valkey/`](../../dotnet/src/Microsoft.Agents.AI.Valkey/)
- **Python**: [`python/packages/core/agent_framework/_sessions.py`](../../python/packages/core/agent_framework/_sessions.py)

---

## 6. Workflow Engine

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Workflow core / builder | `Microsoft.Agents.AI.Workflows` · `Workflow`, `WorkflowBuilder`, `WorkflowSession`, `WorkflowHostAgent` | `agent-framework-core` · `Workflow`, `WorkflowBuilder`, `WorkflowAgent`, `WorkflowContext` | `agent-framework-workflows` · `com.microsoft.agents.workflows.Workflow`, `WorkflowBuilder` | `dotnet/tests/Microsoft.Agents.AI.Workflows.Generators.UnitTests`; `JCF-WORKFLOWS-001` | `initial-scope` |
| Edges / graph | `Microsoft.Agents.AI.Workflows` · `Edge`, `FanInEdgeData`, `FanOutEdgeData`, `SwitchBuilder` | `agent-framework-core` · `Edge`, `Case`, `Default`, `FanInEdgeGroup`, `FanOutEdgeGroup`, `SwitchCaseEdgeGroup` | `agent-framework-workflows` · `com.microsoft.agents.workflows.Edge` hierarchy | `JCF-WORKFLOWS-002` | `initial-scope` |
| Executor / function executor | `Microsoft.Agents.AI.Workflows` · `Executor`, `FunctionExecutor`, `AggregatingExecutor` | `agent-framework-core` · `Executor`, `FunctionExecutor`, `handler`, `executor` | `agent-framework-workflows` · `com.microsoft.agents.workflows.Executor`, `FunctionExecutor` | `JCF-WORKFLOWS-001`, `JCF-WORKFLOWS-003` | `initial-scope` |
| Sequential / group chat / Magentic | `Microsoft.Agents.AI.Workflows` · `SequentialWorkflowBuilder`, `GroupChatWorkflowBuilder`, `MagenticWorkflowBuilder` | `agent-framework-orchestrations` | `agent-framework-orchestrations` · `com.microsoft.agents.orchestrations` | `JCF-ORCHESTRATIONS` | `later-parity` |
| Functional workflow | — | `agent-framework-core` · `FunctionalWorkflow`, `FunctionalWorkflowAgent`, `step`, `workflow`, `RunContext` | `agent-framework-workflows` · `com.microsoft.agents.workflows.FunctionalWorkflow` | `JCF-WORKFLOWS` | `later-parity` |
| Checkpoint storage / resume | `Microsoft.Agents.AI.Workflows` · `CheckpointManager`, `CheckpointInfo` | `agent-framework-core` · `CheckpointStorage`, `FileCheckpointStorage`, `InMemoryCheckpointStorage`, `WorkflowCheckpoint` | `agent-framework-workflows` · `com.microsoft.agents.workflows.CheckpointStorage`, `CheckpointCommit` | `JCF-WORKFLOWS-004`, `JCF-WORKFLOWS-005`; [`docs/decisions/0038-java-state-serialization-and-compatibility.md`](../decisions/0038-java-state-serialization-and-compatibility.md) | `initial-scope` |
| Workflow events | `Microsoft.Agents.AI.Workflows` · `WorkflowEvent`, `WorkflowOutputEvent`, `ExecutorCompletedEvent`, `SuperStepCompletedEvent` | `agent-framework-core` · `WorkflowEvent`, `WorkflowEventType`, `WorkflowRunState` | `agent-framework-workflows` · `com.microsoft.agents.workflows.WorkflowEvent` hierarchy | `JCF-WORKFLOWS-001`, `JCF-WORKFLOWS-003`, `JCF-WORKFLOWS-004` | `initial-scope` |
| Visualization | `Microsoft.Agents.AI.Workflows` · `Visualization/` | `agent-framework-core` · `WorkflowViz` | `agent-framework-workflows` · `com.microsoft.agents.workflows.WorkflowViz` | `JCF-WORKFLOWS` | `later-parity` |
| Workflow validation | — | `agent-framework-core` · `validate_workflow_graph`, `WorkflowValidationError`, `EdgeDuplicationError`, `GraphConnectivityError`, `TypeCompatibilityError` | `agent-framework-workflows` · `com.microsoft.agents.workflows.WorkflowValidator` | `JCF-WORKFLOWS` | `later-parity` |

**Key source references**

- `.NET`: [`dotnet/src/Microsoft.Agents.AI.Workflows/`](../../dotnet/src/Microsoft.Agents.AI.Workflows/) (109 source files)
- **Python**: [`python/packages/core/agent_framework/_workflows/`](../../python/packages/core/agent_framework/_workflows/)

---

## 7. Declarative / YAML Agents

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Prompt agent factory | `Microsoft.Agents.AI.Declarative` · `PromptAgentFactory`, `AggregatorPromptAgentFactory` | `agent-framework-declarative` · YAML/JSON agent definitions | `agent-framework-declarative` | `dotnet/tests/Microsoft.Agents.AI.Workflows.Declarative.UnitTests` | `later-parity` |
| Declarative workflows (excluding PowerFx) | `Microsoft.Agents.AI.Workflows.Declarative` · `DeclarativeWorkflowBuilder`, MCP/Foundry sub-packages | — | `agent-framework-workflows-declarative` | `dotnet/tests/Microsoft.Agents.AI.Workflows.Declarative.UnitTests` | `later-parity` |
| PowerFx workflow expressions | `Microsoft.Agents.AI.Workflows.Declarative` · PowerFx expressions | — | `agent-framework-workflows-declarative-powerfx` (blocked pending a supported Java SDK/runtime) | `dotnet/tests/Microsoft.Agents.AI.Workflows.Declarative.UnitTests` | `sdk-gap` |

**Key source references**

- `.NET`: [`dotnet/src/Microsoft.Agents.AI.Declarative/`](../../dotnet/src/Microsoft.Agents.AI.Declarative/); [`dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/`](../../dotnet/src/Microsoft.Agents.AI.Workflows.Declarative/)
- **Python**: [`python/packages/declarative/`](../../python/packages/declarative/)

---

## 8. Hosting Infrastructure

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Hosting abstractions | `Microsoft.Agents.AI.Hosting` · `IHostedAgentBuilder`, `IHostedWorkflowBuilder`, `AgentSessionStore`, `WorkflowCatalog`, `SessionIsolationKeyProvider` | `agent-framework-hosting` · `AgentState`, session helpers | `agent-framework-hosting` · `com.microsoft.agents.hosting.HostedAgentBuilder` (uses `agents.SessionStore`) | `JCF-HOSTING` | `later-parity` |
| OpenAI Responses hosting | `Microsoft.Agents.AI.Hosting.OpenAI` · `OpenAIResponses`, `OpenAIResponsesMapOptions`, `OpenAIResponseRequestInfo` | `agent-framework-hosting-responses` | `agent-framework-hosting-openai` | `dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests` | `later-parity` |
| Generic HTTP / SSE / WebSocket hosting | framework-neutral behavior represented by `Microsoft.Agents.AI.Hosting.AspNetCore` | `agent-framework-hosting` (FastAPI / Starlette helpers) | Java hosting adapters under `com.microsoft.agents.hosting.<framework>` | `JCF-HOSTING-TRANSPORT` | `later-parity` |
| ASP.NET Core-specific route extensions | `Microsoft.Agents.AI.Hosting.AspNetCore` · `MapOpenAIResponses`, `MapA2A`, `MapAGUI` | — | no Java counterpart | `JCF-HOSTING-ASPNET-SURFACE` | `n/a` |
| A2A hosting | `Microsoft.Agents.AI.Hosting.A2A` + `AspNetCore` · `A2AAgent`, `A2AAgentSession` | `agent-framework-hosting-a2a` · `A2AExecutor` | `agent-framework-hosting-a2a` · protocol types in `com.microsoft.agents.protocols.a2a` | `dotnet/tests/Microsoft.Agents.AI.Hosting.A2A.UnitTests` | `later-parity` |
| AG-UI hosting | `Microsoft.Agents.AI.Hosting.AGUI.AspNetCore` · `AGUIEndpointRouteBuilderExtensions`, `AGUIServerSentEventsResult` | `agent-framework-ag-ui` · `AgentFrameworkAgent`, `AGUIHttpService`, `add_agent_framework_fastapi_endpoint` | `agent-framework-hosting-agui` · protocol types in `com.microsoft.agents.protocols.agui` | `dotnet/tests/Microsoft.Agents.AI.Hosting.AGUI.AspNetCore.UnitTests` | `later-parity` |
| MCP hosting | `Microsoft.Agents.AI.Mcp` · `Skills/` | `agent-framework-hosting-mcp` · `AgentMCPTool`, `WorkflowMCPTool`, `mcp_to_run`, `mcp_from_run` | `agent-framework-hosting-mcp` · protocol types in `com.microsoft.agents.protocols.mcp` | `JCF-PROTOCOLS` | `later-parity` |
| Foundry hosting / session | `Microsoft.Agents.AI.Foundry.Hosting` · `HostedSessionContext`, `HostedCallContext`, `AgentSessionStore`, `FoundryToolboxService` | `agent-framework-foundry` (hosting portions) | `agent-framework-foundry-hosting` | `dotnet/tests/Microsoft.Agents.AI.Foundry.Hosting.UnitTests` | `later-parity` |
| Telegram hosting | — | `agent-framework-hosting-telegram` | `agent-framework-hosting-telegram` · `com.microsoft.agents.hosting.telegram` | `JCF-HOSTING` | `later-parity` |
| Dev UI hosting | `Microsoft.Agents.AI.DevUI` + `Aspire.Hosting.AgentFramework.DevUI` | `agent-framework-devui` | `agent-framework-devui` | `dotnet/tests/Microsoft.Agents.AI.DevUI.UnitTests` | `later-parity` |

**Spec references**: [`docs/specs/002-python-hosting-channels.md`](../specs/002-python-hosting-channels.md); [`docs/specs/003-dotnet-hosting-protocol-helpers.md`](../specs/003-dotnet-hosting-protocol-helpers.md)

---

## 9. LLM Providers

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| OpenAI / Azure OpenAI | `Microsoft.Agents.AI.OpenAI` · `ChatClient/`, `Extensions/` | `agent-framework-openai` (built into `core`) | `agent-framework-openai` · `com.microsoft.agents.providers.openai` | `dotnet/tests/OpenAIResponse.IntegrationTests`; `JCF-PROVIDERS-001` | `initial-scope` |
| Foundry / Azure AI | `Microsoft.Agents.AI.Foundry` · `FoundryAgent`, `FoundryChatClient`, `FoundryAITool` | `agent-framework-foundry` | `agent-framework-foundry` · `com.microsoft.agents.providers.foundry` using `com.azure:azure-ai-projects` | `dotnet/tests/Microsoft.Agents.AI.Foundry.UnitTests`; `JCF-PROVIDERS-002` | `initial-scope` |
| Anthropic | `Microsoft.Agents.AI.Anthropic` | `agent-framework-anthropic` | `agent-framework-anthropic` · `com.microsoft.agents.providers.anthropic` using `com.anthropic:anthropic-java` | `dotnet/tests/Microsoft.Agents.AI.Anthropic.UnitTests`; `dotnet/tests/AnthropicChatCompletion.IntegrationTests` | `later-parity` |
| AWS Bedrock | — | `agent-framework-bedrock` | `agent-framework-bedrock` · `com.microsoft.agents.providers.bedrock` using AWS SDK for Java v2 `software.amazon.awssdk:bedrockruntime` | `JCF-PROVIDERS` | `later-parity` |
| Gemini | — | `agent-framework-gemini` | `agent-framework-gemini` · `com.microsoft.agents.providers.gemini` using `com.google.genai:google-genai` | `JCF-PROVIDERS` | `later-parity` |
| Mistral | — | `agent-framework-mistral` | `agent-framework-mistral` · `com.microsoft.agents.providers.mistral` using a standard HTTP/JSON adapter | `JCF-PROVIDERS` | `later-parity` |
| Ollama | — | `agent-framework-ollama` | `agent-framework-ollama` · `com.microsoft.agents.providers.ollama` | `JCF-PROVIDERS` | `later-parity` |
| Foundry Local | — | `agent-framework-foundry-local` | `agent-framework-foundry-local` · `com.microsoft.agents.providers.foundrylocal` | `JCF-PROVIDERS` | `later-parity` |
| Azure AI Persistent (OpenAI Assistants) | `Microsoft.Agents.AI.AzureAI.Persistent` | — | `agent-framework-azureai-persistent` · `com.microsoft.agents.providers.azureaipersistent` using `com.azure:azure-ai-agents-persistent` | `dotnet/tests/Microsoft.Agents.AI.AzureAI.Persistent.UnitTests`; `dotnet/tests/AzureAIAgentsPersistent.IntegrationTests` | `later-parity` |
| Claude Agent SDK integration | — | `agent-framework-claude` | `agent-framework-claude` · `com.microsoft.agents.providers.claude` | `JCF-PROVIDERS` | `sdk-gap` |
| GitHub Copilot | `Microsoft.Agents.AI.GitHub.Copilot` | `agent-framework-github-copilot` | `agent-framework-github-copilot` · `com.microsoft.agents.providers.githubcopilot` | `dotnet/tests/Microsoft.Agents.AI.GitHub.Copilot.UnitTests` | `later-parity` |
| Copilot Studio | `Microsoft.Agents.AI.CopilotStudio` | `agent-framework-copilotstudio` | `agent-framework-copilotstudio` · `com.microsoft.agents.providers.copilotstudio` | `JCF-PROVIDERS` | `later-parity` |
| Hyperlight | `Microsoft.Agents.AI.Hyperlight` | `agent-framework-hyperlight` | `agent-framework-hyperlight` · `com.microsoft.agents.providers.hyperlight` | `dotnet/tests/Microsoft.Agents.AI.Hyperlight.UnitTests` | `sdk-gap` |

---

## 10. Memory & Search Integrations

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Mem0 memory | `Microsoft.Agents.AI.Mem0` | `agent-framework-mem0` | `agent-framework-mem0` | `dotnet/tests/Microsoft.Agents.AI.Mem0.UnitTests` | `later-parity` |
| Azure AI Search (RAG) | — | `agent-framework-azure-ai-search` | `agent-framework-azure-ai-search` · `com.microsoft.agents.storage.azureaisearch` using `com.azure:azure-search-documents` | `JCF-INTEGRATIONS` | `later-parity` |
| Azure Cosmos memory | — | `agent-framework-azure-cosmos-memory` | `agent-framework-azure-cosmos-memory` · `com.microsoft.agents.storage.cosmosmemory` using `com.azure:azure-cosmos` | `JCF-INTEGRATIONS` | `later-parity` |
| Azure Content Understanding | — | `agent-framework-azure-contentunderstanding` | `agent-framework-azure-contentunderstanding` · `com.microsoft.agents.providers.azurecontentunderstanding` using `com.azure:azure-ai-contentunderstanding` | `JCF-INTEGRATIONS` | `later-parity` |

---

## 11. Context Compaction

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Compaction strategies | — | `agent-framework-core` · `CompactionStrategy`, `ContextWindowCompactionStrategy`, `SlidingWindowStrategy`, `SummarizationStrategy`, `TruncationStrategy`, `SelectiveToolCallCompactionStrategy`, `ToolResultCompactionStrategy`, `TokenBudgetComposedStrategy` | `agent-framework-agents` · `com.microsoft.agents.agents.context.CompactionStrategy` hierarchy | `JCF-CONTEXT` | `later-parity` |
| Tokenizer protocol | — | `agent-framework-core` · `TokenizerProtocol`, `CharacterEstimatorTokenizer` | `agent-framework-agents` · `com.microsoft.agents.agents.context.Tokenizer` interface | `JCF-CONTEXT` | `later-parity` |
| Message group annotations | — | `agent-framework-core` · `annotate_message_groups`, `apply_compaction`, `included_messages` | `agent-framework-agents` · `com.microsoft.agents.agents.context.MessageGroupAnnotator` | `JCF-CONTEXT` | `later-parity` |

---

## 12. Skills System

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Skills sources / provider | — | `agent-framework-core` · `SkillsProvider`, `SkillsSource`, `InMemorySkillsSource`, `FileSkillsSource`, `MCPSkillsSource`, `AggregatingSkillsSource`, `CachingSkillsSource`, `DeduplicatingSkillsSource`, `FilteringSkillsSource`, `DelegatingSkillsSource` | `agent-framework-agents` · `com.microsoft.agents.agents.skills.SkillsProvider`, `SkillsSource` | `JCF-SKILLS` | `later-parity` |
| Skill types | — | `agent-framework-core` · `Skill`, `ClassSkill`, `InlineSkill`, `FileSkill`, `MCPSkill`, `SkillScript`, `SkillFrontmatter` | `agent-framework-agents` · `com.microsoft.agents.agents.skills.Skill` hierarchy | `JCF-SKILLS` | `later-parity` |
| MCP skill templates (dotnet) | `Microsoft.Agents.AI.Mcp` · `Skills/` | — | `agent-framework-mcp` · `com.microsoft.agents.protocols.mcp.MCPSkillsSource` | `JCF-SKILLS` | `later-parity` |

ADR reference: [`docs/decisions/0021-agent-skills-design.md`](../decisions/0021-agent-skills-design.md)

---

## 13. Harness (Autonomous Agent Loop)

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Harness agent | `Microsoft.Agents.AI.Harness` · `HarnessAgent`, `HarnessAgentOptions` | `agent-framework-core` · `create_harness_agent`, `AgentLoopMiddleware` | `agent-framework-harness` | `dotnet/tests/Microsoft.Agents.AI.Harness.UnitTests` | `later-parity` |
| Background agents | — | `agent-framework-core` · `BackgroundAgentsProvider`, `BackgroundTaskInfo`, `BackgroundTaskStatus` | `agent-framework-harness` · `BackgroundAgentsProvider` | `JCF-HARNESS` | `later-parity` |
| File access / memory / todo | — | `agent-framework-core` · `FileAccessProvider`, `FileMemoryProvider`, `TodoProvider`, `MemoryStore`, `MemoryContextProvider` | `agent-framework-harness` · harness providers | `JCF-HARNESS` | `later-parity` |
| Tool approval / resume | — | `agent-framework-core` · `ToolApprovalMiddleware`, `ToolApprovalRule`, `ToolApprovalState` | `agent-framework-tools` approval/resume models and loop enforcement (implemented); `agent-framework-agents` middleware integration remains pending | `JCF-TOOLS-007`, `JCF-TOOLS-008`, `JCF-TOOLS-010`, `JCF-TOOLS-011`; [`docs/decisions/0006-userapproval.md`](../decisions/0006-userapproval.md) | `initial-scope` |

ADR reference: [`docs/decisions/0006-userapproval.md`](../decisions/0006-userapproval.md)

---

## 14. Evaluation

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Agent evaluation | `Microsoft.Agents.AI.Foundry` · `Evaluation/` | `agent-framework-core` · `evaluate_agent`, `evaluate_workflow`, `Evaluator`, `LocalEvaluator`, `EvalItem`, `EvalResults`, `EvalScoreResult` | `agent-framework-evaluation` | `dotnet/tests/Microsoft.Agents.AI.Foundry.UnitTests` | `later-parity` |
| Check helpers | — | `agent-framework-core` · `keyword_check`, `tool_called_check`, `tool_calls_present`, `tool_call_args_match` | `agent-framework-evaluation` · check helpers | `JCF-EVALUATION` | `later-parity` |
| Conversation splitter | — | `agent-framework-core` · `ConversationSplitter`, `ConversationSplit` | `agent-framework-evaluation` · `ConversationSplitter` | `JCF-EVALUATION` | `later-parity` |
| Foundry evals integration | `Microsoft.Agents.AI.Foundry` · `Evaluation/` | — | `agent-framework-foundry` evaluation adapter using `com.azure:azure-ai-projects` | `JCF-EVALUATION` | `later-parity` |

ADR reference: [`docs/decisions/0023-foundry-evals-integration.md`](../decisions/0023-foundry-evals-integration.md)

---

## 15. Feature Lifecycle & Telemetry

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Feature stage decorators | — | `agent-framework-core` · `ExperimentalFeature`, `ReleaseCandidateFeature` | `agent-framework-core` · `@Experimental`, `@ReleaseCandidate` annotations | `JCF-TELEMETRY` | `later-parity` |
| User-agent telemetry | — | `agent-framework-core` · `AGENT_FRAMEWORK_USER_AGENT`, `prepend_agent_framework_to_user_agent`, `USER_AGENT_KEY` | `agent-framework-observability` · `UserAgentUtil` | `JCF-TELEMETRY` | `later-parity` |
| Feature usage bitmask | `Microsoft.Agents.AI` · feature-usage telemetry | `agent-framework-core` · `APP_INFO` | `agent-framework-observability` · `FeatureUsageRegistry` | `JCF-TELEMETRY` | `later-parity` |

Spec: [`docs/specs/004-feature-usage-telemetry.md`](../specs/004-feature-usage-telemetry.md); [`docs/specs/feature-usage-bit-registry.md`](../specs/feature-usage-bit-registry.md)

---

## 16. Infrastructure / Azure-Specific

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Purview data governance | `Microsoft.Agents.AI.Purview` | `agent-framework-purview` | `agent-framework-purview` | `JCF-INTEGRATIONS` | `later-parity` |
| CodeAct / LocalCodeAct | `Microsoft.Agents.AI.LocalCodeAct` | `agent-framework-monty` (CodeAct alpha) | `agent-framework-codeact` | `dotnet/tests/Microsoft.Agents.AI.LocalCodeAct.UnitTests` | `later-parity` |
| Orchestrations | — | `agent-framework-orchestrations` | `agent-framework-orchestrations` · `com.microsoft.agents.orchestrations` | `JCF-ORCHESTRATIONS` | `later-parity` |
| Tools (general) | — | `agent-framework-tools` | `agent-framework-tools` · `com.microsoft.agents.tools` | `JCF-TOOLS` | `later-parity` |
| Lab / experimental | — | `agent-framework-lab` | no supported Java publication | `JCF-INTEGRATIONS` | `n/a` |

---

## 17. Samples & Testing Utilities

| Area / Group | .NET project | Python package | Proposed Java module | Expected contract-test source | Status |
|---|---|---|---|---|---|
| Sample catalog | `dotnet/samples/` | `python/samples/` | `java/samples/` | `JCF-SAMPLES` | `later-parity` |
| Integration test harness | `dotnet/tests/` integration projects | `python/tests/` | `agent-framework-conformance` (non-published test support) | `JCF-HOSTING-001`; `java/agent-framework-conformance/src/test/java/com/microsoft/agents/conformance/ConformanceFixtureValidationTest.java` | `initial-scope` |

---

## SDK Classification Audit

| Integration | Java library evidence | Classification result |
|---|---|---|
| AWS Bedrock | [AWS SDK for Java 2.x Bedrock Runtime](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_bedrock-runtime_code_examples.html) · `software.amazon.awssdk:bedrockruntime` | `later-parity` |
| Gemini | [Google Gen AI Java SDK](https://github.com/googleapis/java-genai) · `com.google.genai:google-genai` | `later-parity` |
| Anthropic Messages | [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) · `com.anthropic:anthropic-java` | `later-parity`; this does not replace the Claude Agent SDK |
| Cosmos DB, Azure AI Search, Azure AI Persistent, Content Understanding, Foundry evaluations | [Azure SDK for Java package index](https://learn.microsoft.com/azure/developer/java/sdk/azure-sdk-library-package-index#all-libraries) · `com.azure:azure-cosmos`, `com.azure:azure-search-documents`, `com.azure:azure-ai-agents-persistent`, `com.azure:azure-ai-contentunderstanding`, `com.azure:azure-ai-projects` | `later-parity` |
| Redis / Valkey | [Valkey Java client](https://github.com/valkey-io/valkey-java) and [GLIDE for Valkey Java](https://github.com/valkey-io/valkey-glide/tree/main/java) | `later-parity` |
| Mistral | [Mistral HTTP API](https://docs.mistral.ai/api/) provides a standards-based adapter path even without an official Java SDK | `later-parity` |
| PowerFx | [Power Fx overview](https://learn.microsoft.com/power-platform/power-fx/overview) documents the language, but no supported Java SDK/runtime is available | `sdk-gap` |
| Claude Agent SDK | The Java Messages SDK above is not a Claude Agent SDK runtime | `sdk-gap` |
| Hyperlight | No supported Java runtime/SDK counterpart is available | `sdk-gap` |

---

## Status Summary

| Status | Count |
|---|---|
| `initial-scope` | 27 area/group rows |
| `later-parity` | 58 area/group rows |
| `sdk-gap` | 3 area/group rows |
| `n/a` | 2 area/group rows |

---

## Package Inventory Appendix

### .NET — All Source Projects

| Project | Layer | Status |
|---|---|---|
| `Microsoft.Agents.AI.Abstractions` | Core abstractions | `initial-scope` |
| `Microsoft.Agents.AI` | Core + DI | `initial-scope` |
| `Microsoft.Agents.AI.OpenAI` | Provider — OpenAI | `initial-scope` |
| `Microsoft.Agents.AI.Foundry` | Provider — Foundry/Azure AI | `initial-scope` |
| `Microsoft.Agents.AI.Anthropic` | Provider — Anthropic | `later-parity` |
| `Microsoft.Agents.AI.AzureAI.Persistent` | Provider — Assistants API; Java SDK `com.azure:azure-ai-agents-persistent` | `later-parity` |
| `Microsoft.Agents.AI.GitHub.Copilot` | Provider — GitHub Copilot | `later-parity` |
| `Microsoft.Agents.AI.CopilotStudio` | Provider — Copilot Studio | `later-parity` |
| `Microsoft.Agents.AI.Hyperlight` | Provider — Hyperlight | `sdk-gap` |
| `Microsoft.Agents.AI.Workflows` | Workflow engine | `initial-scope` |
| `Microsoft.Agents.AI.Workflows.Declarative` | Declarative workflows | `later-parity` |
| `Microsoft.Agents.AI.Workflows.Declarative.Foundry` | Declarative + Foundry | `later-parity` |
| `Microsoft.Agents.AI.Workflows.Declarative.Mcp` | Declarative + MCP | `later-parity` |
| `Microsoft.Agents.AI.Workflows.Generators` | Workflow code-gen | `later-parity` |
| `Microsoft.Agents.AI.Hosting` | Hosting abstractions | `later-parity` |
| `Microsoft.Agents.AI.Hosting.AspNetCore` | ASP.NET Core-specific hosting surface | `n/a` |
| `Microsoft.Agents.AI.Hosting.OpenAI` | OpenAI Responses hosting | `later-parity` |
| `Microsoft.Agents.AI.Hosting.A2A` | A2A hosting | `later-parity` |
| `Microsoft.Agents.AI.Hosting.A2A.AspNetCore` | A2A HTTP routing | `n/a` |
| `Microsoft.Agents.AI.Hosting.AGUI.AspNetCore` | AG-UI HTTP routing | `n/a` |
| `Microsoft.Agents.AI.AGUI` | AG-UI models | `later-parity` |
| `Microsoft.Agents.AI.A2A` | A2A client/server | `later-parity` |
| `Microsoft.Agents.AI.Mcp` | MCP tools + skills | `later-parity` |
| `Microsoft.Agents.AI.Foundry.Hosting` | Foundry hosted sessions | `later-parity` |
| `Microsoft.Agents.AI.Harness` | Autonomous agent loop | `later-parity` |
| `Microsoft.Agents.AI.Declarative` | Prompt agents | `later-parity` |
| `Microsoft.Agents.AI.CosmosNoSql` | Cosmos storage; Java SDK `com.azure:azure-cosmos` | `later-parity` |
| `Microsoft.Agents.AI.Valkey` | Redis/Valkey storage; Java client `io.valkey:valkey-glide` | `later-parity` |
| `Microsoft.Agents.AI.Mem0` | Mem0 memory | `later-parity` |
| `Microsoft.Agents.AI.Purview` | Purview governance | `later-parity` |
| `Microsoft.Agents.AI.LocalCodeAct` | CodeAct executor | `later-parity` |
| `Microsoft.Agents.AI.Tools.Shell` | Shell tool | `later-parity` |
| `Microsoft.Agents.AI.DevUI` | Developer UI | `later-parity` |
| `Aspire.Hosting.AgentFramework.DevUI` | Aspire dev UI | `n/a` |

### Python — All Packages

| Package | Layer | Status |
|---|---|---|
| `agent-framework-core` | Core + built-in OpenAI | `initial-scope` |
| `agent-framework-openai` | Provider — OpenAI (separate pkg) | `initial-scope` |
| `agent-framework-foundry` | Provider — Foundry/Azure AI | `initial-scope` |
| `agent-framework-foundry-hosting` | Foundry hosting | `later-parity` |
| `agent-framework-anthropic` | Provider — Anthropic | `later-parity` |
| `agent-framework-bedrock` | Provider — AWS Bedrock; AWS SDK for Java v2 `bedrockruntime` | `later-parity` |
| `agent-framework-claude` | Provider — Claude Agent SDK (no Java Agent SDK equivalent) | `sdk-gap` |
| `agent-framework-gemini` | Provider — Gemini; Java SDK `com.google.genai:google-genai` | `later-parity` |
| `agent-framework-mistral` | Provider — Mistral via standard HTTP/JSON adapter | `later-parity` |
| `agent-framework-ollama` | Provider — Ollama | `later-parity` |
| `agent-framework-foundry-local` | Provider — Foundry Local | `later-parity` |
| `agent-framework-github-copilot` | Provider — GitHub Copilot | `later-parity` |
| `agent-framework-copilotstudio` | Provider — Copilot Studio | `later-parity` |
| `agent-framework-hyperlight` | Provider — Hyperlight | `sdk-gap` |
| `agent-framework-a2a` | Protocol — A2A | `later-parity` |
| `agent-framework-hosting-a2a` | A2A hosting helpers | `later-parity` |
| `agent-framework-ag-ui` | Protocol — AG-UI | `later-parity` |
| `agent-framework-hosting-mcp` | MCP hosting helpers | `later-parity` |
| `agent-framework-hosting-responses` | OpenAI Responses hosting | `later-parity` |
| `agent-framework-hosting-telegram` | Telegram hosting | `later-parity` |
| `agent-framework-hosting` | Generic HTTP/SSE/WebSocket hosting helpers | `later-parity` |
| `agent-framework-chatkit` | ChatKit integration | `later-parity` |
| `agent-framework-azure-cosmos` | Cosmos history; Java SDK `com.azure:azure-cosmos` | `later-parity` |
| `agent-framework-azure-cosmos-memory` | Cosmos memory; Java SDK `com.azure:azure-cosmos` | `later-parity` |
| `agent-framework-azure-ai-search` | Azure AI Search RAG; Java SDK `com.azure:azure-search-documents` | `later-parity` |
| `agent-framework-azure-contentunderstanding` | Content Understanding; Java SDK `com.azure:azure-ai-contentunderstanding` | `later-parity` |
| `agent-framework-redis` | Redis/Valkey storage; Java client `io.valkey:valkey-glide` | `later-parity` |
| `agent-framework-mem0` | Mem0 memory | `later-parity` |
| `agent-framework-purview` | Purview governance | `later-parity` |
| `agent-framework-declarative` | YAML/JSON agents | `later-parity` |
| `agent-framework-devui` | Developer UI | `later-parity` |
| `agent-framework-monty` | CodeAct alpha | `later-parity` |
| `agent-framework-orchestrations` | Orchestrations | `later-parity` |
| `agent-framework-tools` | General tools | `later-parity` |
| `agent-framework-lab` | Experimental | `n/a` |
