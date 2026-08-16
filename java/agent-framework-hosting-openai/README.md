# Agent Framework OpenAI Responses hosting

`agent-framework-hosting-openai` exposes framework agents through a strict, framework-owned subset
of the OpenAI Responses HTTP and SSE contract. It adapts requests to the shared
`HostingRegistry`/`HostingDispatcher`; it does not introduce another execution engine and has no
dependency on an OpenAI provider SDK.

The module is published with the shared Java release and constrained by `agent-framework-bom`.

## Endpoint

`OpenAIResponsesHostingRegistry.DEFAULT_PATH` is `/v1/responses`. Applications may bind additional
exact paths to independently registered agent routes.

| Method | Path | Result |
|---|---|---|
| `POST` | registered exact path | finite JSON response or SSE stream when `stream` is `true` |
| `OPTIONS` | registered exact path | CORS preflight under the shared HTTP policy |

The request must use `Content-Type: application/json`. A finite request must accept
`application/json`; a streaming request must accept `text/event-stream`. Unknown fields, duplicate
JSON members, trailing JSON, non-finite numbers, invalid discriminators, and configured parser-limit
violations are rejected before dispatch.

The adapter does not implement response retrieval, deletion, or HTTP cancellation routes. It also
does not implement `Last-Event-ID` replay.

## Supported request subset

Accepted top-level members are:

- `input` (required)
- `model`
- `instructions`
- `max_output_tokens`
- `temperature`
- `top_p`
- `parallel_tool_calls`
- `metadata`
- `stream`
- `previous_response_id`
- `conversation`
- `store`
- `tools`
- `tool_choice`
- `max_tool_calls`
- `user`

`input` may be a string or an item array. Supported items are messages, function calls, function
call outputs, and reasoning items. Message content supports text, refusal, input image, and input
file values. Remote image/file references must be absolute HTTP(S) URIs; bounded data URIs are also
supported. Image detail accepts `auto`, `low`, `high`, or `original`; file detail accepts `auto`,
`low`, or `high`. The adapter only maps these references and does not fetch them. Provider-owned
`file_id` references are not supported.

Only function tool declarations and `none`, `auto`, `required`, or named-function tool choices are
accepted. Hosted OpenAI tools such as web search, file search, or computer use are not implemented.

### Request settings are opt-in

The default `OpenAIResponsesHostingOptions` treats `model` as an informational response value and
maps `max_tool_calls` to `RunOptions.maxFunctionCalls`. It rejects caller attempts to override
instructions, generation settings, tools, tool choice, or parallel tool behavior.

An application must provide an explicit `OpenAIResponsesRunOptionsMapper` to accept those settings:

```java
OpenAIResponsesHostingOptions options = OpenAIResponsesHostingOptions.builder()
        .runOptionsMapper(request -> RunOptions.builder()
                .maxFunctionCalls(request.maxToolCalls() == null
                        ? 8
                        : request.maxToolCalls())
                .build())
        .build();
```

The mapper receives only framework-owned values. Accepting a field does not make a provider-specific
setting available automatically; the hosted agent must interpret the resulting provider-neutral
`RunOptions`.

## Responses and streaming

Finite results use an OpenAI Responses-shaped object with a generated `resp_*` identifier, status,
model, output items, usage, request settings, metadata, and conversation references. Framework text,
function call, function result, reasoning, data, and URI content are mapped without exposing
provider types.

Every successful SSE stream starts with:

1. `response.created`
2. `response.in_progress`

Text output then uses output-item/content-part added events, one or more
`response.output_text.delta` events, and the corresponding text/content/item done events. Function
calls use output-item added, function-argument delta/done, and output-item done events. Refusals use
`response.refusal.delta` and `response.refusal.done`. Each event has a monotonically increasing
`sequence_number`.

Exactly one terminal event is emitted when delivery continues:

- `response.completed`
- `response.failed`
- `response.cancelled`

