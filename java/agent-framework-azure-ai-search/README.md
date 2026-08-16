# Microsoft Agent Framework Azure AI Search integration

`com.microsoft.agents:agent-framework-azure-ai-search` provides a read-only
`com.microsoft.agents.storage.azureaisearch.AzureAISearchContextProvider` for Java 25. It uses the
stable official `com.azure:azure-search-documents:12.0.1` SDK while keeping Azure SDK and Reactor
types out of public signatures.

## Supported retrieval

| Mode | Azure AI Search request |
|---|---|
| `FULL_TEXT` | Lexical search over the configured content field. |
| `VECTOR` | Client-generated vector or index-configured query-time vectorizer. |
| `HYBRID` | Lexical and vector candidates in one request. |
| `SEMANTIC` | Semantic ranking with extractive captions. |
| `SEMANTIC_HYBRID` | Semantic ranking over lexical and vector candidates; vector `k` is at least 50. |
| `AGENTIC` | Retrieval through an existing knowledge base whose sources are all search-index knowledge sources. |

The module does not create services, indexes, vectorizers, knowledge sources, or knowledge bases. It
also exposes no document mutation API and does not claim `MemoryStore` CAS semantics.

## Authentication

Microsoft Entra ID and Azure RBAC are recommended. A production identity needs the narrowest
applicable data-plane role, normally **Search Index Data Reader**, plus any permissions required to
read an existing knowledge base and its metadata.

```java
AzureAISearchAuthentication authentication = AzureAISearchAuthentication.rbac(
        AzureAuthenticationProviders.productionDefaultCredential());
```

A query key can be used when RBAC is unavailable:

```java
AzureAISearchAuthentication authentication = AzureAISearchAuthentication.apiKey(
        AzureAISearchApiKey.of(System.getenv("AZURE_AI_SEARCH_API_KEY")));
```

Credentials, resource names, filters, document identifiers, retrieved text, and citations are
redacted from framework value `toString()` output.

## Index retrieval

Every request requires a trusted `MemoryScope`. The provider always adds equality filters for both
the configured tenant and scope fields. A trusted static filter can only narrow that mandatory
boundary.

```java
AzureAISearchOptions options = AzureAISearchOptions.forIndex(
                AzureAISearchEndpoint.of(System.getenv("AZURE_AI_SEARCH_ENDPOINT")),
                System.getenv("AZURE_AI_SEARCH_INDEX"),
                authentication)
        .fieldMapping(AzureAISearchFieldMapping.builder()
                .keyField("id")
                .contentField("content")
                .titleField("title")
                .sourceUrlField("sourceUrl")
                .tenantIdField("tenantId")
                .scopeIdField("scopeId")
                .build())
        .mode(AzureAISearchQueryMode.FULL_TEXT)
        .build();

MemoryScope scope = new MemoryScope("tenant-42", "user-17");
AzureAISearchContextProvider search = new AzureAISearchContextProvider(options, scope);
```

For per-session isolation, use an application-owned resolver. Do not derive scope values from prompt
text, model output, or untrusted metadata.

```java
AzureAISearchContextProvider search = AzureAISearchContextProvider.builder(
                options,
                request -> new MemoryScope(
                        trustedTenantFor(request.session()),
                        trustedPrincipalFor(request.session())))
        .id("product-search")
        .build();
```

At initialization, the provider verifies that:

- key and content fields exist, use `Edm.String`, and are retrievable;
- the key field is the index key;
- tenant and scope fields use `Edm.String` and are filterable;
- configured title and source URL fields exist and are retrievable;
- semantic configurations exist for semantic modes; and
- vector fields, dimensions, profiles, and query-time vectorizers match the selected vector mode.

Mapped field identifiers are ASCII-only and distinct. In vector modes, omit `vectorField` only when
the index contains exactly one discoverable vector field.

## Client-side embeddings

Pass a framework `EmbeddingProvider` to use client-side embeddings. The generated dimension must
match the index field.

```java
AzureAISearchOptions options = AzureAISearchOptions.forIndex(endpoint, indexName, authentication)
        .mode(AzureAISearchQueryMode.SEMANTIC_HYBRID)
        .semanticConfigurationName("semantic-config")
        .fieldMapping(AzureAISearchFieldMapping.builder()
                .vectorField("contentVector")
                .build())
        .embeddingProvider(embeddingProvider)
        .build();
```

Without an `EmbeddingProvider`, vector modes use `VectorizableTextQuery`; the selected vector-search
profile must reference a configured server-side vectorizer. Vector filters use Azure AI Search
`preFilter` so tenant isolation is applied before candidate selection.

## Existing knowledge-base retrieval

Agentic mode targets an already provisioned knowledge base:

```java
AzureAISearchOptions options = AzureAISearchOptions.forKnowledgeBase(
                endpoint,
                "existing-knowledge-base",
                authentication)
        .topK(5)
        .agenticMaxOutputSizeTokens(4096)
        .build();
```

Initialization fails unless every referenced knowledge source is a search-index knowledge source.
Each source index must satisfy the mapped field contract, and every mapped result field must be in
that source's `sourceDataFields`. The provider adds the mandatory tenant/scope filter through
`filterAddOn` for every source. Returned reference `sourceData` is preferred over generated response
text.

## Context safety and failures

Retrieved data is escaped, bounded, deduplicated by document ID, and injected only as a `USER`
message containing `<search-reference>` blocks. The message is labeled
`memoryTrust=untrusted-reference` and carries citation/rank provenance. Retrieved text never becomes
system instructions.

`FAIL_RUN` is the default. `CONTINUE_WITHOUT_CONTEXT` must be configured explicitly and applies only
to transport errors, deadlines, HTTP 408/429, and 5xx responses. Cancellation, authentication,
authorization, not-found resources, schema mismatch, and malformed successful responses always
propagate.

## Tests

All offline SDK pipeline, schema, isolation, cancellation, redaction, and context tests:

```bash
./gradlew :agent-framework-azure-ai-search:test
```

The optional live test is read-only and disabled by default:

```bash
AZURE_AI_SEARCH_LIVE_TEST=true \
AZURE_AI_SEARCH_ENDPOINT=https://<service>.search.windows.net \
AZURE_AI_SEARCH_INDEX=<index> \
AZURE_AI_SEARCH_TENANT_ID=<tenant-value> \
AZURE_AI_SEARCH_SCOPE_ID=<scope-value> \
AZURE_AI_SEARCH_QUERY=<query> \
./gradlew :agent-framework-azure-ai-search:test --tests '*AzureAISearchLiveTest'
```

Set `AZURE_AI_SEARCH_API_KEY` for key authentication; otherwise the test uses
`AzureAuthenticationProviders.defaultCredential()`. Optional field variables are documented in the
test source. A skipped live test is not evidence that the cloud integration succeeded.
