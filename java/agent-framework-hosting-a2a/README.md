# Agent Framework A2A Hosting for Java

`agent-framework-hosting-a2a` exposes framework Agents and Workflows through A2A v1 task operations
and a focused embedded JSON-RPC/SSE host.

## Hosting model

- `A2AService` owns task lifecycle, finite/streaming operations, resubscription, cancellation,
  idempotent message IDs, card retrieval, pagination, and push-configuration CRUD.
- `A2AAgentExecutor` and `A2AWorkflowExecutor` map framework execution to status and artifact events.
- `A2ATaskStore` and `A2APushNotificationConfigStore` always receive `A2APrincipal`, whose
  `principalId` and `isolationKey` form the storage boundary.
- `InMemoryA2ATaskStore` and `InMemoryA2APushNotificationConfigStore` are bounded, process-memory
  implementations that fail closed when full.
- `A2AHttpServer` serves `/.well-known/agent-card.json` plus JSON-RPC/SSE without exposing
  `HttpServer`, servlet, Tomcat, Quarkus, or SDK types publicly.

Task and context IDs are correlation values, never authorization tokens.

## Secure defaults

The embedded listener is loopback-only and anonymous only on loopback. Non-loopback binding requires:

1. `behindTrustedTlsProxy(true)`;
2. an HTTPS `publicEndpoint`;
3. an `A2AHostAuthenticator`; and
4. explicit Host and Origin allowlists.

Host, Origin, content type, protocol version, payload size, JSON depth/string/collection size,
concurrent requests, task channels, and per-subscriber buffers are validated. Graceful close stops
new work, cancels streams, waits for exchanges, and closes the owned virtual-thread executor.
Machine clients may omit `Origin`; when it is present it must be allowlisted. If the embedded host
rewrites a JSON-RPC interface URL (for example, after selecting an ephemeral port), it removes
existing card signatures instead of returning a signature over mutated content.

```java
A2AExecutor executor =
        new A2AAgentExecutor(agent, List.of("text/plain"), A2ALimits.defaults());
A2AService service = A2AService.builder(publicCard, executor)
        .taskStore(new InMemoryA2ATaskStore(10_000))
        .pushStore(new InMemoryA2APushNotificationConfigStore(10_000))
        .build();

try (A2AHttpServer server =
        A2AHttpServer.start(service, A2AHttpServerOptions.builder().build())) {
    System.out.println(server.agentCardUri());
}
```

## Semantics and limitations

| Capability | Status / boundary |
|---|---|
| Agent finite and streaming output | Status + artifact events, append and `lastChunk` preserved |
| Workflow execution | Lossless String, Message, StateValue, byte[] mappings |
| Workflow checkpoint/input-required | Not fabricated; current Workflow runtime exposes no external-input event |
| First resubscribe event | Current `Task`, atomically ordered before later in-process events |
| Historical event replay | Not provided; no Last-Event-ID claim |
| Multiple subscribers | Supported with independent bounded queues |
| Input-required / auth-required | Explicit application-managed task boundaries |
| Push configuration | Stored and queryable |
| Outbound push delivery | **Not implemented**; no SSRF-prone dispatcher is installed |
| Direct orchestration adapter | Not provided; host an orchestration through an Agent when appropriate |
| Cross-language persisted state | Not claimed; only A2A wire interoperability is claimed |
| REST / gRPC bindings | Not implemented |

The built-in stores are not durable and do not coordinate across processes. Applications needing
durability must implement the store SPIs while preserving principal/isolation dimensions and atomic
task replacement.

`A2AInputRequiredException` and `A2AAuthRequiredException` expose boundaries whose continuation
state is application-managed. A framework `ApprovalRequiredException` is not presented as resumable
A2A input: the A2A message model does not carry the framework's typed approval decisions, so silently
starting a new run would be incorrect.
