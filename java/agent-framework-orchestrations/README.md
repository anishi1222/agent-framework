# Agent Framework Orchestrations for Java

`agent-framework-orchestrations` provides Java 25, provider-neutral sequential, concurrent, handoff,
group-chat, and Magentic patterns over `Agent<?>`. It depends directly on
`agent-framework-agents` and uses no workflow implementation type.

## Shared execution contract

Every pattern implements `Orchestration<O>`:

```java
CompletionStage<OrchestrationResult<O>> future = orchestration.runAsync("task");
Flow.Publisher<OrchestrationEvent> events = orchestration.runStreaming("task");
OrchestrationResult<O> result = orchestration.run("task");
RunHandle<OrchestrationResult<O>> handle =
        orchestration.startRun("task", OrchestrationRunOptions.defaults());
handle.cancel();
```

These are views of one execution core. Streaming is cold, bounded, backpressure-aware, and
single-subscriber. Cancellation propagates through participant runs and terminates with
`RunCancelledException`. Framework-created participant executors use virtual threads and close at
the run boundary; an executor supplied through `OrchestrationRunOptions` remains caller-owned.

`OrchestrationParticipant` supplies a stable ID and caller-owned agent. Declaration order is retained
in results and events even when concurrent participants finish in another order.
`OrchestrationRunOptions` propagates metadata plus run, event, correlation, and participant IDs into
each underlying `AgentRunContext`. Optional instrumentation can consume `OrchestrationEventListener`
without introducing an OpenTelemetry dependency into this module.

## Process-local continuation and resume

`INPUT_REQUIRED` results contain one framework-owned `OrchestrationContinuation`. Resume consumes the
descriptor exactly once and validates its orchestration, pattern, logical run, participant, kind, and
underlying approval identity:

```java
OrchestrationContinuation continuation = suspended.continuation();
OrchestrationResumeInput input = OrchestrationResumeInput.approval(
        continuation.agentContinuation().approvalRequests().stream()
                .map(ToolApprovalDecision::approve)
                .toList());

CompletionStage<OrchestrationResult<O>> future =
        orchestration.resumeAsync(continuation, input);
Flow.Publisher<OrchestrationEvent> events =
        orchestration.resumeStreaming(continuation, input);
OrchestrationResult<O> result = orchestration.resume(continuation, input);
RunHandle<OrchestrationResult<O>> handle =
        orchestration.startResume(continuation, input);
```

Resume continues captured pattern state and keeps the transcript, participant results, event sequence,
sessions, and logical run ID. It does not reinvoke completed turns. Sequential, Concurrent, and Group
Chat support participant approval; Handoff supports participant approval and human messages; Magentic
supports participant approval and typed plan approval/rejection. Any unsupported approval source fails
before an unusable continuation is returned.

Continuation storage is process-local only: there is no cross-process, serialization, restart, or
post-close guarantee even when the underlying agent continuation is durable. Each builder accepts
`continuationOptions(...)` to bound retained entries by TTL and maximum count. State is also removed on
resume, eviction, expiration, or orchestration close. The suspended session policy is retained even
when resume options use their default; a caller-supplied run ID must match the logical run.
Cancellation and streaming buffer limits apply to every resume phase.

## Sequential

```java
SequentialOrchestration pipeline = SequentialOrchestration.builder(List.of(
                OrchestrationParticipant.of(researcher),
                OrchestrationParticipant.of(writer),
                OrchestrationParticipant.of(reviewer)))
        .historyPolicy(SequentialHistoryPolicy.SHARED_TRANSCRIPT)
        .failurePolicy(SequentialFailurePolicy.STOP)
        .inputTransform(context -> List.of(
                Message.text(Role.USER, "Refine this: " + context.previousResponse().text())))
        .build();

OrchestrationResult<AgentResponse<?>> result = pipeline.run("Prepare a release note");
```

Without an explicit transform, `SHARED_TRANSCRIPT` passes the complete canonical transcript and
`PREVIOUS_RESPONSE` passes only the prior agent output, reassigned as user context. A response is
appended once, so echoed input is not duplicated. `CONTINUE` returns
`COMPLETED_WITH_ERRORS` only when a later participant still produces a final output.

