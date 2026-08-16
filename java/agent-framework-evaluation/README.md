# Agent Framework Java evaluation

`agent-framework-evaluation` is the provider-neutral evaluation layer for Agent Framework Java. It
contains framework-owned immutable contracts, deterministic local checks, conversation splitting,
and asynchronous adapters for `Agent` and `Workflow`. It does not depend on a provider SDK, Jackson,
Reactor, or a cloud service.

## Contracts

- `Evaluator` evaluates a non-empty `List<EvalItem>` through `CompletionStage<EvalResults>`.
- `EvalItem` keeps the full immutable `Message` conversation as its source of truth and derives
  query and response text through a `ConversationSplitter`.
- `EvalResults`, `EvalItemResult`, `EvalScoreResult`, and `EvalCounts` provide immutable run,
  per-item, and per-check results.
- `EvaluationTool`, `ExpectedToolCall`, and every metadata surface use framework-owned
  `StateValue` rather than provider or JSON-library types.
- `LocalEvaluator` runs synchronous and asynchronous `EvaluationCheck` instances in stable input
  order. An item with no checks fails because no passing evidence was produced.

## Deterministic checks

```java
LocalEvaluator evaluator = new LocalEvaluator(
        EvalChecks.keyword("weather", "temperature"),
        EvalChecks.toolCalled("get_weather"),
        EvalChecks.toolCallsPresent(),
        EvalChecks.toolCallArgsMatch());
```

- `keyword` requires every keyword and is case-insensitive by default.
- `toolCalled` checks explicit tool names in `ALL` or `ANY` mode.
- `toolCallsPresent` compares `EvalItem.expectedToolCalls()` by exact name, without checking
  arguments. Matching is unordered, preserves duplicate-call multiplicity, permits extras, and
  passes when no calls are expected.
- `toolCallArgsMatch` performs the same one-to-one matching and treats each expected argument object
  as a top-level subset of the actual framework-owned `StateValue.ObjectValue`.

Tool names are matched exactly. Actual calls are inspected only from `FunctionCallContent` in the
conversation.

## Conversation splitting

`ConversationSplitters` provides:

- `lastTurn()` — response begins after the last user message.
- `full()` — response begins after the first user message.
- `atMessageBoundary(index)` — response begins at an explicit message index.
- `beforeFirstToolCall()`, `beforeToolCall(name)`, and `beforeToolCallId(callId)` — response begins
  with a function-call message.
- `afterToolResult(callId)` — response begins after the correlated function-result message.

Requested tool boundaries that do not exist fail validation instead of silently choosing a
different split. Custom splitters must preserve every conversation message exactly once and in
order.

## Agent evaluation

```java
AgentEvaluationAdapter<Void> adapter = new AgentEvaluationAdapter<>(agent);
EvaluationCase testCase = EvaluationCase.builder(
                List.of(Message.text(Role.USER, "What is the weather in Seattle?")))
        .expectedOutput("Sunny")
        .expectedToolCalls(List.of(new ExpectedToolCall(
                "get_weather",
                StateValue.object(Map.of("city", StateValue.string("Seattle"))))))
        .build();

CompletionStage<EvalResults> stage = adapter.evaluateAsync(
        List.of(testCase),
        evaluator,
        new AgentEvaluationOptions("weather eval", 3, RunOptions.empty()),
        cancellation);
```

Repetitions execute in repetition-major, case-minor order. Input messages and all response messages
are preserved in each `EvalItem`.

## Workflow evaluation

Workflow cases carry the actual workflow input separately from the query messages used for
evaluation. A `WorkflowOutputMapper` converts the typed workflow result to response messages.

```java
WorkflowEvaluationAdapter<String, String> adapter =
        new WorkflowEvaluationAdapter<>(workflow, WorkflowOutputMapper.text(value -> value));

CompletionStage<EvalResults> stage = adapter.evaluateAsync(
        List.of(WorkflowEvaluationCase.text("Seattle", "What is the weather?")),
        evaluator,
        WorkflowEvaluationOptions.defaults(),
        cancellation);
```

Built-in output mappers cover text, message lists, and `AgentResponse`.

## Cancellation

The caller-owned `RunCancellation` is passed unchanged to every agent or workflow run, every local
check, and the final evaluator. Cancellation completes the adapter or local-evaluator stage with
`RunCancelledException` and makes a best-effort attempt to cancel an in-flight `CompletableFuture`.
The adapters do not own or close the supplied agent, workflow, evaluator, or cancellation signal.

## Standalone verification

The module includes a local `settings.gradle.kts` only so it can be built and tested before shared
repository integration:

```bash
cd java/agent-framework-evaluation
../gradlew :spotlessApply
../gradlew :test
../gradlew :check
```

The standalone build maps the existing sibling core, tools, agents, workflows, and conformance
projects without editing shared settings.

## Shared build

The module is published with the Java release, constrained by `agent-framework-bom`, and checked by
the shared architecture and publication gates. It remains provider-neutral and does not
reverse-depend on `agent-framework-foundry-evaluations`.

The direct integration dependencies are `agent-framework-agents` and `agent-framework-workflows`;
core and tools arrive transitively.
