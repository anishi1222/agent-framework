# Agent Framework Gemini provider

`agent-framework-gemini` uses the official stable
`com.google.genai:google-genai:1.65.0` SDK and supports Gemini Developer API keys or Vertex AI
Application Default Credentials through framework-owned options.

```java
try (GeminiChatClient client = GeminiChatClient.builder()
        .options(GeminiChatClientOptions.builder()
                .apiKey(System.getenv("GOOGLE_API_KEY"))
                .model("gemini-2.5-flash")
                .build())
        .build()) {
    ChatResponse response = client.complete(
            List.of(Message.text(Role.USER, "Hello")), ChatOptions.empty());
}
```

| Surface | Support |
|---|---|
| Finite / streaming / sync / cancellation | Yes |
| Text, inline data, HTTPS or `gs:` file URIs | Yes |
| Images, documents, audio, video | Yes |
| Function calls/results and model-controlled parallel calls | Yes |
| JSON Schema output | `gemini.responseSchema` |
| Thinking/signatures, grounding, prompt/candidate safety, cache usage | Yes |
| Developer role, user/store/conversation options | No |

The owned OkHttp client disables HTTP and HTTPS redirects and enforces endpoint, request, response,
event, timeout, and concurrency limits. SDK types never appear in public signatures. The Google SDK
is Apache-2.0 licensed.
