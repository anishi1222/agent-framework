# Agent Framework Mistral provider

`agent-framework-mistral` is a strict Java 25 JDK `HttpClient` adapter for the current Mistral
`POST /v1/chat/completions` JSON/SSE API. Mistral's current SDK page lists official Python and
TypeScript clients only, and Maven Central has no `ai.mistral` Java artifact.

```java
try (MistralChatClient client = MistralChatClient.builder()
        .options(MistralChatClientOptions.builder()
                .apiKey(System.getenv("MISTRAL_API_KEY"))
                .model("mistral-large-latest")
                .build())
        .build()) {
    ChatResponse response = client.complete(
            List.of(Message.text(Role.USER, "Hello")), ChatOptions.empty());
}
```

| Surface | Support |
|---|---|
| Finite / SSE streaming / sync / explicit cancellation | Yes |
| Text and HTTPS/data-URI images | Yes |
| Documents / audio / developer role | No; rejected before transport |
| Function tools, correlated calls/results, parallel calls | Yes |
| JSON Schema output | `mistral.responseSchema` |

Remote endpoints require HTTPS and an explicit host allowlist. Redirects are never followed.
Requests, responses, SSE events, JSON depth/string/collection sizes, concurrency, and timeouts are
bounded. Duplicate JSON keys and trailing content are rejected. Caller-supplied `HttpClient`,
executor, and transport instances are not closed unless transport ownership is explicitly
transferred.
