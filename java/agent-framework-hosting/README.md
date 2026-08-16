# Agent Framework generic hosting core

`agent-framework-hosting` is the framework-owned, web-server-neutral execution layer for exposing
registered Java Agent, Workflow, and Orchestration instances. It depends inward on those runtimes;
it does not expose Servlet, Tomcat, Spring, Reactor, or provider SDK types.

## Core model

- `HostingRegistry` registers deterministic agent, workflow, and orchestration route IDs. Duplicate kind/ID pairs
  fail rather than replace a target.
- `HostingDispatcher` applies `HostingAuthorizer`, starts finite or streaming production runs, maps
  results/events, enforces `HostingLimits`, and owns active-run and continuation lifecycle.
- `HostingAuthenticator` maps a validated transport request to a `HostingPrincipal`. Both
  `principalId` and `isolationId` are security dimensions.
- `HostingJsonCodec` implements the strict nonstandard wire version
  **`java-hosting-2026-08-01`**. It rejects duplicate members, trailing input, unknown request
  members, unsupported content discriminators, non-finite numbers, and configured
  byte/depth/string/number/collection limit violations.
- `HostingLimits.maxResponseBytes` must be at least **153 bytes**, which guarantees that every
  stable error code still has a valid bounded minimal protocol envelope when ordinary error
  encoding itself overflows.
- `HostingWorkflowCodec` makes workflow input/output conversion explicit. Built-in codecs support
  JSON-shaped values and text; route codecs may configure real checkpoint storage.
- `HostingOrchestrationCodec` maps terminal output and typed process-local resume input; standard
  codecs cover approval, human input, and plan review.

Finite operations return a `CompletionStage<HostingOutcome>`. Streaming operations return a
`HostingRun` containing a demand-aware `Flow.Publisher<HostingEvent>`, terminal stage, and explicit
cancellation.

## Continuation boundary

Production continuations include ChatAgent tool approval and real Orchestration approval/input
boundaries. A returned token is:

- opaque and cryptographically random;
- bound to principal, isolation, route kind, route ID, run ID, and continuation type;
- one-time, expiring, capacity-bounded, and process-local; and
- retained only until consumed, expired, explicitly discarded, or dispatcher close.

An identity mismatch does not consume a token. Replay after successful consumption fails. The module
does **not** claim durable or cross-process resume, historical event replay, or exactly-once external
effects after process failure.

## Transport adapters

- [`agent-framework-hosting-http`](../agent-framework-hosting-http/README.md) — embedded
  HTTP/JSON, SSE, and WebSocket.
- [`agent-framework-hosting-spring`](../agent-framework-hosting-spring/README.md) — optional,
  opt-in Spring WebFlux JSON/SSE adapter.
