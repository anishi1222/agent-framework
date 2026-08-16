# Agent Framework Anthropic provider

`agent-framework-anthropic` implements `ChatClient` with the official stable
`com.anthropic:anthropic-java:2.53.0` Messages SDK on Java 25.

```java
try (AnthropicChatClient client = AnthropicChatClient.builder()
        .options(AnthropicChatClientOptions.builder()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-sonnet-4-6")
                .build())
        .build()) {
    ChatResponse response = client.complete(
            List.of(Message.text(Role.USER, "Hello")), ChatOptions.empty());
}
```

## Support

| Surface | Support |
|---|---|
| Finite / streaming / sync / `RunHandle` cancellation | Yes |
| System, user, assistant, tool roles | Yes; developer is rejected |
| Text, HTTPS/base64 images, HTTPS/base64 PDFs, inline UTF-8 documents | Yes |
| Audio | No |
| Function calls/results and parallel calls | Yes |
| JSON Schema output | `anthropic.responseSchema` metadata option |
| Thinking / signatures and cache-token usage | Yes |
| Text-block citation summaries | Finite responses; unsupported streaming citation deltas fail explicitly |
| Server tools (web search/fetch, code execution, tool search, container upload) | Not admitted; unexpected output blocks fail with `unsupported_output_block` |

The SDK runs through a redirect-free, bounded JDK HTTP implementation of its supported `HttpClient`
SPI. API keys and raw error bodies are never retained in exceptions or diagnostics. Caller-injected
framework transports and executors remain caller-owned. This is a Messages adapter, not a Claude
Agent SDK implementation.

Only framework `FUNCTION` tools are admitted by request validation. The current SDK also models
server-tool and server-result output variants, but this adapter does not request or execute those
capabilities. If the service nevertheless returns one, finite and streaming paths fail with its
non-secret block type instead of silently dropping provider output.

The Anthropic SDK is MIT licensed.
