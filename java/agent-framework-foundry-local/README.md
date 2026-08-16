# Agent Framework Foundry Local provider

The current Foundry Local documentation lists native SDKs for C#, JavaScript, Python, and Rust—not
Java—and its process-neutral REST service exposes OpenAI-compatible **Chat Completions**, not the
Responses API. `agent-framework-foundry-local` therefore honestly integrates the documented REST
surface and does not bundle, download, load, unload, or start native binaries.

```java
try (FoundryLocalChatClient client = FoundryLocalChatClient.builder()
        .options(FoundryLocalChatClientOptions.builder()
                .endpoint(discoveredServiceEndpoint)
                .model(loadedModelId)
                .build())
        .build()) {
    FoundryLocalStatus status = client.statusAsync().toCompletableFuture().join();
    List<String> cached = client.cachedModelsAsync().toCompletableFuture().join();
    List<FoundryLocalModel> catalog = client.catalogAsync().toCompletableFuture().join();
}
```

Chat delegates internally to the strict OpenAI-style Chat Completions mapping used by the Mistral
module. Supported chat content is text plus function calls/results; tool and structured-output
support remains model-dependent. Status uses `/openai/status`, cached models use `/openai/models`,
and catalog discovery uses `/foundry/list`.

The service endpoint must come from the application/Foundry Local manager because its port is
dynamic. Loopback HTTP is allowed; remote access requires HTTPS, an explicit host allowlist, and a
bearer token. Redirects are never followed. Native model/process lifecycle is intentionally outside
this module.