SSE delivery is bounded and demand-aware. Subscription cancellation, explicit hosted-run
cancellation, disconnect/write failure, idle timeout, and server shutdown propagate cancellation to
the generic hosting run.

## Conversation isolation

Conversation storage keys include all of:

- trusted authenticated principal ID;
- independently derived isolation/tenant ID;
- generic route ID;
- reference type; and
- caller-supplied conversation or response identifier.

Caller identifiers are correlation values, never authorization authorities.

`conversation` uses a mutable compare-and-set head and rejects concurrent mutation.
`previous_response_id` reads an immutable response snapshot, so multiple independent branches can
start from the same response. The default store is capacity bounded and TTL expiring; applications
may provide a durable implementation of `OpenAIResponsesConversationStore`.

Top-level `instructions` apply only to the current response and are not retained in the conversation
or previous-response transcript. Explicit developer/system messages supplied in `input` are normal
conversation items and are retained.

`store: false` suppresses retention for a detached create request. Requests that explicitly
participate in a conversation or previous-response chain retain the state required to preserve that
continuity.

## Start a loopback server

```java
HostingLimits limits = HostingLimits.defaults();
HostingRegistry generic = new HostingRegistry();
OpenAIResponsesHostingRegistry responses =
        new OpenAIResponsesHostingRegistry(generic);
responses.registerAgent(OpenAIResponsesHostingRegistry.DEFAULT_PATH, agent);

try (HostingDispatcher dispatcher = new HostingDispatcher(generic, limits)) {
    HostingHttpServerOptions http = HostingHttpServerOptions.builder()
            .limits(limits)
            .build();
    OpenAIResponsesHttpHandler handler =
            new OpenAIResponsesHttpHandler(dispatcher, responses, http);

    try (OpenAIResponsesHttpServer server =
            OpenAIResponsesHttpServer.start(handler)) {
        System.out.println(
                server.endpoint().resolve(OpenAIResponsesHostingRegistry.DEFAULT_PATH));
    }
}
```

The default listener is loopback-only on an ephemeral port. The embedded server uses JDK
`HttpServer` and virtual threads.

For an application-owned HTTP stack, construct a bounded `HostingHttpRequest` and call
`handleAsync`, `handleAuthenticatedAsync`, or `handleResolvedAsync`. Write finite response bytes
directly, or subscribe once to `OpenAIResponsesHostedRun.frames()`. If a stream cannot be completely
delivered, call `discardUndelivered()`.

## Security and limits

- Shared generic HTTP Host, Origin, proxy, authentication, authorization, request-context, and
  cancellation policy is reused.
- Loopback HTTP is the default. Non-loopback binding requires the generic trusted-TLS-proxy mode, an
  application authenticator, an HTTPS advertised endpoint, and explicit Host/Origin allowlists.
- Request/header bytes, JSON depth/string/number/collection sizes, response bytes, event counts, SSE
  buffers, concurrent requests/runs, run time, idle time, conversation capacity, and conversation
  lifetime are bounded.
- Errors are mapped to OpenAI-shaped envelopes without returning provider exception details.
- Generic hosting redaction still applies to credential-shaped object members in response metadata
  and tool payloads; non-credential tool arguments retain their exact JSON values.
- Public APIs expose only JDK and Agent Framework types—never OpenAI SDK, Jackson, Reactor, servlet,
  or Spring types.
- The embedded server does not terminate TLS and is not a replacement for a production reverse
  proxy or application authentication boundary.

## Build dependencies and repository integration

The module declares exactly these direct project dependencies:

```kotlin
api(project(":agent-framework-hosting"))
api(project(":agent-framework-hosting-http"))
```

It does not depend on `agent-framework-openai`.

Applications add `com.microsoft.agents:agent-framework-hosting-openai` under the shared BOM. Run
`./gradlew :agent-framework-hosting-openai:check` from `java/` for focused validation.
