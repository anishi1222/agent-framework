# Agent Framework Ollama provider

`agent-framework-ollama` implements Ollama's native `POST /api/chat` JSON/NDJSON protocol with JDK
`HttpClient`.

```java
try (OllamaChatClient client = OllamaChatClient.builder()
        .options(OllamaChatClientOptions.builder().model("qwen3").build())
        .build()) {
    ChatResponse response = client.complete(
            List.of(Message.text(Role.USER, "Hello")), ChatOptions.empty());
}
```

| Surface | Support |
|---|---|
| Finite / NDJSON streaming / sync / cancellation | Yes |
| Text and inline base64 image input | Yes |
| Remote image URI, document, audio, developer role | No |
| Function tools/calls/results | Yes; missing provider IDs receive explicit synthetic-ID metadata |
| JSON Schema output / thinking | `ollama.responseSchema` / `ollama.think` |

The default endpoint is loopback HTTP (`127.0.0.1:11434`). Non-loopback endpoints require HTTPS and
an explicit host allowlist; redirects are never followed. A bearer token is optional for protected
compatible gateways and is always redacted. JSON, byte, event, concurrency, and timeout limits are
strict.