## Concurrent

```java
ConcurrentOrchestration<List<AgentResponse<?>>> fanOut =
        ConcurrentOrchestration.builder(List.of(
                        OrchestrationParticipant.of(redTeam),
                        OrchestrationParticipant.of(blueTeam)))
                .failurePolicy(ConcurrentFailurePolicy.COLLECT_ERRORS)
                .build();
```

All participants receive the same immutable input. Results and completion events are ordered by
declaration, not timing. `FAIL_FAST` cancels unfinished siblings. `COLLECT_ERRORS` never invokes the
aggregator when any participant fails and returns an explicit `FAILED` result rather than a partial
success. A real participant error always takes precedence over simultaneous `INPUT_REQUIRED`,
independent of completion timing; abandoned approval state is invalidated. Skipped fail-fast siblings
emit `PARTICIPANT_SKIPPED`, while the run emits one run-level terminal event.

## Handoff

```java
HandoffOrchestration support = HandoffOrchestration.builder(List.of(
                OrchestrationParticipant.of(triage),
                OrchestrationParticipant.of(billing),
                OrchestrationParticipant.of(technical)))
        .startParticipant("triage")
        .allowHandoff("triage", "billing")
        .allowHandoff("triage", "technical")
        .maxHandoffs(8)
        .maxTurns(16)
        .build();
```

The default `HandoffRouter.functionCalls()` accepts only typed function content:

- `handoff` with `{ "target": "<registered-id>", "reason": "..." }`;
- `handoff_to_<registered-id>`; or
- `request_human_input` with `{ "prompt": "..." }`.

Natural-language phrases are not parsed for routing. Unknown targets, registered-but-disallowed
transitions, self-handoffs, and repeated paths each have an independent `HandoffViolationPolicy`
setting. Approval and human-input boundaries return `INPUT_REQUIRED` plus an
`OrchestrationContinuation`; they are not reported as generic failures.

## Group chat

```java
GroupChatOrchestration review = GroupChatOrchestration.builder(List.of(
                OrchestrationParticipant.of(author),
                OrchestrationParticipant.of(reviewer)))
        .selector(new RoundRobinGroupChatSelector())
        .repetitionPolicy(SpeakerRepetitionPolicy.DISALLOW_CONSECUTIVE)
        .allowTransition("author", "reviewer")
        .allowTransition("reviewer", "author")
        .terminationPredicate(context ->
                context.transcript().stream().anyMatch(message -> message.text().contains("APPROVED")))
        .maxTurns(12)
        .build();
```

A custom `GroupChatManager` may terminate or select a speaker. `AgentBasedGroupChatSelector` uses
injectable framework prompts and decoders; its default decoder accepts only an exact registered ID or
the exact manager token `TERMINATE`. The manager loop owns one shared transcript and performs no
concurrent writes.

## Magentic

```java
MagenticManager manager = new AgentMagenticManager(managerAgent);
MagenticOrchestration magentic = MagenticOrchestration.builder(
                List.of(
                        OrchestrationParticipant.of(researcher),
                        OrchestrationParticipant.of(writer)),
                manager)
        .maxIterations(30)
        .maxStalls(3)
        .maxReplans(2)
        .build();
```

`MagenticManager` exchanges typed `MagenticPlan`, `MagenticTask`, and
`MagenticProgressAssessment` values. The runtime validates every assignment against registered IDs,
retains an immutable `MagenticLedger`, detects consecutive stalls, bounds replanning and iterations,
and returns explicit `UNSOLVED`, `FAILED`, or `INPUT_REQUIRED` outcomes. Agent-backed manager prompts
are framework-owned `MagenticPromptTemplates` and are replaceable; the default decoders require
structured framework values and make no provider-specific parsing assumptions.

Compile-checked deterministic examples and edge cases live in
[`src/test/java/com/microsoft/agents/orchestrations`](./src/test/java/com/microsoft/agents/orchestrations)
and are suitable starting points for the later standalone Java samples module.
