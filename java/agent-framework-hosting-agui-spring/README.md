# Agent Framework AG-UI Spring Hosting

This optional module attaches the same `AGUIHostingHttpHandler`, `AGUIJsonCodec`,
`HostingDispatcher`, and generic transport policy to Spring Boot WebFlux.

Enable both shared hosting and AG-UI routing:

```properties
agent-framework.hosting.enabled=true
agent-framework.hosting.agui.enabled=true
```

Register exact routes on the `AGUIHostingRegistry`. The default WebFlux predicate covers `/ag-ui`
and `/ag-ui/**`.

The adapter:

- keeps Reactor and Spring types out of public AG-UI/core APIs;
- reuses the existing generic `HostingHttpHandler` and application `HostingAuthenticator`;
- accepts an `AGUIPrincipalResolver` for trusted principal/isolation mapping;
- does not install or mutate Spring Security or global CORS;
- uses Reactor Netty and excludes embedded Tomcat at runtime;
- propagates disconnect cancellation and continuation delivery cleanup.

WebSocket hosting, direct TLS, replay, and durable continuation are not claimed.
