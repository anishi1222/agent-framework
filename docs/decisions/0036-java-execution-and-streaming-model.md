---
status: proposed
contact: anishi1222
date: 2026-08-04
deciders: anishi1222
consulted:
informed:
---

# Java execution and streaming model

## Context and Problem Statement

Agent and workflow operations need asynchronous, streaming, and synchronous entry points. Implementing three separate
execution paths would make results, errors, cancellation, and tool side effects diverge. The Java design also needs a
stable concurrency foundation that works well with virtual threads without depending on Java 25 preview APIs.

## Decision Drivers

- Use standard JDK contracts in the core API.
- Preserve backpressure and cancellation for streaming operations.
- Keep synchronous and asynchronous behavior derived from one execution core.
- Allow synchronous callers to use virtual threads efficiently.
- Avoid public or internal dependence on preview Structured Concurrency APIs.
- Keep Reactor and other reactive libraries optional.

## Considered Options

- `CompletionStage<T>` plus `Flow.Publisher<T>` with a derived synchronous facade
- Reactor `Mono<T>` and `Flux<T>` as core contracts
- Synchronous-only core executed on virtual threads
- Separate synchronous, asynchronous, and streaming implementations
- `StructuredTaskScope` as the concurrency foundation

## Decision Outcome

Chosen option: **`CompletionStage<T>` plus `Flow.Publisher<T>` with a derived synchronous facade**.

One internal execution core owns each operation's lifecycle, state transitions, tool side effects, cancellation, and
terminal result:

- finite asynchronous operations return `CompletionStage<T>`;
- streaming operations return `Flow.Publisher<T>`;
- synchronous facade methods invoke the same execution core and wait for its terminal stage; and
- Reactor interoperation is supplied only by an optional adapter module.

The public method families are fixed as follows:

| Surface | Finite asynchronous | Streaming | Synchronous | Explicitly cancellable run |
|---|---|---|---|---|
| `Agent` | `runAsync(...)` | `runStreaming(...)` | `run(...)` | `startRun(...)` |
| `ChatClient` (`com.microsoft.agents.agents`) | `completeAsync(...)` | `completeStreaming(...)` | `complete(...)` | `startCompletion(...)` |
| `Workflow` | `runAsync(...)` | `runStreaming(...)` | `run(...)` | `startRun(...)` |

`Async` is reserved for finite methods returning `CompletionStage`; `Streaming` is reserved for methods returning
`Flow.Publisher`; synchronous facade methods are unsuffixed. Mixed sync/async suffix conventions are not public Java
API names.

### Per-run cancellation contract

`CompletionStage` represents completion, not an operation-owned cancellation protocol. The Java API therefore defines
the following provider-neutral public contracts in `com.microsoft.agents.core`:

```java
public interface RunCancellation {
    boolean cancel();
    boolean isCancellationRequested();
    CompletionStage<Void> cancelledAsync();
}

public interface RunHandle<T> {
    CompletionStage<T> resultAsync();
    RunCancellation cancellation();
    boolean cancel();
}
```

`RunHandle.cancel()` delegates to its `RunCancellation`. `startRun`/`startCompletion` return the handle created by the
single execution core. Every finite asynchronous operation also has an overload accepting a caller-owned
`RunCancellation`; the no-token convenience overload creates a token and returns
`startRun(...).resultAsync()`/`startCompletion(...).resultAsync()`. Callers that need to initiate cancellation use the handle or
pass their own token. Calling `cancel()` on a concrete `CompletableFuture` obtained by down-casting a returned
`CompletionStage` is neither required nor guaranteed to cancel framework work.

Cancellation is idempotent and propagates to queued tasks, provider calls that expose cancellation, tool execution,
workflow branches, and the terminal stage. The terminal stage completes exceptionally with
`RunCancelledException`; it never reports a successful partial result unless that result is an explicitly documented
domain value. For streaming runs, `Flow.Subscription.cancel()` requests the same run cancellation and stops further
signals. Implementations document adapter limitations when an external SDK cannot interrupt an in-flight request, but
must still suppress subsequent framework work and terminal success.

A streaming publisher is cold and represents one run per subscription. Framework publishers support one subscriber per
publisher instance, emit only in response to positive demand, serialize signals, emit exactly one terminal signal, and
stop work promptly after cancellation. A caller that needs another run requests another publisher.

