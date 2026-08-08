# Agent Framework MCP hosting

`agent-framework-hosting-mcp` exposes Agent Framework `FunctionTool`, `Agent<?>`, prompt, and
resource instances through the official MCP Java SDK `2.0.0`. It depends inward on
`agent-framework-agents` and `agent-framework-mcp`; shared runtime modules do not depend on hosting.

## Stdio

```java
FunctionTool weather = FunctionTool.create(metadata, handler);

MCPServerHandle server = MCPServer.builder("weather-mcp", "1.0.0")
        .instructions("Use weather_get_forecast for location forecasts.")
        .tool(weather)
        .build()
        .startStdio();
```

The official SDK owns MCP sessions and protocol handling. A framework transport supplies bounded
newline-delimited framing because the SDK 2.0.0 stdio provider uses unbounded sinks. Stdout is
reserved for JSON-RPC; diagnostics belong on stderr. The handle owns the SDK server and its
virtual-thread executor, but not the caller-supplied tool.

## Streamable HTTP/SSE

```java
MCPServer definition = MCPServer.builder("research-mcp", "1.0.0")
        .agent(MCPAgentTool.builder(agent)
                .name("research_run")
                .description("Runs the research agent for one focused request.")
                .build())
        .build();

try (MCPStreamableHTTPServer server =
        definition.startStreamableHTTP(MCPStreamableHTTPServerOptions.builder().build())) {
    System.out.println(server.endpoint());
}
```

The embedded host binds to `127.0.0.1` and an ephemeral port by default, validates Host and Origin
headers, rejects oversized/chunked payloads, caps retained sessions at 128 by default, and disables
arbitrary remote binding. Session slots are released by MCP DELETE; an abandoned client retains one
bounded slot until restart. A non-loopback listener requires `behindTrustedTLSProxy(true)`, explicit
Host/Origin allowlists, and a trusted reverse proxy that terminates HTTPS. The embedded host does not
terminate TLS directly.

## Prompts and resources

```java
MCPServer server = MCPServer.builder("docs-mcp", "1.0.0")
        .prompt(new MCPServerPrompt(
                "docs_explain",
                "Builds a documentation explanation prompt.",
                List.of(new MCPPromptArgument("topic", "Topic to explain.", true)),
                arguments -> CompletableFuture.completedFuture(promptResult)))
        .resource(new MCPServerResource(
                resourceDescriptor,
                uri -> CompletableFuture.completedFuture(resourceResult)))
        .build();
```

Framework-owned rich content maps to MCP text, image, audio, embedded resource, and resource-link
blocks. Tool outputs include both readable text and `structuredContent`, and declared output schemas
are sent to clients.

## Execution and boundaries

- MCP input schema validation is enabled in the official SDK. Framework tool binding and output
  validation remain active.
- `MCPLimits` bounds request bytes, JSON depth/collections, concurrent tool/agent/prompt/resource
  handlers, and registered primitives. Calls time out at `MCPServer.Builder.callTimeout(...)`.
- A progress token receives at most two notifications per hosted call (started/completed), so
  notification production is bounded.
- Tools marked `ALWAYS_REQUIRE` return an explicit `isError` result with
  `code=approval_required`; they are never executed implicitly.
- Agent approval or input-required continuations return an explicit `isError` result. Process-local
  continuation identifiers are not sent across the MCP boundary.
- MCP `tools/call` has one final result. Agent update streams are not represented as partial tool
  results.
- Registry list responses are one bounded page because the pinned SDK's built-in server registry
  does not provide cursor hooks.
- Protocol-level cancellation, durable MCP tasks, replay after disconnect, cross-process resume,
  generic HTTP/WebSocket hosting, A2A, and AG-UI are not implemented here.
