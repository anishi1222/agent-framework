# Agent Framework Azure OpenAI provider

`agent-framework-azure-openai` adapts the framework-owned `ChatClient` contract to Azure OpenAI's
Responses API. Azure OpenAI remains a separate artifact from both the public OpenAI provider and
Microsoft Foundry.

The adapter pins the latest published official Azure SDK surface available for this API:
`com.azure:azure-ai-openai:1.0.0-beta.16`. Maven Central and the Microsoft Learn package reference
publish no GA `azure-ai-openai` release. The beta dependency is isolated behind
`AzureOpenAITransport`; no Azure service model appears in a public signature.

## Dependency and authentication

```kotlin
dependencies {
    implementation(platform("com.microsoft.agents:agent-framework-bom:0.1.0-SNAPSHOT"))
    implementation("com.microsoft.agents:agent-framework-azure-openai")

    // Required at compile time only when passing TokenCredential directly.
    implementation("com.azure:azure-identity:1.18.4")
}
```

Use one authentication mode:

```java
AzureOpenAIChatClientOptions keyOptions = AzureOpenAIChatClientOptions.builder()
        .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))
        .deployment(System.getenv("AZURE_OPENAI_DEPLOYMENT"))
        .apiKey(System.getenv("AZURE_OPENAI_API_KEY"))
        .build();

AzureOpenAIChatClientOptions identityOptions = AzureOpenAIChatClientOptions.builder()
        .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))
        .deployment(System.getenv("AZURE_OPENAI_DEPLOYMENT"))
        .defaultAzureCredential()
        .build();
```

`tokenCredential(TokenCredential)` accepts a caller-owned credential and never closes, serializes,
or logs it. `defaultAzureCredential()` and `managedIdentityCredential(...)` create identity
credentials for applications that do not need the Azure type in their source. API keys and
authorization headers are redacted; request/response bodies are never enabled for SDK logging.

The default API version is `2025-03-01-preview`, the latest version supported by the pinned SDK.
Supported older SDK versions can be selected explicitly. Endpoint validation requires HTTPS, a
host, and no user info, query, or fragment. Exactly one credential mode is required.

## Mapping and runtime behavior

The initial adapter maps:

- system, developer, user, assistant, and tool history;
- text, image URI/data, and file URI/data inputs supported by Responses;
- function declarations, parallel calls, call IDs, JSON arguments, and function results;
- model/deployment, instructions, temperature, top-p, output tokens, tool choice, parallel calls,
  user, storage, metadata, and previous-response continuation;
- finite and streaming text/refusal, reasoning, function-call, image, usage, response/message IDs,
  finish reason, and safe request metadata.

Unsupported role/content combinations and unsupported provider output variants fail explicitly.
`ChatOptions.conversationId` follows the OpenAI provider's prefix contract: values beginning with
`conv_` mean Responses conversations, while every other non-blank value means
`previous_response_id`. The pinned Azure SDK and `2025-03-01-preview` request model expose
`previous_response_id` but not `conversation`, so `conv_` values fail with
`AzureOpenAIProviderException.Kind.UNSUPPORTED_OPTION` before transport instead of being silently
dropped.

Streams reuse the OpenAI protocol lifecycle: one cold subscription, bounded retention, positive
demand, cancellation propagation, and exactly one terminal signal. Closing the client cancels
active work and closes only an owned transport. The Azure SDK's `ResponsesAsyncClient` is not
`AutoCloseable` and may share its HTTP pipeline, so closing the SDK transport is intentionally a
flag-only operation that rejects new calls.

`AzureOpenAIProviderException` retains only safe status, request/correlation identifiers, and
service codes. It never retains a response body or credential.

## Tests and current scope

`AzureOpenAITransport` is a framework-owned deterministic test boundary. Offline tests also inject
an Azure SDK `HttpClient` internally to bind key/token authentication and real SDK serialization to
the production mapping path. `AzureOpenAIConformanceTest` binds the shared OpenAI-family
`JCF-PROVIDERS-001` behavior to the Azure production client and mapping path.

```bash
./gradlew :agent-framework-azure-openai:test

AZURE_OPENAI_ENDPOINT=... \
AZURE_OPENAI_DEPLOYMENT=... \
AZURE_OPENAI_API_KEY=... \
./gradlew :agent-framework-azure-openai:liveTest
```

`liveTest` is excluded from `check` and fails before execution when required variables are absent.
Embeddings, audio, file-management helpers, structured-output helpers, and Azure-hosted built-in
tool factories are deferred. The module implements Responses chat and local function-tool
roundtrips only.
