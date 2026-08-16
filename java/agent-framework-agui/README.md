# Agent Framework AG-UI for Java

`agent-framework-agui` is the framework-owned AG-UI wire boundary for Java 25. Public APIs use only
Agent Framework and JDK types; Jackson is internal.

## Verified protocol baseline

| Surface | Version verified on 2026-08-10 |
|---|---|
| TypeScript schema | `@ag-ui/core` `0.0.57` |
| TypeScript encoder | `@ag-ui/encoder` `0.0.57` |
| TypeScript client | `@ag-ui/client` `0.0.57` |
| Official .NET packages | `AGUI.Abstractions`, `AGUI.Client`, `AGUI.Server` `0.0.5` |
| AG-UI repository Java release | `com.ag-ui.community:java-{core,client,server}:0.1.0` |

The Java artifacts now exist, but remain under `sdks/community`, use `com.ag-ui.community`
coordinates, and state that they are under development. This module therefore keeps a strict,
framework-owned model and validates checked-in goldens produced from the official TypeScript
schema/encoder. It does not depend on or expose the community SDK.

## Public entry points

- `RunAgentInput`, `AGUIMessage`, `AGUIMessages`, `AGUITool`, `AGUIContext`
- sealed `AGUIEvent` / `AGUIEvents` hierarchy and exact `AGUIEventType` discriminators
- `AGUIJsonCodec`, `AGUISseParser`, `AGUIEventNormalizer`, `AGUIEventStreamValidator`
- `AGUIJsonPatch` and `AGUIJsonPatchOperation`
- `AGUIMessageConverter`, `AGUIAgentEventConverter`, `AGUIAgentResponseConverter`
- redirect-free `AGUIClient` using JDK `HttpClient`, `Flow`, and `CompletionStage`

## Event coverage

| Category | Events |
|---|---|
| Run / step | `RUN_STARTED`, `RUN_FINISHED`, `RUN_ERROR`, `STEP_STARTED`, `STEP_FINISHED` |
| Text | `TEXT_MESSAGE_START`, `TEXT_MESSAGE_CONTENT`, `TEXT_MESSAGE_END`, `TEXT_MESSAGE_CHUNK` |
| Tool | `TOOL_CALL_START`, `TOOL_CALL_ARGS`, `TOOL_CALL_END`, `TOOL_CALL_CHUNK`, `TOOL_CALL_RESULT` |
| State | `STATE_SNAPSHOT`, `STATE_DELTA`, `MESSAGES_SNAPSHOT` |
| Activity | `ACTIVITY_SNAPSHOT`, `ACTIVITY_DELTA` |
| Reasoning | `REASONING_START`, message start/content/end/chunk, `REASONING_END`, `REASONING_ENCRYPTED_VALUE` |
| Extension | `RAW`, `CUSTOM` |
| Deprecated official compatibility | five `THINKING_*` events, marked `@Deprecated(forRemoval = true)` |

Draft `META_EVENT` is intentionally rejected because it is absent from the `0.0.57`
`EventSchemas` union.

## Client

```java
try (AGUIClient client = new AGUIClient(
        AGUIClientOptions.builder(URI.create("https://agents.example/ag-ui")).build())) {
    Flow.Publisher<AGUIEvent> events = client.runStreaming(input);
}
```

The client validates HTTP status/media/framing, strict UTF-8 and JSON, event order, byte/event
bounds, idle/run timeouts, and host policy. Cancellation closes the response body. Plain HTTP
requires explicit loopback opt-in. For SSE EOF, the client honors `AGUISseParser.finish()`: it
dispatches one syntactically complete buffered trailing frame before final stream validation.
Official blank-line termination remains preferred; incomplete JSON fails, and EOF without
`RUN_FINISHED` or `RUN_ERROR` is never treated as success.

## Deliberate input and compatibility policy

The framework is intentionally stricter than the permissive official input schemas. A present
optional identifier or name must contain at least one non-whitespace character. `RunAgentInput`
also rejects unknown members and reports their names with the action to remove the member or
upgrade. This catches misspellings and unsupported request features early, but means clients must
upgrade before sending a future request field.

Recognized event envelopes take the forward-compatible path instead: bounded unknown additive
fields are retained in `AGUIEvent.additionalProperties()` and re-emitted on encode. Unknown event
discriminators still fail because their lifecycle semantics cannot be validated safely.

| Wire surface | Unknown member policy | Present optional ID/name policy | Compatibility tradeoff |
|---|---|---|---|
| `RunAgentInput` and nested input models | Reject with member names and upgrade/remove guidance | Nonblank | Fail fast; upgrade required for future request features |
| Recognized event envelope | Preserve bounded additions and round-trip | Nonblank | Additive fields survive; known lifecycle remains validated |
| Unknown event discriminator | Reject | n/a | No unsafe guess at ordering or terminal semantics |

## Limits

- HTTP/SSE only; no WebSocket transport claim.
- No `Last-Event-ID` replay or automatic reconnect.
- Resume requires the server’s namespaced capability document and remains opaque, one-time, and
  process-local.
- No durable or cross-process continuation claim.
- Protobuf is not implemented by this module.
