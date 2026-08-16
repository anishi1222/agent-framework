# Agent Framework MCP client

`agent-framework-mcp` is the Java 25 client and tool-adaptation boundary for the Model Context
Protocol. It pins the official `io.modelcontextprotocol.sdk:mcp:2.0.0` SDK as an implementation
dependency. MCP SDK, Reactor, Jackson, and JSON-RPC types do not appear in the public API.

## Connect and discover tools

Local servers use a literal executable and argument vector. No shell command string is evaluated:

```java
MCPStdioTransport transport = MCPStdioTransport.builder("node")
        .arguments(List.of("server.js", "--stdio"))
        .inheritedEnvironmentAllowlist(Set.of("HOME", "PATH"))
        .build();

try (MCPClient client = MCPClient.create(transport)) {
    MCPInitialization initialization = client.initialize();
    List<MCPToolDescriptor> tools = client.listTools();
}
```

Remote servers use Streamable HTTP. HTTPS, an exact host allowlist, and redirects disabled are the
defaults. Plain HTTP requires an explicit loopback-only development opt-in:

```java
MCPStreamableHTTPTransport transport =
        MCPStreamableHTTPTransport.builder(URI.create("https://mcp.example.com/mcp"))
                .allowedHosts(Set.of("mcp.example.com"))
                .header("Authorization", "Bearer " + token)
                .build();

try (MCPClient client = MCPClient.create(transport)) {
    MCPToolResult result = client.callTool(
            "docs_search",
            StateValue.object(Map.of("query", StateValue.string("Streamable HTTP"))));
}
```

`MCPClient` owns the SDK client, HTTP session, or child process. `closeAsync()` and `close()` are
idempotent. Stdio close terminates the complete child process tree after the configured deadline.
Caller-provided agents and tools on the server side are never closed by this module.

## Protocol surface

The public facade provides:

- explicit initialization and negotiated server capabilities;
- cursor pages and bounded aggregate traversal for tools, prompts, resources, and resource
  templates;
- tool calls with structured output, rich text/image/audio/embedded-resource/resource-link content,
  progress tokens, correlation metadata, per-call timeout, and framework cancellation;
- prompt resolution, resource reads and subscriptions, roots, and logging levels;
- a bounded single-subscriber `Flow.Publisher<MCPClientEvent>` for progress, logging, list-change,
  resource-update, and URL-elicitation completion notifications; and
- opt-in sampling, form elicitation, and HTTPS URL elicitation callbacks. These capabilities are not
  advertised without an explicit handler. Sampling is capped at 4,096 requested tokens and 25
  requests per client lifetime by default. The request count is cumulative for the entire
  `MCPClient` lifetime and is not reset per operation, transport session, or reconnect. Create a new
  client to start a new sampling-request budget.

All JSON-shaped values use `StateValue`. `MCPLimits` bounds payload size, nesting, collections,
cursor traversal, concurrent requests, and event buffering.

## Adapt remote tools

```java
MCPClientOptions options = MCPClientOptions.builder()
        // Remote tools require approval by default. Opt out only after establishing trust.
        .remoteToolApprovalMode(ToolApprovalMode.NEVER_REQUIRE)
        .build();

try (MCPClient client = MCPClient.create(transport, options)) {
    List<FunctionTool> tools = client.asFunctionToolsAsync("github")
            .toCompletableFuture()
            .join();
}
```

The adapter preserves exact remote names in call dispatch, descriptions, input schemas, output
schemas, and call IDs. Exposed function names use deterministic ASCII normalization and stable
suffixes for collisions. Remote tool errors become actionable `ToolUserException` failures instead
of success-shaped results. Raw `MCPToolDescriptor` schemas preserve JSON values exactly; adaptation
to `FunctionTool` is limited to the safe JSON Schema vocabulary accepted by
`agent-framework-tools` and fails explicitly for unsupported reference/polymorphic schemas.

## Discover Agent Skills

`MCPSkillsSource` implements SEP-2640 discovery from `skill://index.json`:

```java
try (MCPClient client = MCPClient.create(transport);
        MCPSkillsSource source = new MCPSkillsSource(client)) {
    SkillsProvider provider = new SkillsProvider(source);
}
```

`skill-md` entries use index metadata immediately and fetch `SKILL.md` plus safe relative sibling
resources on demand. Successful skill-document reads are cached; missing resources return no
resource, while non-not-found MCP failures propagate.

`archive` entries accept ZIP, TAR, and TAR.GZ resources. Archive magic bytes take precedence over
media type and URL suffix. Extraction uses configurable compressed/uncompressed byte and file-count
limits, rejects path escape, skips TAR links, serializes reconciliation, prunes stale skills, and
cleans partial extraction. Archive scripts are never executable; callers may opt specific script
extensions into the readable-resource extension set instead. `mcp-resource-template` entries are
intentionally deferred. See `MCPSkillsSourceOptions` for limits and directory ownership.

## Security and unsupported behavior

- Child environments are cleared and rebuilt from an inheritance allowlist plus explicit values.
  Working directories require an allowed-root policy.
- HTTP user info, query/fragment endpoints, redirects, host changes, and non-loopback plaintext are
  rejected.
- Header values and child stderr are redacted from public diagnostics.
- Only JSON primitives, lists, and string-keyed maps are converted. No polymorphic object
  deserialization is used.
- Legacy HTTP+SSE, WebSocket, automatic OAuth, dynamic authorization refresh, MCP experimental
  durable tasks, and protocol-level cancellation are not exposed by this release.
- Streamable HTTP may reconnect at the pinned SDK transport layer, but this adapter does not retry
  tool calls or claim event replay, exactly-once delivery, or cross-process resume. Event-ID
  resumability is disabled because complete replay is not available in the pinned SDK.
