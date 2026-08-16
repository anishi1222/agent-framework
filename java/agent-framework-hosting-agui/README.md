# Agent Framework AG-UI Hosting

`agent-framework-hosting-agui` maps exact AG-UI HTTP/SSE routes onto the shared
`HostingRegistry`/`HostingDispatcher`. Agent, Workflow, and Orchestration execution therefore uses
the generic authorization, run limits, bounded publishers, cancellation, and process-local
continuation registry instead of a second execution engine.

## Endpoint

AG-UI does not mandate a universal URL. `AGUIHostingRegistry.DEFAULT_PATH` is `/ag-ui`; applications
may register another exact path.

- Request: `POST application/json` with exact `RunAgentInput`
- Response: `200 text/event-stream`
- Optional extension: `GET <route>/capabilities` with
  `application/vnd.microsoft.agent-framework.agui-capabilities+json`
- `OPTIONS` is served only when the shared transport options enable CORS with an explicit origin
  allowlist.

```java
HostingRegistry generic = new HostingRegistry();
AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
routes.registerAgent("/ag-ui", agent);

try (HostingDispatcher dispatcher = new HostingDispatcher(generic, limits);
     AGUIThreadStore threads = new InMemoryAGUIThreadStore(1_000, Duration.ofMinutes(30));
     AGUIHttpServer server = AGUIHttpServer.start(new AGUIHostingHttpHandler(
         dispatcher, routes, threads, httpOptions, AGUIHostingOptions.defaults(), codec))) {
    // server.endpoint().resolve("/ag-ui")
}
```

## Isolation and continuation

Thread state keys include authenticated principal, independent isolation, route kind/id, and
`threadId`. The caller’s `threadId` and `runId` are correlation only. The default same-thread policy
rejects concurrent runs. `AGUIThreadStore` is a caller-replaceable CAS SPI; the in-memory
implementation is capacity bounded and TTL expiring.

Generic approval/input continuations become official
`RUN_FINISHED { outcome: { type: "interrupt", interrupts: [...] } }` events. Resume validates
principal, isolation, route, thread, host run, every interrupt ID, type, TTL, and one-time token.
The token is process-local and never claims checkpoint durability or replay. Workflow checkpoints
are emitted as namespaced custom events unless an actual continuation exists; state is never
fabricated.

## Security and limits

- loopback HTTP by default;
- non-loopback requires the shared trusted-TLS-proxy, authenticator, Host, and Origin policy;
- bounded request/header/event/response/concurrency/run/idle limits;
- disconnect and timeout cancel the production run;
- no direct TLS termination, WebSocket, `Last-Event-ID` replay, or cross-process resume.
