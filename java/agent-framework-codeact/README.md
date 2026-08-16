# Agent Framework CodeAct for Java

`agent-framework-codeact` provides a framework-owned, approval-gated CodeAct executor and an
optional `Agent<CodeActResult>` facade. It delegates every process launch, timeout, cancellation,
and per-stream capture operation to `agent-framework-tools-shell`; this module does not implement a
second process runtime.

> [!WARNING]
> Local shell execution is **not a security sandbox**. The workspace checks and `ShellPolicy` are
> spelling-based defense-in-depth controls. Use a container, VM, or equivalent isolation boundary
> for untrusted prompts or generated commands.

## Dependency

Once the module is registered in the Java build:

```kotlin
implementation("com.microsoft.agents:agent-framework-codeact:<version>")
```

The only direct project dependency is `agent-framework-tools-shell`, which supplies the shell,
Agent, Tool, approval, state, run-handle, timeout, and cancellation contracts.

## Bounded execution

Both an approval handler and caller shell policy are required. There is no default approval, no
`NEVER_REQUIRE` path, and no unsafe acknowledgement switch in this module.

```java
Path workspace = Path.of("workspace");
ShellPolicy policy = new ShellPolicy(
        List.of("\\brm\\b"),
        List.of("^(echo|printf|git)\\b"),
        null);

CodeActOptions options = CodeActOptions.builder(workspace)
        .shellPolicy(policy)
        .approvalHandler((request, cancellation) ->
                approvalService.requestApprovalAsync(request, cancellation))
        .maxSteps(8)
        .maxOutputBytes(64 * 1024)
        .timeout(Duration.ofSeconds(30))
        .build();

CodeActProgram program = CodeActProgram.ofCommands(
        "git status --short",
        "printf 'complete'");

try (CodeActExecutor executor = new CodeActExecutor(options)) {
    CodeActResult result = executor.run(program);
    System.out.println(result.transcript());
}
```

Approval is bundled once for the exact immutable program, workspace, and configured bounds. The
approval request uses the framework `ToolApprovalRequest` and `ToolApprovalDecision` contracts and
is digest-bound to prevent a decision for one program from authorizing another.

## Agent facade

`CodeActAgent` adapts an existing structured-output `Agent<CodeActProgram>`:

```java
Agent<CodeActProgram> planner = createStructuredPlanner();

try (CodeActExecutor executor = new CodeActExecutor(options)) {
    Agent<CodeActResult> agent = new CodeActAgent(planner, executor);
    AgentResponse<CodeActResult> response = agent.run("Inspect the workspace.");
}
```

The planner and executor remain caller-owned. The facade never parses free-form model text into a
command and never falls back to unrestricted local execution. Its streaming surface emits one
terminal update; detailed step lifecycle is available through `CodeActEventListener`.

## Safety and determinism

- **Explicit authority:** construction fails without both an approval handler and `ShellPolicy`.
- **Workspace anchoring:** the workspace must already exist and is resolved to its real path.
  Stateless shell steps start there with `HOME`, `USERPROFILE`, and temporary-directory variables
  anchored there.
- **Mandatory confinement checks:** parent traversal, absolute/home-relative paths,
  directory-changing commands, multi-line commands, and variable/command substitution are rejected
  before approval and rechecked by the shell runtime.
- **Bounded work:** `maxSteps`, aggregate UTF-8 `maxOutputBytes`, and one wall-clock `timeout`
  covering approval and execution are always positive.
- **Cancellation:** `RunCancellation` propagates to approval and the shell process tree.
- **Deterministic state:** run IDs, event IDs, approval digests, terminal state, and transcript
  ordering derive from immutable inputs. Measured timestamps and durations are deliberately absent
  from the transcript.
- **Clean runs:** every step uses `ShellMode.STATELESS`; shell variables and process state do not
  persist between steps or runs.

These checks do not prevent an approved command or interpreter from finding another way to access
host resources. Treat approval and external isolation as the security boundaries.

## Shared build

The module is published with the Java release, constrained by `agent-framework-bom`, and checked by
the repository architecture and publication gates. Run
`./gradlew :agent-framework-codeact:check` from `java/` for focused validation.
