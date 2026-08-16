# Agent Framework shell tools for Java

`agent-framework-tools-shell` provides framework-owned Java 25 APIs for approval-gated host-local
and Docker-compatible shell execution. It supports persistent and stateless modes, bounded UTF-8
stdout/stderr, timeout and cancellation, deny-first command policy, and cached shell-environment
instructions for agents.

```kotlin
implementation("com.microsoft.agents:agent-framework-tools-shell:<version>")
```

## Local execution

```java
LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
        .mode(ShellMode.STATELESS)
        .workingDirectory("/workspace")
        .timeout(Duration.ofSeconds(30))
        .maxOutputBytes(64 * 1024)
        .build();

try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
    FunctionTool tool = shell.asFunctionTool();
    ShellResult result = shell.run("git status --short");
}
```

The default `FunctionTool` requires approval for every invocation and advertises both `FUNCTION`
and `SHELL` capabilities. Disabling approval for host execution requires
`acknowledgeUnsafe(true)` as well as an explicit `NEVER_REQUIRE` approval mode. A `ShellPolicy` is a
spelling-based user-experience guardrail, not a security boundary.

Persistent local mode retains shell environment and working-directory state. By default each
command is re-anchored to the configured working directory; set `confineWorkingDirectory(false)`
only when intentional directory persistence is required. A timeout terminates the persistent
session rather than preserving potentially inconsistent state.

## Container execution

```java
DockerShellExecutorOptions options = DockerShellExecutorOptions.builder()
        .mode(ShellMode.STATELESS)
        .hostWorkingDirectory("/workspace")
        .build();

try (DockerShellExecutor shell = new DockerShellExecutor(options)) {
    ShellResult result = shell.run("git status --short");
}
```

Container defaults disable networking, use UID/GID `65532:65532`, make the root filesystem
read-only, drop all capabilities, enable `no-new-privileges`, limit memory to 512 MiB and processes
to 256, and provide a bounded `/tmp`. These defaults reduce risk but do not make arbitrary code
safe. Keep approval enabled for untrusted commands and use an appropriate external isolation
boundary.

`DockerShellExecutor` builds Docker-compatible argument vectors without retokenizing command text.
Stateless runs receive unique container names so timeout and cancellation cleanup can force-remove
daemon-side containers after terminating the local runtime client. Live execution requires a
reachable compatible daemon; unit and conformance tests do not require one.

## Environment context

`ShellEnvironmentProvider` probes the shell identity, working directory, and configured CLI
versions once, then caches deterministic POSIX or PowerShell instructions for subsequent agent
runs. CLI names are restricted before interpolation, probe failures are represented as missing
values, and caller cancellation still propagates.
