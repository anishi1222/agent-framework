# Agent Framework Microsoft Foundry provider

`agent-framework-foundry` adapts the framework-owned `ChatClient` and `Agent` contracts to Microsoft
Foundry project Responses. It is separate from Azure OpenAI because project routing, agent
references, OAuth scope, continuation, and server-owned agent configuration are distinct APIs.

The module pins the current GA official Java SDKs published on Maven Central:

- `com.azure:azure-ai-projects:2.2.0`
- `com.azure:azure-ai-agents:2.2.0`
- `com.azure:azure-identity:1.18.4`

The SDKs use the Foundry `v1` data plane and the official OpenAI Java Responses models internally.
Because this adapter reuses `agent-framework-openai`, dependency resolution selects the framework's
tested stable `com.openai:openai-java:4.50.0` over the Azure SDKs' transitive 4.14.0 request; offline
tests exercise both GA Azure builders against that resolved version. The
`checkOpenAIJavaConvergence` task resolves `runtimeClasspath` and fails unless
`com.openai:openai-java` is exactly the version catalog's tested `4.50.0`; it is also part of
`check`. No Azure or OpenAI service model appears in a public signature.

## Dependency and identity

```kotlin
dependencies {
    implementation(platform("com.microsoft.agents:agent-framework-bom:0.1.0-SNAPSHOT"))
    implementation("com.microsoft.agents:agent-framework-foundry")

    // Required at compile time only when passing TokenCredential directly.
    implementation("com.azure:azure-identity:1.18.4")
}
```

Foundry's supported authentication surface is Microsoft Entra ID:

```java
FoundryChatClientOptions options = FoundryChatClientOptions.builder()
        .projectEndpoint(System.getenv("FOUNDRY_PROJECT_ENDPOINT"))
        .model(System.getenv("FOUNDRY_MODEL"))
        .defaultAzureCredential()
        .build();
```

`tokenCredential(TokenCredential)` accepts a caller-owned credential and never closes, serializes,
or logs it. `defaultAzureCredential()` and `managedIdentityCredential(...)` cover the common
managed-identity/default-chain paths without exposing Azure Identity types in application source.
API-key authentication is not offered because the GA Foundry Agents builder supports
`TokenCredential`, not an API key. Request and response body logging is disabled.

The project endpoint must be an HTTPS URI ending in `/api/projects/<project>`. Exactly one of
`model` or `agentName` is required. The GA Java SDK identifies an `AgentReference` by name and
optional version, so this adapter uses those published fields rather than inventing an opaque
`agentId` contract.

## Direct model and existing-agent surfaces

Direct model Responses:

```java
FoundryChatClient client = FoundryChatClient.builder().options(options).build();
```

Existing versioned agent reference:

```java
FoundryChatClientOptions agentOptions = FoundryChatClientOptions.builder()
        .projectEndpoint(System.getenv("FOUNDRY_PROJECT_ENDPOINT"))
        .agentName(System.getenv("FOUNDRY_AGENT_NAME"))
        .agentVersion(System.getenv("FOUNDRY_AGENT_VERSION"))
        .defaultAzureCredential()
        .build();

FoundryAgent agent = FoundryAgent.builder()
        .options(agentOptions)
        .tools(List.of(localFunctionTool))
        .build();
```

For existing agents, the provider sends `agent_reference` and removes request-owned model,
instructions, temperature, top-p, tool declarations, tool choice, and parallel-tool flags. The
server-side agent definition owns those values. It still forwards input, maximum output tokens,
end-user identifier, storage preference, metadata, and continuation. Local `FunctionTool` instances
remain available to `ChatAgent`: returned call IDs and arguments are dispatched locally, including
parallel calls, and correlated function results are sent on the next Responses turn.

## Continuation, streaming, and errors

`FoundryContinuationMode.CONVERSATION` is the default. It maps
`ChatOptions.conversationId` (or `defaultConversationId`) to the Foundry/OpenAI `conversation`
field and preserves it through every local tool-loop turn.
`FoundryContinuationMode.PREVIOUS_RESPONSE` maps the same framework field to
`previous_response_id`. This provider-owned switch prevents Foundry continuation semantics from
leaking into core.

Finite and streaming paths map text/refusal, reasoning, function calls/results, usage, finish
reason, response/message/conversation IDs, and safe request metadata. Streams are cold,
single-subscriber, cancellation-propagating, and bounded. Unsupported content and service output
fail rather than producing fake success; agent-owned override fields are deterministically removed.
Closing a production client closes the Foundry-owned `OpenAIClientAsync` and any active raw/parsed
streams; caller-injected transports remain caller-owned unless ownership is explicitly transferred.

`FoundryProviderException` retains only safe status, request/correlation identifiers, and service
codes. It never retains response bodies or credentials.

`JCF-PROVIDERS-002` is bound by `FoundryConformanceTest` to the production client, request mapping,
continuation mapping, local tool loop, and framework API-isolation paths.

## Tests and deferred surfaces

```bash
./gradlew :agent-framework-foundry:test

FOUNDRY_PROJECT_ENDPOINT=... \
FOUNDRY_MODEL=... \
./gradlew :agent-framework-foundry:liveTest
```

`liveTest` is excluded from `check` and fails before execution when required variables are absent.
Use `FOUNDRY_AGENT_NAME` (and optionally `FOUNDRY_AGENT_VERSION`) instead of `FOUNDRY_MODEL` to
exercise an existing agent.

Deferred features are explicit: persistent thread/run semantics from
`com.azure:azure-ai-agents-persistent:1.0.0-beta.2`, hosted-agent `agent_session_id` lifecycle,
embeddings, evaluations, memory stores, file/vector-store management, and agent CRUD are not
represented by this initial chat adapter. No placeholder response is returned for those
capabilities.
