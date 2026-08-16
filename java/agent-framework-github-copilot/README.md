# Agent Framework GitHub Copilot provider

`agent-framework-github-copilot` adapts GitHub Copilot sessions to Agent Framework while keeping all
`com.github.copilot` types internal. It uses the **official stable GitHub Copilot SDK for Java
`1.0.9`**:

- [Official Java SDK documentation](https://github.github.io/copilot-sdk-java/latest/)
- [Canonical source (`github/copilot-sdk`, `java/`)](https://github.com/github/copilot-sdk/tree/main/java)
- [Maven Central: `com.github:copilot-sdk-java:1.0.9`](https://central.sonatype.com/artifact/com.github/copilot-sdk-java/1.0.9)

The SDK requires Java 17+, exposes its root API package as `com.github.copilot`, and is developed in
the `github/copilot-sdk` monorepo's `java/` directory. The stable 1.0.9 JAR's JPMS descriptor is
`com.github.copilot.java`. This repository compiles and tests with Java 25. The official
documentation requires Copilot CLI 1.0.55+. Local discovery found CLI 1.0.79. Builds never make an
authenticated model request.

```java
Path workspace = Path.of("/srv/tenant-42/work");
GitHubCopilotClientOptions options = GitHubCopilotClientOptions.builder()
        // Optional: omit this to let the official SDK resolve "copilot" from PATH.
        .cliExecutable(Path.of("/absolute/approved/path/to/copilot"))
        .workingDirectory(workspace)
        .workingDirectoryRoots(Set.of(Path.of("/srv/tenant-42")))
        .useLoggedInUser(true)
        .build();

try (GitHubCopilotClient client = new GitHubCopilotClient(options);
        GitHubCopilotSession session = client.createSessionAsync(
                        GitHubCopilotSessionConfig.builder()
                                .model("gpt-5")
                                .permissionHandler(request -> approveFromApplication(request))
                                .build())
                .toCompletableFuture()
                .join()) {
    GitHubCopilotEvent answer =
            session.sendAndWaitAsync("Review this design.").toCompletableFuture().join();
}
```

## SDK delegation boundary

The official SDK is the sole implementation of Copilot startup handshake, RPC, session persistence,
messages, events, tools, permissions, user input, hooks, MCP, custom agents, skills, BYOK, resume,
list, metadata, delete, abort, model switching, compaction, and session logging. Production contains
no Copilot JSON-RPC client, parser, generated-model substitute, or protocol constants.

Framework-owned types provide only:

- public API isolation from the SDK;
- Agent and ChatClient mapping;
- `CompletionStage` and bounded `Flow.Publisher` adaptation;
- cancellation bridging to `CopilotSession.abort()`;
- deny-by-default permissions and decline-by-default user input;
- request/event/JSON limits, lifecycle policy, and resource ownership;
- flattened model billing, vision, and token limits;
- filtered session listing, O(1) metadata lookup, and client lifecycle subscriptions;
- stable telemetry configuration mapped to `TelemetryConfig`.

`sdkProtocolVersion()` reads `com.github.copilot.SdkProtocolVersion` for diagnostics. Compatibility is
decided only by the official `CopilotClient.start()` handshake and its reported errors.

## CLI launch and ownership

`GitHubCopilotCliLaunchMode.SDK_MANAGED` is the default. The module delegates `cliPath`, working
directory, cleared environment, authentication, idle timeout, telemetry, process startup, transport,
handshake, and shutdown to `CopilotClientOptions` and `CopilotClient`. This is the documented SDK
lifecycle and should be used normally.

`HARDENED_EXTERNAL` is an explicit opt-in for hosts that additionally require bounded stdout/stderr
and descendant-process termination. The small framework launcher starts the CLI in its official
headless server form (`copilot --server`), clears the environment, binds an authenticated loopback
endpoint, and owns the complete process tree. It does **not** read or implement RPC; it supplies
`setCliUrl` and the connection token to the official SDK, which still owns every Copilot semantic.
The tradeoff is extra process code and loopback TCP instead of the SDK's default managed stdio.

`externalServer(...)` is caller-owned. The framework neither starts nor stops that process. It is
restricted to loopback because SDK 1.0.9 uses plaintext TCP for external servers. Telemetry and
authentication for a caller-owned server must be configured on that server.

## Security and lifecycle

- Explicit executable and working-directory roots are canonicalized when configured.
- A supplied environment is allowlisted and delegated to the SDK, whose 1.0.9 process manager clears
  the child environment before applying it.
- Tokens never appear in process arguments or `toString()`. Classic `ghp_` PATs are rejected.
- Stored CLI credentials require explicit `useLoggedInUser(true)`.
- Custom tools always retain SDK permission handling; the framework never selects the SDK's
  permission-skip factory.
- Local MCP uses an exact executable/argument list. HTTP MCP requires HTTPS unless loopback HTTP is
  explicitly enabled.
- Caller-owned executors and clients are not closed. Framework-owned clients, executors, sessions,
  subscriptions, and optional hardened processes are closed deterministically.

CLI session files remain external Copilot storage, not Agent Framework Java session wire format.
`GitHubCopilotAgent` stores only the external session ID in `AgentSession` metadata. Multi-user hosts
must partition CLI home/runtime state and framework session storage per authenticated principal.

When `ChatOptions.conversationId` is present, `GitHubCopilotChatClient` sends only the newest user
message because the resumed Copilot session already owns its history. Configure tools on
`GitHubCopilotSessionConfig`; an outer framework tool loop is rejected to prevent double execution.

## Limitations

- The official documentation labels the Java SDK as public preview even though `1.0.9` is the
  current stable Maven release; later minor releases may require facade updates.
- SDK external-server TCP is plaintext and therefore restricted to loopback.
- CLI persistence and memory remain external Copilot state, not Agent Framework session wire data.
- Experimental SDK types are not exposed. The facade intentionally covers stable 1.0.9 operations
  and preserves unknown SDK events as bounded framework-owned JSON.
