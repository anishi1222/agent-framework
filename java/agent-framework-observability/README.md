# Agent Framework Java observability

`agent-framework-observability` is an optional outward adapter for Agent Framework Java. It depends
inward on workflows and agents; shared runtime modules never depend on OpenTelemetry. The module uses
the application-supplied `OpenTelemetry` instance and never reads or mutates the global SDK.

## Versions and conventions

- `io.opentelemetry:opentelemetry-api:1.64.0` is the only production OpenTelemetry dependency.
- Tests use `io.opentelemetry:opentelemetry-sdk:1.64.0` and
  `io.opentelemetry:opentelemetry-sdk-testing:1.64.0`.
- The GenAI conventions in
  [`open-telemetry/semantic-conventions-genai`](https://github.com/open-telemetry/semantic-conventions-genai)
  are **Development**, not stable. The current generated Java constants are published separately as
  `io.opentelemetry.semconv:opentelemetry-semconv-incubating:1.43.0-alpha`; this module deliberately
  does not add that alpha artifact to production and instead keeps its small set of development
  attribute names internal.

The decorators emit stable span names:

| Operation | Span name | Kind |
|---|---|---|
| agent | `invoke_agent {agent name}` | `INTERNAL` |
| chat client | `chat {request model}` | `CLIENT` |
| function tool | `execute_tool {tool name}` | `INTERNAL` |
| workflow | `invoke_workflow {workflow id}` | `INTERNAL` |

Metrics use the current names `gen_ai.client.operation.duration`,
`gen_ai.client.token.usage`, `gen_ai.invoke_agent.duration`,
`gen_ai.invoke_workflow.duration`, and `gen_ai.execute_tool.duration`. Streaming workflow and response
updates are also represented as span events.

## Configure and wrap

```java
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        .build();
OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();

AgentFrameworkTelemetry telemetry = AgentFrameworkTelemetry.builder(sdk)
        .providerName("openai")
        .identifierPolicy(IdentifierPolicy.HASH)
        .instrumentationFailureHandler(InstrumentationFailureHandler.recordOnCurrentSpan())
        .contextRegistryOptions(TelemetryContextRegistryOptions.builder()
                .maximumEntries(4096)
                .abandonedRunTtl(Duration.ofMinutes(10))
                .build())
        .build();

ChatClient observedClient = new OpenTelemetryChatClient(chatClient, telemetry);
Agent<Void> observedAgent = new OpenTelemetryAgent<>(agent, telemetry);
FunctionMiddleware observedTools = new OpenTelemetryFunctionMiddleware(telemetry);
OpenTelemetryWorkflow<Input, Output> observedWorkflow =
        new OpenTelemetryWorkflow<>(workflow, telemetry);
```

Wrappers are non-owning by default. Constructor overloads can explicitly transfer close behavior.
`OpenTelemetryFunctionMiddleware` belongs in the `ChatAgent` function-middleware list.

## Privacy

Prompts, outputs, tool arguments, tool results, workflow input, and workflow output are omitted by
default. Identifiers are also omitted by default. Content capture requires explicit opt-in:

```java
TelemetryContentPolicy content = TelemetryContentPolicy.builder()
        .captureContent(true)
        .redactedKeys(Set.of("customerNumber"))
        .maxValueCharacters(1024)
        .build();

AgentFrameworkTelemetry telemetry = AgentFrameworkTelemetry.builder(sdk)
        .providerName("azure.ai.openai")
        .identifierPolicy(IdentifierPolicy.HASH)
        .contentPolicy(content)
        .build();
```

Credential-like object members—including authorization, API-key, token, password, secret,
credential, cookie, and private-key fields—are always replaced with `[REDACTED]`, even after content
opt-in. Captured scalar values have control characters replaced and are bounded by
`maxValueCharacters`. Streaming output is sanitized incrementally, retained within
`maxStreamingCaptureCharacters`, and written to the span once at terminal; a separate truncation
attribute reports when complete chunks did not fit.

Metric dimensions are explicitly allow-listed. Response, conversation, agent-run, workflow-run,
tool-call, and tool-invocation identifiers are span-only and are never metric attributes.

## Context and lifecycle

The decorators explicitly carry parent context through `CompletionStage` callbacks, virtual-thread
handoffs, `Flow` subscriptions, and concurrent agent runs. Operation-specific context keys suppress
duplicate spans when the same decorator is composed twice while preserving the expected
workflow → agent → chat/tool hierarchy. `TelemetrySuppression.suppress()` provides an explicit
lexical suppression scope. Summarization compaction sets the same internal suppression hint on its
direct `ChatClient` request, preventing recursive instrumentation.

Streaming spans start only on subscription and end exactly once on completion, error, cancellation,
or subscriber failure. A publisher that is never subscribed creates no span.

Parent contexts are retained in a per-`AgentFrameworkTelemetry` bounded registry keyed by propagated
per-run correlation metadata, never by cancellation-object identity. Terminal signals remove entries
deterministically. Abandoned entries expire and oldest entries are evicted during register/lookup
operations; no cleanup thread is created, and evicted spans receive the `abandoned` outcome.

## Instrumentation failures

Observability is non-intrusive: sanitizer, observer, span, metric, exporter-facing, and failure-handler
exceptions do not replace delegate results or errors, drop stream items, or cancel upstream. The
default `InstrumentationFailureHandler` records an internal event containing only the exception type
when a recording span is safely available. Otherwise it is a documented no-op; it never logs
exception messages or application values. Custom handlers are recursion-guarded, and any failure
raised by a custom handler is discarded.
