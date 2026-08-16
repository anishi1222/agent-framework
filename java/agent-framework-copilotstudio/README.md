# Agent Framework Copilot Studio provider

`agent-framework-copilotstudio` implements the Power Platform **Direct-to-Engine** Copilot Studio
client used by the official .NET and Python Microsoft 365 Agents SDK packages. Microsoft currently
publishes no Copilot Studio Java client. The verified wire is
`2022-03-01-preview` over redirect-free HTTPS plus Server-Sent Events; the Python fixture source is
`microsoft-agents-copilotstudio-client 1.3.0`.

This is not the Bot Framework Direct Line channel. Direct Line remains an alternative for custom
channels, but the .NET/Python Agent Framework adapters use the authenticated
`/copilotstudio/.../bots/{bot}/conversations` Direct-to-Engine API. Consequently resumption uses SSE
`Last-Event-ID`, not a Direct Line numeric watermark.

```java
CopilotStudioClientOptions options = CopilotStudioClientOptions.builder()
        .tenantId("11111111-1111-1111-1111-111111111111")
        .environment("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "cr123_supportAgent")
        .allowedHosts(Set.of(
                "aaaaaaaabbbbccccddddeeeeeeeeee.ee.environment.api.powerplatform.com"))
        .build();

try (CopilotStudioClient client = CopilotStudioClient.builder()
        .options(options)
        .tokenProvider(cancellation -> acquirePowerPlatformToken(
                options.tokenAudience(), cancellation))
        .build()) {
    CopilotStudioConversation conversation =
            client.startConversationAsync().toCompletableFuture().join();
    List<CopilotStudioEvent> events = client.sendActivityAsync(
                    conversation.conversationId(),
                    CopilotStudioActivity.message(
                            UUID.randomUUID().toString(),
                            conversation.conversationId(),
                            "Hello"),
                    conversation.cursor(),
                    new DefaultRunCancellation())
            .toCompletableFuture()
            .join();
}
```

## Wire and security guarantees

- Tenant, environment, bot, endpoint, scheme, and host allowlist validation happens before I/O.
- Remote endpoints require HTTPS; HTTP is limited to an explicit loopback test/development opt-in.
  `HttpClient.Redirect.NEVER` is mandatory for caller-owned clients.
- Entra access tokens are refreshed before expiry, stay in the authorization header, and are never
  placed in a URI, exception, or `toString`.
- SSE lines/events, requests, JSON depth/string/collection sizes, remembered IDs, buffers, and
  concurrency are bounded. JSON rejects duplicate members, trailing content, non-finite numbers, and
  implicit polymorphism.
- Cancellation closes the response body and stops send, poll, subscribe, and chat streams. The
  module closes only its own HTTP client and executor.
- Cursor processing rejects duplicate and numerically out-of-order SSE IDs and duplicate activity
  IDs. Cards, attachments, citations, typing, message updates, end, and error activities remain
  framework-owned values.
- OAuth/sign-in cards and Adaptive Card input/actions produce explicit `OAUTH_REQUIRED` or
  `INPUT_REQUIRED` events. The module never follows links, signs in, or executes card actions.

## Sessions and hosting

`CopilotStudioAgent` stores conversation ID, `Last-Event-ID`, cursor sequence, and stable submitted
activity IDs in `AgentSession` metadata using atomic state updates. A durable host must save the
session through its compare-and-set `SessionStore`. Only the newest user activity is transmitted on
each turn; previous framework history is not replayed.

A conversation ID is continuation state, not authorization. Hosts must partition sessions per
authenticated principal, bind every load/resume/delete to that principal, and apply retention and
quota policy. Stable activity IDs are reserved before transport to prevent duplicate submission in
one session; a process failure after reservation but before acknowledgement can require explicit
operator/application reconciliation.

## Limitations

Direct-to-Engine is currently documented through official SDK implementations rather than a separate
public OpenAPI document, and its API version still carries a preview suffix. This module pins the
verified version and fails strict wire drift. WebSocket and Direct Line token exchange are not
claimed. The obsolete/internal subscribe endpoint is exposed only as an SSE reconnect/poll primitive
with `Last-Event-ID`.
