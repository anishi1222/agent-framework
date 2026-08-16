# Azure AI Agents Persistent

`agent-framework-azure-ai-persistent` is a low-level adapter over
`com.azure:azure-ai-agents-persistent:1.0.0-beta.2`. That SDK and its default
`2025-05-15-preview` service API are preview surfaces; this module does not claim GA support.

The module exposes framework-owned immutable agent, thread, message, attachment, run, usage,
required-action, page, and event types. `AzureAIPersistentClient` supports agent CRUD, thread
create/get/delete, messages, run create/get/list/cancel, tool-output submission, native SSE
streaming, and bounded asynchronous polling with cancellation. `AzureAIPersistentAgent` adapts an
existing service agent to the framework `Agent` contract and stores agent/thread/run IDs plus stable
submitted message IDs in `AgentSession` state.

`startRun` and tool-output start-and-await operations request best-effort service cancellation when
their local poller is cancelled or times out. Public `awaitRunAsync` is observe-only by default:
cancellation and timeout stop local polling without changing the existing service run. Use its
explicit `cancelRemoteOnTimeoutOrCancellation` overload only when the observer also owns the remote
run lifecycle.

Supported provider tools in the pinned SDK are code interpreter, file search, function, and
anonymous-auth OpenAPI tools. MCP has no model in this SDK version and is represented explicitly as
unsupported. Requires-action function calls are surfaced for caller approval/execution and continued
through `submitToolOutputsAsync`; the service has no separate input-required or approval endpoint.

The adapter never auto-deletes caller agents, threads, files, or vector stores. A scheduler created
by the adapter is closed deterministically; caller-provided authentication providers and schedulers
remain caller-owned. Thread and run identifiers are continuation keys, not authorization evidence;
hosts must bind them to authenticated principals.

## Public API

- `AzureAIPersistentClient` and `AzureAIPersistentClientOptions` expose agent CRUD, thread
  create/get/delete, message create/list, run create/get/list/cancel/await, tool-output continuation,
  and the SDK's native SSE run stream.
- `AzureAIPersistentAgent` adapts an existing service agent to the generic `Agent<Void>` contract.
- `PersistentAgentDefinition`, `PersistentThread`, `PersistentMessage`, `PersistentRun`,
  `PersistentRequiredAction`, `PersistentToolCall`, `PersistentToolOutput`, and `PersistentPage<T>`
  are immutable framework-owned service models. No Azure SDK type appears in a public signature.

## Authentication and security

Clients use a caller-owned `AzureAuthenticationProvider` and request
`https://ai.azure.com/.default`. Production applications should use
`AzureAuthenticationProviders.productionDefaultCredential()` or managed identity with least
privilege. Project endpoints must be HTTPS
`https://<resource>.services.ai.azure.com/api/projects/<project>` URIs without query, fragment, or
userinfo. SDK HTTP body logging is disabled and service errors retain only sanitized status,
request ID, retry delay, and code.

This adapter performs data-plane calls only. It never provisions projects, deployments, agents,
files, vector stores, or role assignments, and its tests use injected Azure SDK HTTP pipelines
without live Azure calls.
