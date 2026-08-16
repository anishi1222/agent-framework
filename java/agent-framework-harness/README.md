# Agent Framework harness for Java

`agent-framework-harness` provides Java 25 APIs for assembling a bounded autonomous agent on top of
the provider-neutral `ChatAgent` runtime.

```kotlin
implementation("com.microsoft.agents:agent-framework-harness:<version>")
```

## Create a harness

Todo tracking, plan/execute modes, and session-isolated file memory are enabled by default. The
example uses an in-memory store so it does not create the default `./agent-file-memory` directory:

```java
HarnessAgentOptions options = HarnessAgentOptions.builder()
        .agentInstructions("Keep the implementation and todos synchronized.")
        .fileMemoryStore(new InMemoryAgentFileStore())
        .loopEvaluators(List.of(new CompletionMarkerLoopEvaluator("TASK_COMPLETE")))
        .loopOptions(LoopAgentOptions.builder()
                .maxIterations(5)
                .returnFinalOnly(true)
                .build())
        .build();

try (HarnessAgent agent = HarnessAgents.create(chatClient, options)) {
    AgentResponse<Void> response = agent.run(
            "Implement the requested change and end with TASK_COMPLETE.");
}
```

The deterministic provider order is history, optional compaction, todo, mode, file memory, shared
file access, skills, background agents, then caller providers. Disable or replace built-ins through
`HarnessAgentOptions`.

## Autonomous loop

Autonomous execution is enabled only when at least one evaluator is configured. Built-in
evaluators support completion markers, remaining todos, incomplete background tasks, and a
separate AI judge. Evaluators run in order; the first continuation decision wins. The loop stops
when all evaluators stop or the hard `maxIterations` cap is reached.

Finite execution may return only the final iteration with `returnFinalOnly(true)`. Streaming always
publishes every iteration and synthesized continuation message as it happens. Function calls,
function results, and final assistant text are retained when constructing the response evaluated
for an iteration. Approval-required results are returned immediately and are never automatically
continued.

`freshContextPerIteration(true)` restores the original session snapshot before each reinvocation.
Use it only when intentionally isolating iteration-local history; provider state restored from the
snapshot follows the same behavior. Process-local background-task records are retained because a
live child execution cannot be rewound safely.

## Optional providers

- Configure `fileAccessStore` for shared file tools. Reads and writes require approval by default;
  bypass approval only for a trusted store and caller.
- Configure `skillPaths` or a `SkillsProvider` to expose Agent Skills.
- Configure `backgroundAgents` or a `BackgroundAgentsProvider` to expose
  start/wait/result/continue/clear tools. Serializable task metadata persists in the parent
  session, while futures and child sessions remain process-local and are excluded from automatic
  `SessionStore` persistence. A restored task that was `RUNNING` without a runtime handle becomes
  `LOST`. Caller cancellation records `CANCELLED`; closing the provider records active work as
  `LOST`. Both are terminal states.
- Use `loopOnTodos(...)` or `loopOnBackgroundTasks(true)` to install the corresponding evaluator
  with its required provider.
- Add `AIJudgeLoopEvaluator` only with a judge `ChatClient` trusted to receive the original request
  and latest response. The evaluator requests strict structured output and uses explicit verdict
  markers only as a fallback.

## File and lifecycle safety

`FileSystemAgentFileStore` confines UTF-8 files beneath one configured root, rejects absolute paths,
`.`/`..` segments, Windows drive paths, and symbolic-link traversal, and uses atomic replacement
when supported. It fails closed when the underlying file system does not expose
`SecureDirectoryStream`, because path-based fallback writes cannot prevent symbolic-link races.
Search expressions use RE2-compatible regular expressions, so backreferences and
lookarounds are unsupported. Search cancellation and deadlines are cooperative: a timed-out scan
terminates before the next serialized file operation starts. File memory is isolated by session;
shared file access is not. On file-system providers without `SecureDirectoryStream`, confinement
uses path checks rather than descriptor-relative traversal and therefore cannot provide the same
resistance to concurrent symbolic-link replacement. The JDK has no descriptor-relative directory
creation API; newly created directories are reopened and validated through secure descriptors
before file content is read or written.

`HarnessAgent` owns providers it constructs internally and closes them with its runtime. Caller
supplied `ChatClient`, stores, providers, background agents, and judge clients remain caller-owned.
Always close the harness and any caller-owned resources.