The synchronous facade does not duplicate orchestration or tool logic and does not create a separate platform-thread
pool. It obtains a `RunHandle`, waits interruptibly on `RunHandle.resultAsync()`, and returns the terminal result. On
interruption it calls `RunHandle.cancel()`, restores the interrupt flag, and throws `SynchronousExecutionException`
with the `InterruptedException` as its cause. A failed asynchronous run completes with the same typed framework cause
that the synchronous facade wraps in `SynchronousExecutionException`; cancellation maps to
`RunCancelledException`. Serialization failures remain `SerializationException`, and optimistic storage conflicts
remain `StorageConflictException`, so callers can distinguish execution, persistence, and cancellation failures.

The implementation may use stable Java 25 APIs including virtual threads, `ExecutorService`, `CompletableFuture`, and
`Flow`. Blocking provider calls and independent workflow branches use explicitly owned executors; default blocking
work uses virtual threads rather than an unbounded pool of platform threads. Executor ownership and close behavior must
be explicit so that framework-created executors are closed while caller-provided executors are not.

The implementation must not reference `StructuredTaskScope` or require `--enable-preview`. Structured concurrency can
be reconsidered in a superseding decision after the relevant JDK API is final.

### Consequences

- Good, because core consumers need no reactive or application framework dependency.
- Good, because `Flow.Publisher` carries standard demand and cancellation semantics.
- Good, because synchronous, asynchronous, and streaming results share one lifecycle and side-effect model.
- Good, because synchronous calls can run naturally on virtual threads.
- Neutral, because provider adapters must bridge SDK-specific future and stream types.
- Bad, because implementing a correct `Flow.Publisher` requires careful demand, cancellation, and terminal-signal tests.
- Bad, because callers using Reactor need an additional adapter artifact.
- Bad, because stable APIs require more explicit task and cancellation bookkeeping than preview structured concurrency.

## Validation

- Contract tests must compare synchronous and asynchronous results, errors, cancellation, and tool side effects.
- API-signature tests must require `RunCancellation.cancelledAsync()` and `RunHandle.resultAsync()` and reject the
  unsuffixed finite-stage forms.
- Cancellation tests must use both `RunHandle.cancel()` and a caller-owned `RunCancellation`, prove idempotence and
  propagation, and prove that `CompletionStage` alone is not advertised as the cancellation controller.
- Publisher tests must cover demand, invalid demand, slow subscribers, cancellation, failure, and exactly-once terminal
  signals.
- Tests must verify that a second subscription to the same publisher is rejected and that a newly requested publisher
  starts an independent run.
- The build must reject `--enable-preview` and references to `StructuredTaskScope`.
- Executor lifecycle tests must distinguish framework-owned and caller-owned executors.

## Pros and Cons of the Options

### `CompletionStage<T>` plus `Flow.Publisher<T>` with a derived synchronous facade

- Good, because both contracts are stable JDK APIs.
- Good, because the design supports asynchronous, streaming, and virtual-thread callers.
- Neutral, because conversion is required at external SDK boundaries.
- Bad, because the framework must implement and test Reactive Streams rules.

### Reactor `Mono<T>` and `Flux<T>` as core contracts

- Good, because Reactor has mature operators and testing utilities.
- Good, because it integrates naturally with reactive Spring applications.
- Neutral, because Reactor can adapt to `Flow`.
- Bad, because every core consumer would inherit Reactor's dependency and release cadence.
- Bad, because Reactor would become part of the public compatibility surface.

### Synchronous-only core executed on virtual threads

- Good, because provider implementations can use straightforward blocking code.
- Neutral, because virtual threads make many blocking workloads scalable.
- Bad, because streaming backpressure and integration with asynchronous provider SDKs become adapters around a
  synchronous abstraction.
- Bad, because cancellation and partial updates are harder to express faithfully.

### Separate synchronous, asynchronous, and streaming implementations

- Good, because each path can be optimized independently.
- Neutral, because providers could implement only the paths they support.
- Bad, because behavior, tool invocation, and error handling can drift across paths.
- Bad, because bug fixes must be repeated in multiple execution engines.

### `StructuredTaskScope` as the concurrency foundation

- Good, because task lifetime and failure propagation are expressed directly.
- Neutral, because it may become an appropriate implementation choice after finalization.
- Bad, because it is a preview API in Java 25 and would require preview flags for consumers and maintainers.

## More Information

- [Java async and streaming API mapping](../java/api-mapping.md#3-async--streaming-api-shape)
- [ADR-0001: Agent run responses design](0001-agent-run-response.md)
