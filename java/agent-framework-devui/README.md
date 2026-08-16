# Agent Framework Java Developer UI

`agent-framework-devui` is an opt-in embedded developer UI for the framework-owned generic hosting
contract. It serves bounded HTML, CSS, and JavaScript resources from the module JAR and mounts the
existing generic JSON and SSE routes on the same origin.

The module does not use a browser framework, package manager, external CDN, remote asset, or
additional application API. WebSocket hosting is intentionally not mounted by this adapter.

## Usage

Register targets with generic hosting, then explicitly start the developer UI:

```java
HostingLimits limits = HostingLimits.defaults();
HostingRegistry registry = new HostingRegistry();
registry.registerAgent(agent);

try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
        DevUIServer devUI = DevUIServer.start(
                dispatcher,
                DevUIServerOptions.builder()
                        .limits(limits)
                        .build())) {
    System.out.println(devUI.endpoint());
}
```

Defaults bind `127.0.0.1` on an ephemeral port. The UI is available at `/devui/`; its relative
configuration points to the same-origin `/v1` generic hosting API. Supported UI traffic is limited
to the generic discovery, finite JSON run, cancellation/resume JSON, and SSE run routes already
implemented by `agent-framework-hosting-http`.

## Non-loopback binding

Remote binding is deliberately difficult and requires all of the following:

- `allowNonLoopback(true)`
- `TRUSTED_TLS_PROXY`
- an explicit HTTPS advertised origin
- explicit Host and Origin allowlists
- an application authenticator rather than the local-only authenticator

```java
DevUIServerOptions options = DevUIServerOptions.builder()
        .bindAddress(InetAddress.getByName("0.0.0.0"))
        .allowNonLoopback(true)
        .transportSecurity(DevUITransportSecurity.TRUSTED_TLS_PROXY)
        .advertisedEndpoint(URI.create("https://devui.example.com"))
        .allowedHosts(Set.of("devui.example.com"))
        .allowedOrigins(Set.of("https://devui.example.com"))
        .authenticator(applicationAuthenticator)
        .build();
```

The trusted proxy must terminate TLS, prevent direct access to the listener, sanitize forwarding
headers, and apply the configured identity contract to both assets and API requests. The adapter
does not create that network boundary.

## Security behavior

- Static paths are exact classpath-map lookups; no request value is converted to a filesystem path.
- Percent-encoded, duplicate-separator, backslash, dot-segment, query-bearing, and oversized paths
  are rejected.
- Asset and configuration requests pass through generic Host, Origin, proxy, peer, and
  authentication validation.
- UI responses use a self-only CSP plus `nosniff`, clickjacking, referrer, permissions, and
  cross-origin isolation headers.
- Assets and runtime configuration are `no-store`.
- CORS is not enabled; the browser configuration is relative and same-origin.
- Request headers, request bodies, concurrent requests, SSE buffering, run duration, and shutdown
  use framework-owned finite limits.

## Dependencies

Production dependencies are limited to:

- `agent-framework-hosting` for registry/dispatcher contracts and limits
- `agent-framework-hosting-http` for the existing generic JSON/SSE handler and security contract
- JDK `jdk.httpserver` for the embedded listener

The Tomcat implementation dependencies of `agent-framework-hosting-http` are excluded because this
adapter uses only the framework-neutral handler types.

## Shared build

The module is published with the Java release, constrained by `agent-framework-bom`, and checked by
public-signature isolation. Run `./gradlew :agent-framework-devui:check` from `java/` for focused
validation.
