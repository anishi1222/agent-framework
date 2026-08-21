# Agent Framework Amazon Bedrock provider

`agent-framework-bedrock` uses AWS SDK v2
`software.amazon.awssdk:bedrockruntime:2.51.4` and the `Converse` /
`ConverseStream` APIs. The AWS default credential provider chain and SigV4 implementation remain
SDK-owned; credentials never enter framework options.

```java
try (BedrockChatClient client = BedrockChatClient.builder()
        .options(BedrockChatClientOptions.builder()
                .region("us-east-1")
                .model("us.anthropic.claude-sonnet-4-6-v1")
                .build())
        .build()) {
    ChatResponse response = client.complete(
            List.of(Message.text(Role.USER, "Hello")), ChatOptions.empty());
}
```

| Surface | Support |
|---|---|
| Finite `Converse`, streaming `ConverseStream`, sync, cancellation | Yes |
| Text, inline images/documents/audio | Yes; remote media URI is rejected |
| Function tools/calls/results | Yes; parallel selection is model-controlled |
| JSON Schema output | `bedrock.responseSchema`; model support required |
| Reasoning, citations, cache usage, guardrail finish/trace flags | Yes |
| Developer role and generic user/store/conversation options | No |

Endpoint overrides require HTTPS and an explicit host allowlist; loopback HTTP is test-only and
explicit. Caller-supplied framework transports and SDK clients used by package tests remain
caller-owned. The AWS SDK is Apache-2.0 licensed.
