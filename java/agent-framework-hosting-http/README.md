# Agent Framework embedded HTTP, SSE, and WebSocket hosting

`agent-framework-hosting-http` runs the generic Java hosting contract over an embedded Tomcat
**11.0.24** server while keeping all public server, options, request, response, and WebSocket peer
APIs framework-owned. The wire is Agent Framework-specific; it is **not** OpenAI Responses, A2A,
MCP, AG-UI, or a cross-language persistence format.

## Start a loopback host

```java
HostingRegistry registry = new HostingRegistry();
registry.registerAgent(agent);
registry.registerWorkflow(workflow, HostingWorkflowCodecs.text());

try (HostingDispatcher dispatcher =
             new HostingDispatcher(registry, HostingLimits.defaults());
     HostingHttpServer server = HostingHttpServer.start(
             dispatcher, HostingHttpServerOptions.builder().build())) {
    System.out.println(server.endpoint());
    System.out.println(server.webSocketEndpoint());
}
```

The default listener is `127.0.0.1` on an ephemeral port.

## HTTP routes

All JSON uses wire version **`java-hosting-2026-08-01`**.

| Method | Route | Result |
|---|---|---|
| `GET` | `/v1` | API discovery and limitations |
| `GET` | `/v1/agents` | authorized agent descriptors |
| `GET` | `/v1/workflows` | authorized workflow descriptors |
| `GET` | `/v1/orchestrations` | authorized orchestration descriptors |
| `GET` | `/v1/agents/{routeId}` | one agent descriptor |
| `GET` | `/v1/workflows/{routeId}` | one workflow descriptor |
| `GET` | `/v1/orchestrations/{routeId}` | one orchestration descriptor |
| `POST` | `/v1/{agents\|workflows\|orchestrations}/{routeId}/runs` | finite outcome |
| `POST` | `/v1/{agents\|workflows\|orchestrations}/{routeId}/runs/stream` | SSE run |
| `DELETE` | `/v1/{agents\|workflows\|orchestrations}/{routeId}/runs/{runId}` | cancel an active bound run |
| `POST` | `/v1/{agents\|workflows\|orchestrations}/{routeId}/runs/{runId}/resume` | finite one-time resume |
| `POST` | `/v1/{agents\|workflows\|orchestrations}/{routeId}/runs/{runId}/resume/stream` | SSE one-time resume |
| `GET` upgrade | `/v1/ws` | typed WebSocket protocol |

Run and resume bodies require `Content-Type: application/json` with an optional UTF-8 charset.
Finite routes require an `Accept` compatible with `application/json`; streaming routes require
`text/event-stream`. Outcome statuses are `completed`, `input-required`, `approval-required`,
`failed`, `cancelled`, and `overflow`.

### SSE

Every stream is delivered incrementally and contains:

1. `event: run-started`;
2. zero or more `event: agent-update` or `event: workflow-event` frames; and
3. exactly one `event: terminal` outcome.

IDs start at zero for each transport stream and are correlation values only. `Last-Event-ID` is
rejected because historical replay is not implemented. Disconnect, output failure, idle timeout,
server close, and subscription cancellation propagate to the logical run.

## WebSocket v1

- Path: **`/v1/ws`**
- Required exact subprotocol: **`agent-framework-hosting.v1`**
- JSON version member: **`java-hosting-2026-08-01`**
- One active operation per connection.
- Text messages may be fragmented, but the complete UTF-8 message is bounded.
- Binary messages and negotiated extensions/compression are unsupported.

Client frame fields are strict; unknown members fail:

| Type | Required fields |
|---|---|
| `start` | `version`, `type`, `operationId`, `kind`, `routeId`, `request` |
| `resume` | `version`, `type`, `operationId`, `kind`, `routeId`, `runId`, `request` |
| `demand` | `version`, `type`, `operationId`, positive `count` |
| `cancel` | `version`, `type`, `operationId` |
| `close` | `version`, `type` |

Server frames are `started`, `event`, `terminal`, and `error`. `demand` controls event delivery;
terminal and error control frames are not withheld by event demand. Ping/pong maintains an active
peer; missing activity closes with `1001`. Normal close uses `1000`, unsupported binary uses `1003`,
invalid UTF-8/JSON uses `1007`, oversized/overflowed messages use `1009`, internal failures use
`1011`, and temporary capacity failures map to `1013` when a close is required.

## Security and limits

- `LOOPBACK_HTTP` requires a loopback listener and rejects non-loopback peers.
- A non-loopback listener is allowed only with `TRUSTED_TLS_PROXY`, an explicit HTTPS advertised
  origin, a non-local `HostingAuthenticator`, and explicit Host and Origin allowlists.
- The embedded server does **not** terminate TLS directly. A trusted proxy must be the only network
  path, terminate HTTPS, sanitize forwarded headers, and set an HTTPS forwarded-protocol value.
- Host is always required and allowlisted. Origin is allowlisted when present; a remote WebSocket
  requires it. CORS response headers are disabled by default.
- Credential and security-sensitive headers must be unambiguous. Credential headers cannot be copied
  into trusted context.
- Request/header bytes, JSON depth/strings/numbers/collections, concurrent requests/runs, response
  bytes, event count, SSE buffers, WebSocket complete messages/outbound buffers, run/idle timeouts,
  continuation count/TTL, and graceful shutdown are bounded.
- `maxResponseBytes` has a 153-byte minimum so error handling always has a valid bounded fallback;
  oversized ordinary error envelopes are replaced without retaining request admission.
- Container-level request rejection uses the same sanitized JSON error shape and security headers;
  Tomcat reports and server-version pages are disabled.

`HostingHttpHandler` owns a removable authentication-deadline scheduler and is `AutoCloseable`.
`HostingHttpServer` closes it automatically; standalone handler users must close it explicitly.

The host does not provide historical SSE replay, cross-process continuation, durable storage,
direct TLS, WebSocket compression, multiple simultaneous WebSocket operations, or authorization by
route/run/token identifiers alone.
