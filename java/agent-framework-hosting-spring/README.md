# Agent Framework Spring WebFlux hosting

`agent-framework-hosting-spring` is an optional adapter over the same `HostingRegistry`,
`HostingDispatcher`, `HostingJsonCodec`, `HostingHttpHandler`, and `HostingLimits` used by the
embedded host.

Verified dependency set:

- Spring Boot **4.1.0**
- Spring Framework / WebFlux **7.0.8**
- Reactor Core **3.8.6**

These versions are pinned in `java/gradle/libs.versions.toml` and published in Maven Central. Reactor
is internal adaptation machinery; no Reactor type appears in this module's public signatures.

## Opt in

Routes are absent unless explicitly enabled:

```properties
agent-framework.hosting.enabled=true
server.address=127.0.0.1
```

Provide a pre-populated registry when the application starts:

```java
@Bean
HostingRegistry hostingRegistry(ChatAgent agent, Workflow<String, String> workflow) {
    HostingRegistry registry = new HostingRegistry();
    registry.registerAgent(agent);
    registry.registerWorkflow(workflow, HostingWorkflowCodecs.text());
    return registry;
}
```

The auto-configuration backs off for application beans of type `HostingRegistry`, `HostingLimits`,
`HostingAuthenticator`, `HostingAuthorizer`, `HostingDispatcher`, `HostingHttpServerOptions`,
`HostingJsonCodec`, and `HostingHttpHandler`, and for the named route bean
`agentFrameworkHostingRoutes`.

`HostingAuthenticator` is the pluggable principal/isolation resolver. It must return a
`HostingPrincipal(principalId, isolationId)` based only on trusted application authentication.
The adapter does not install or modify a global Spring Security chain.

## Supported surface

Spring uses the containing application's reactive server and exposes the same `/v1/agents` and
`/v1/workflows` discovery, finite run, SSE stream, finite/SSE resume, and cancellation routes
documented by [`agent-framework-hosting-http`](../agent-framework-hosting-http/README.md).
Request cancellation and disconnect propagate to framework run cancellation. Bodies are aggregated
under `HostingLimits.maxRequestBytes`; SSE remains demand-aware and incremental. The auto-configured
handler is closed with the application context, removing authentication deadlines before its owned
scheduler stops.

**Spring WebSocket is not provided in this release.** A normal request to `/v1/ws` returns `426`.
Use the embedded `HostingHttpServer` for the exact `agent-framework-hosting.v1` WebSocket semantics.
This avoids a second protocol implementation with different buffering, close, or fragmentation
behavior.

## Safe networking properties

The adapter is disabled, loopback-policy, and CORS-off by default. `bind-address` describes the
containing application's listener security boundary; it does not open a listener.

For a trusted reverse proxy deployment, all of the following are required:

```properties
agent-framework.hosting.enabled=true
agent-framework.hosting.bind-address=0.0.0.0
agent-framework.hosting.trusted-tls-proxy=true
agent-framework.hosting.advertised-endpoint=https://agents.example
agent-framework.hosting.allowed-hosts[0]=agents.example
agent-framework.hosting.allowed-origins[0]=https://app.example
```

The application must also provide a non-local `HostingAuthenticator`. The proxy trust assumptions and
limitations are identical to the embedded host. CORS remains disabled unless
`agent-framework.hosting.cors-enabled=true`; enabling it never changes Spring Security.
