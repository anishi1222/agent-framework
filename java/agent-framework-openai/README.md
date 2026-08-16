# Agent Framework OpenAI provider

`agent-framework-openai` adapts the framework-owned chat and embedding contracts to OpenAI. It
compiles and runs on Java 25 without preview APIs.

The implementation uses the official stable `com.openai:openai-java:4.50.0` SDK behind an internal
adapter. No `com.openai.*` type appears in a public or protected signature.

## Dependency

Use the Agent Framework BOM so all framework modules have one version:

```kotlin
dependencies {
    implementation(platform("com.microsoft.agents:agent-framework-bom:0.1.0-SNAPSHOT"))
    implementation("com.microsoft.agents:agent-framework-openai")
}
```

## Create a client

Explicit credentials are preferred:

```java
OpenAIChatClientOptions options = OpenAIChatClientOptions.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("gpt-5.4")
        .build();

try (OpenAIChatClient client = OpenAIChatClient.builder()
        .options(options)
        .build()) {
    ChatResponse response = client.completeAsync(
            List.of(Message.text(Role.USER, "Hello")),
            ChatOptions.empty())
        .toCompletableFuture()
        .join();
    System.out.println(response.text());
}
```

When `apiKey` is omitted, the SDK's environment configuration is used, including
`OPENAI_API_KEY`. `OpenAISecret`, client-option diagnostics, and provider exceptions never print or
retain credential values. A custom absolute `baseUrl`, organization, project, timeout, and retry
limit can be set on `OpenAIChatClientOptions`.

## Generate embeddings

`OpenAIEmbeddingClient` implements the provider-neutral
`EmbeddingClient<String, FloatEmbeddingVector, OpenAIEmbeddingOptions>` contract:

```java
OpenAIEmbeddingClientOptions options = OpenAIEmbeddingClientOptions.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("text-embedding-3-small")
        .build();

try (OpenAIEmbeddingClient client = OpenAIEmbeddingClient.builder()
        .options(options)
        .build()) {
    GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions> generated =
            client.generateAsync(List.of("first document", "second document"))
                    .toCompletableFuture()
                    .join();
    System.out.println(generated.get(0).vector().values());
}
```

The client automatically sends sequential batches of at most 2,048 inputs, restores caller input
order from provider indexes, folds token usage, validates dimensions across batches, and propagates
explicit cancellation to the active SDK request. Float-array and base64 little-endian SDK
responses are supported. An empty input list completes without a provider call.

## Streaming and cancellation

`completeStreaming` returns a cold, single-subscriber `Flow.Publisher<ChatResponseUpdate>`. Work
starts on subscription. Demand is validated, downstream cancellation propagates to the provider,
and undelivered updates are bounded by `maxBufferedUpdates` (default `256`). Exceeding that bound
fails with `OpenAIStreamingBufferOverflowException` and cancels the provider stream.

Use `DefaultRunCancellation` or another `RunCancellation` when the caller needs explicit control:

```java
DefaultRunCancellation cancellation = new DefaultRunCancellation();
Flow.Publisher<ChatResponseUpdate> updates =
        client.completeStreaming(request, cancellation);
```

Closing `OpenAIChatClient` cancels active operations. The default SDK transport is client-owned and
closed with the client. An injected `OpenAITransport` is caller-owned unless
`transport(transport, true)` explicitly transfers ownership.

## Mapping coverage

The adapter maps:

- system, developer, user, assistant, and tool history;
- text plus user image/file URI or data content;
- provider-neutral temperature, top-p, output-token, tool-choice, parallel-call, metadata,
  storage, response-continuation, and conversation options;
- function declarations, calls, rich/JSON results, and multi-turn call IDs;
- OpenAI reasoning effort/summary, service tier, background, truncation, and encrypted-reasoning
  options;
- the built-in image-generation tool when `OpenAIResponseOptions.imageOutputFormat` is set, with
  framework-owned `PNG`, `JPEG`, and `WEBP` formats mapped to their correct media types;
- finite and streaming text/refusal, reasoning, function-call, image, usage, finish, continuation,
  status, request-ID, and error observations.

Unsupported roles, content/media kinds, built-in tool declarations, and Responses-incompatible
`ChatOptions` (`stop`, `seed`, frequency penalty, and presence penalty) fail explicitly before a
network call. Unknown provider output variants fail as protocol errors rather than being silently
dropped.

### Conversation continuation contract

The provider interprets `ChatOptions.conversationId` using the OpenAI identifier namespace:

- values beginning with `conv_` are sent as the Responses API `conversation` identifier;
- every other non-blank value is sent as `previous_response_id` to continue from a response.

The Responses API does not allow `conversation` and `previous_response_id` together. Applications
should therefore retain the identifier kind returned by OpenAI rather than rewriting prefixes.

### Generated-image formats

Set `OpenAIResponseOptions.imageOutputFormat` to enable the Responses image-generation tool and
request `PNG`, `JPEG`, or `WEBP`. The provider uses `image/png`, `image/jpeg`, and `image/webp`
respectively for both finite and partial streaming image data. When a generated-image response has
no format field and no explicit format was requested, the provider uses `image/png`, the documented
Responses API default. Unknown requested formats are rejected locally because the public enum
contains only values supported by SDK 4.50.0.

## Test boundary

`OpenAITransport` is a framework-owned immutable boundary intended for deterministic offline tests
and custom transports. Injecting it exercises the production `OpenAIChatClient`, request mapper,
response mapper, cancellation, and bounded-stream paths without exposing SDK models to shared
modules.

`OpenAIResponsesJsonCodec` is the SDK-free wire bridge used by provider adapters that expose the
same Responses protocol. Azure OpenAI and Microsoft Foundry keep separate artifacts, credentials,
endpoints, options, errors, and SDK transports while reusing this protocol boundary and the tested
client lifecycle.

`JCF-PROVIDERS-001` is bound by `OpenAIConformanceTest` to those production paths.

Run offline verification with:

```bash
./gradlew :agent-framework-openai:test
```

The live test is explicit and is not executed by `check`:

```bash
OPENAI_API_KEY=... OPENAI_MODEL=... \
  ./gradlew :agent-framework-openai:liveTest
```

Invoking `liveTest` without either variable fails before test execution with a credential
configuration message.

## Current scope

This module implements chat through the OpenAI Responses API, function tools, the explicitly
configured image-generation tool, and text embeddings. Other OpenAI built-in tools and
Azure-specific identity/service-version configuration remain outside this artifact. A custom
API-key-compatible endpoint can be selected with `baseUrl`, but that does not imply full Azure
OpenAI credential or URL-mode parity.
