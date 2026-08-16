# Agent Framework A2A for Java

`agent-framework-a2a` provides framework-owned Agent2Agent (A2A) protocol v1 models, a bounded
JSON-RPC 2.0 client over JDK `HttpClient`, SSE streaming, and `A2AAgent`.

## Version and SDK decision

- Protocol release tested: **A2A v1.0.1** (wire protocol version **`1.0`**).
- Official Java SDK tested: **`org.a2aproject.sdk:*:1.2.0.Final`**, published to Maven Central on
  2026-08-07.
- The official SDK is used in tests for real card/client/model/parser/serializer interoperability.
  Production wire code is JDK HTTP plus bounded Jackson-core parsing. This avoids leaking generated
  Proto/SDK types, Quarkus/CDI server coupling, and the official default client's redirect policy
  into the public API.

## Supported surface

| Capability | Status |
|---|---|
| Public and authenticated extended Agent Cards | Supported |
| `SendMessage` / `SendStreamingMessage` | Supported |
| `GetTask`, `ListTasks`, `CancelTask`, `SubscribeToTask` | Supported |
| Push configuration create/get/list/delete | Supported |
| JSON-RPC 2.0 over HTTP(S), `application/json` | Supported |
| SSE with task/context/event validation and bounded backpressure | Supported |
| `A2AAgent` continuation, input-required, and auth-required mapping | Supported |
| HTTP+JSON (REST) binding | Not implemented |
| gRPC binding | Not implemented |
| Last-Event-ID replay/reconnect | Not advertised or implemented |
| OAuth flow execution and JWS card verification | Not implemented |

## Client

Remote endpoints require HTTPS. Plain HTTP requires explicit syntactic-loopback opt-in. Redirects
are always disabled, hosts are allowlisted, credentials come from a per-request header provider, and
request/response/JSON/event/concurrency/buffer limits are finite.

```java
try (A2AClient client = A2AClient.create(
        A2AClientOptions.builder(URI.create("https://agent.example/a2a"))
                .allowedHosts(Set.of("agent.example"))
                .headerProvider(request -> Map.of(
                        "Authorization", "Bearer " + tokenProvider.get()))
                .build())) {
    SendMessageResult result = client.sendMessageAsync(
                    new SendMessageRequest(Message.builder(Role.ROLE_USER)
                            .parts(List.of(new TextPart("Hello")))
                            .build()))
            .toCompletableFuture()
            .join();
}
```

`Flow.Subscription.cancel()` cancels the HTTP future and closes the response stream. Streaming
responses must begin with a `Task` (always for resubscribe) or direct `Message`, preserve task/context
correlation, use valid artifact append/last-chunk ordering, and reach a terminal or interrupted
boundary.

## Remote-agent adapter

`A2AAgent` implements the framework `Agent<Void>` contract. The ordinary response places an
`A2AContinuation` in `AgentResponse.continuationToken()`. Use `runA2AAsync` when the application
needs the explicit `COMPLETED`, `WORKING`, `INPUT_REQUIRED`, or `AUTH_REQUIRED` outcome. Failed,
canceled, rejected, and protocol-error tasks complete exceptionally.

Only the final framework user message is sent; supplied system/assistant history is not replayed.
Completed task continuations use `contextId` plus `referenceTaskIds` for refinement, while interrupted
tasks use the existing `taskId` and `contextId`.

## Content mapping

- text ↔ `TextContent`;
- URI/file bytes ↔ `UriContent` / `DataContent`;
- structured JSON ↔ canonical JSON text carrying a framework-owned data marker; and
- image, audio, and document MIME types retain media type, filename, bytes/URI, and metadata.

Unsupported content or output modes fail with `A2AConversionException`; no content is silently
dropped except non-final caller history intentionally excluded to prevent duplicate delivery.
