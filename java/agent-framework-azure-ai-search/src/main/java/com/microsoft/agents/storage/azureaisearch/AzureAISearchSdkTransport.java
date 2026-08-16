// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.azure.core.http.HttpClient;
import com.azure.search.documents.indexes.models.KnowledgeBase;
import com.azure.search.documents.indexes.models.KnowledgeSource;
import com.azure.search.documents.indexes.models.KnowledgeSourceReference;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.SearchIndexFieldReference;
import com.azure.search.documents.indexes.models.SearchIndexKnowledgeSource;
import com.azure.search.documents.indexes.models.SearchIndexKnowledgeSourceParameters;
import com.azure.search.documents.indexes.models.SemanticConfiguration;
import com.azure.search.documents.indexes.models.SemanticSearch;
import com.azure.search.documents.indexes.models.VectorSearch;
import com.azure.search.documents.indexes.models.VectorSearchProfile;
import com.azure.search.documents.indexes.models.VectorSearchVectorizer;
import com.azure.search.documents.knowledgebases.models.KnowledgeBaseMessage;
import com.azure.search.documents.knowledgebases.models.KnowledgeBaseMessageTextContent;
import com.azure.search.documents.knowledgebases.models.KnowledgeBaseReference;
import com.azure.search.documents.knowledgebases.models.KnowledgeBaseRetrievalOptions;
import com.azure.search.documents.knowledgebases.models.KnowledgeBaseRetrievalResult;
import com.azure.search.documents.knowledgebases.models.KnowledgeBaseSearchIndexReference;
import com.azure.search.documents.knowledgebases.models.KnowledgeRetrievalSemanticIntent;
import com.azure.search.documents.knowledgebases.models.KnowledgeSourceParams;
import com.azure.search.documents.knowledgebases.models.SearchIndexKnowledgeSourceParams;
import com.azure.search.documents.models.QueryCaptionResult;
import com.azure.search.documents.models.QueryCaptionType;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.VectorFilterMode;
import com.azure.search.documents.models.VectorQuery;
import com.azure.search.documents.models.VectorizableTextQuery;
import com.azure.search.documents.models.VectorizedQuery;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class AzureAISearchSdkTransport implements AzureAISearchTransport {
    private final AzureAISearchOptions options;

    private final AzureAISearchClientFactory.Clients clients;

    private volatile Initialization initialized;

    private AzureAISearchSdkTransport(AzureAISearchOptions options, AzureAISearchClientFactory.Clients clients) {
        this.options = Objects.requireNonNull(options, "options");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    static AzureAISearchSdkTransport create(AzureAISearchOptions options) {
        return new AzureAISearchSdkTransport(options, AzureAISearchClientFactory.create(options));
    }

    static AzureAISearchSdkTransport create(AzureAISearchOptions options, HttpClient httpClient) {
        return new AzureAISearchSdkTransport(options, AzureAISearchClientFactory.create(options, httpClient));
    }

    @Override
    public CompletionStage<List<AzureAISearchResult>> searchAsync(
            AzureAISearchRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        return initialize(cancellation)
                .thenCompose(initialization -> options.mode() == AzureAISearchQueryMode.AGENTIC
                        ? agenticSearch(request, initialization, cancellation)
                        : indexSearch(request, initialization, cancellation));
    }

    private CompletionStage<Initialization> initialize(RunCancellation cancellation) {
        Initialization existing = initialized;
        if (existing != null) {
            return CompletableFuture.completedStage(existing);
        }
        CompletionStage<Initialization> loading = options.mode() == AzureAISearchQueryMode.AGENTIC
                ? initializeKnowledgeBase(cancellation)
                : AzureAISearchSdkSupport.stage(
                                clients.indexClient().getIndex(options.indexName()), options, cancellation, "get-index")
                        .thenApply(index -> validateIndex(index, false));
        return loading.thenApply(value -> {
            synchronized (this) {
                if (initialized == null) {
                    initialized = value;
                }
                return initialized;
            }
        });
    }

    private CompletionStage<Initialization> initializeKnowledgeBase(RunCancellation cancellation) {
        return AzureAISearchSdkSupport.stage(
                        clients.indexClient().getKnowledgeBase(options.knowledgeBaseName()),
                        options,
                        cancellation,
                        "get-knowledge-base")
                .thenCompose(knowledgeBase -> validateKnowledgeBase(knowledgeBase, cancellation));
    }

    private CompletionStage<Initialization> validateKnowledgeBase(
            KnowledgeBase knowledgeBase, RunCancellation cancellation) {
        if (knowledgeBase == null
                || knowledgeBase.getKnowledgeSources() == null
                || knowledgeBase.getKnowledgeSources().isEmpty()) {
            return CompletableFuture.failedStage(
                    new AzureAISearchValidationException("The configured knowledge base has no knowledge sources."));
        }
        ArrayList<String> sourceNames = new ArrayList<>();
        HashSet<String> uniqueNames = new HashSet<>();
        for (KnowledgeSourceReference reference : knowledgeBase.getKnowledgeSources()) {
            if (reference == null
                    || reference.getName() == null
                    || reference.getName().isBlank()) {
                return CompletableFuture.failedStage(new AzureAISearchValidationException(
                        "The configured knowledge base contains an unnamed knowledge source."));
            }
            if (!uniqueNames.add(reference.getName())) {
                return CompletableFuture.failedStage(new AzureAISearchValidationException(
                        "The configured knowledge base contains duplicate knowledge sources."));
            }
            sourceNames.add(reference.getName());
        }
        return validateKnowledgeSources(sourceNames, 0, cancellation)
                .thenApply(ignored -> new Initialization(null, null, List.copyOf(sourceNames)));
    }

    private CompletionStage<Void> validateKnowledgeSources(
            List<String> sourceNames, int index, RunCancellation cancellation) {
        if (index == sourceNames.size()) {
            return CompletableFuture.completedStage(null);
        }
        String sourceName = sourceNames.get(index);
        return AzureAISearchSdkSupport.stage(
                        clients.indexClient().getKnowledgeSource(sourceName),
                        options,
                        cancellation,
                        "get-knowledge-source")
                .thenCompose(source -> validateKnowledgeSource(source, sourceNames, index, cancellation));
    }

    private CompletionStage<Void> validateKnowledgeSource(
            KnowledgeSource source, List<String> sourceNames, int index, RunCancellation cancellation) {
        if (!(source instanceof SearchIndexKnowledgeSource searchSource)) {
            return CompletableFuture.failedStage(new AzureAISearchValidationException(
                    "Agentic retrieval requires every knowledge source to be a search-index source."));
        }
        SearchIndexKnowledgeSourceParameters parameters = searchSource.getSearchIndexParameters();
        if (parameters == null
                || parameters.getSearchIndexName() == null
                || parameters.getSearchIndexName().isBlank()) {
            return CompletableFuture.failedStage(
                    new AzureAISearchValidationException("A search-index knowledge source has no index name."));
        }
        return AzureAISearchSdkSupport.stage(
                        clients.indexClient().getIndex(parameters.getSearchIndexName()),
                        options,
                        cancellation,
                        "get-index")
                .thenCompose(searchIndex -> {
                    validateIndex(searchIndex, true);
                    validateSourceDataFields(parameters);
                    return validateKnowledgeSources(sourceNames, index + 1, cancellation);
                });
    }

    private Initialization validateIndex(SearchIndex index, boolean agentic) {
        if (index == null || index.getFields() == null || index.getFields().isEmpty()) {
            throw new AzureAISearchValidationException("The configured Azure AI Search index has no fields.");
        }
        AzureAISearchFieldMapping mapping = options.fieldMapping();
        SearchField key = requireField(index, mapping.keyField());
        requireString(key, mapping.keyField());
        if (!Boolean.TRUE.equals(key.isKey()) || !retrievable(key)) {
            throw new AzureAISearchValidationException("The configured key field must be the retrievable index key.");
        }

        SearchField content = requireField(index, mapping.contentField());
        requireString(content, mapping.contentField());
        if (!retrievable(content)) {
            throw new AzureAISearchValidationException("The configured content field must be retrievable.");
        }
        if (!agentic && options.mode().usesText() && !Boolean.TRUE.equals(content.isSearchable())) {
            throw new AzureAISearchValidationException(
                    "The configured content field must be searchable for the selected query mode.");
        }

        SearchField tenant = requireField(index, mapping.tenantIdField());
        requireString(tenant, mapping.tenantIdField());
        if (!Boolean.TRUE.equals(tenant.isFilterable())) {
            throw new AzureAISearchValidationException("The configured tenant field must be filterable.");
        }
        SearchField scope = requireField(index, mapping.scopeIdField());
        requireString(scope, mapping.scopeIdField());
        if (!Boolean.TRUE.equals(scope.isFilterable())) {
            throw new AzureAISearchValidationException("The configured scope field must be filterable.");
        }
        validateOptionalRetrievableString(index, mapping.titleField());
        validateOptionalRetrievableString(index, mapping.sourceUrlField());

        if (agentic) {
            return new Initialization(null, null, List.of());
        }
        if (!options.mode().usesVector() && !agentic) {
            validateSemanticConfiguration(index, options.semanticConfigurationName());
            return new Initialization(null, null, List.of());
        }
        if (!agentic) {
            validateSemanticConfiguration(index, options.semanticConfigurationName());
        }
        SearchField vector = resolveVectorField(index);
        Integer dimensions = vector.getVectorSearchDimensions();
        if (dimensions == null || dimensions <= 0) {
            throw new AzureAISearchValidationException("The configured vector field has no positive vector dimension.");
        }
        if (options.embeddingProvider() == null) {
            validateServerVectorizer(index, vector);
        }
        return new Initialization(vector.getName(), dimensions, List.of());
    }

    private void validateSourceDataFields(SearchIndexKnowledgeSourceParameters parameters) {
        List<SearchIndexFieldReference> configured = parameters.getSourceDataFields();
        if (configured == null || configured.isEmpty()) {
            throw new AzureAISearchValidationException(
                    "Agentic search-index knowledge sources must configure sourceDataFields.");
        }
        Set<String> names = configured.stream()
                .filter(Objects::nonNull)
                .map(SearchIndexFieldReference::getName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        AzureAISearchFieldMapping mapping = options.fieldMapping();
        ArrayList<String> required = new ArrayList<>();
        required.add(mapping.keyField());
        required.add(mapping.contentField());
        if (mapping.titleField() != null) {
            required.add(mapping.titleField());
        }
        if (mapping.sourceUrlField() != null) {
            required.add(mapping.sourceUrlField());
        }
        if (!names.containsAll(required)) {
            throw new AzureAISearchValidationException(
                    "Agentic search-index knowledge sources must expose every mapped result field "
                            + "in sourceDataFields.");
        }
    }

    private void validateSemanticConfiguration(SearchIndex index, String configurationName) {
        if (!options.mode().usesSemantic() || configurationName == null) {
            return;
        }
        SemanticSearch semantic = index.getSemanticSearch();
        List<SemanticConfiguration> configurations = semantic == null ? null : semantic.getConfigurations();
        if (configurations == null
                || configurations.stream()
                        .filter(Objects::nonNull)
                        .map(SemanticConfiguration::getName)
                        .noneMatch(configurationName::equals)) {
            throw new AzureAISearchValidationException(
                    "The configured semantic configuration does not exist on the index.");
        }
    }

    private SearchField resolveVectorField(SearchIndex index) {
        String configured = options.fieldMapping().vectorField();
        if (configured != null) {
            SearchField field = requireField(index, configured);
            requireVector(field);
            return field;
        }
        List<SearchField> candidates = index.getFields().stream()
                .filter(Objects::nonNull)
                .filter(field -> field.getVectorSearchDimensions() != null)
                .filter(field -> field.getVectorSearchProfileName() != null
                        && !field.getVectorSearchProfileName().isBlank())
                .toList();
        if (candidates.size() != 1) {
            throw new AzureAISearchValidationException(
                    "Vector query modes require an explicit vector field or exactly one discoverable vector field.");
        }
        requireVector(candidates.getFirst());
        return candidates.getFirst();
    }

    private void validateServerVectorizer(SearchIndex index, SearchField vectorField) {
        VectorSearch vectorSearch = index.getVectorSearch();
        if (vectorSearch == null || vectorSearch.getProfiles() == null) {
            throw new AzureAISearchValidationException("Server-side vectorization requires a vector-search profile.");
        }
        VectorSearchProfile profile = vectorSearch.getProfiles().stream()
                .filter(Objects::nonNull)
                .filter(candidate -> vectorField.getVectorSearchProfileName().equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new AzureAISearchValidationException(
                        "The configured vector field references an unknown vector-search profile."));
        if (profile.getVectorizerName() == null
                || profile.getVectorizerName().isBlank()
                || vectorSearch.getVectorizers() == null
                || vectorSearch.getVectorizers().stream()
                        .filter(Objects::nonNull)
                        .map(VectorSearchVectorizer::getVectorizerName)
                        .noneMatch(profile.getVectorizerName()::equals)) {
            throw new AzureAISearchValidationException(
                    "A configured server-side vectorizer is required when no embedding provider is supplied.");
        }
    }

    private CompletionStage<List<AzureAISearchResult>> indexSearch(
            AzureAISearchRequest request, Initialization initialization, RunCancellation cancellation) {
        if (request.embedding() != null && request.embedding().dimensions() != initialization.vectorDimensions()) {
            return CompletableFuture.failedStage(new AzureAISearchValidationException(
                    "The generated embedding dimension does not match the configured vector field."));
        }
        SearchOptions searchOptions = searchOptions(request, initialization);
        return AzureAISearchSdkSupport.stage(
                        clients.searchClient()
                                .search(searchOptions)
                                .take(options.topK())
                                .collectList(),
                        options,
                        cancellation,
                        "search")
                .thenApply(this::mapSearchResults);
    }

    private SearchOptions searchOptions(AzureAISearchRequest request, Initialization initialization) {
        SearchOptions search = new SearchOptions()
                .setFilter(AzureAISearchFilter.forScope(options, request.scope()))
                .setSelect(selectedFields())
                .setTop(options.topK());
        if (options.mode().usesText()) {
            search.setSearchText(request.query())
                    .setSearchFields(options.fieldMapping().contentField());
        }
        if (options.mode().usesSemantic()) {
            search.setQueryType(QueryType.SEMANTIC)
                    .setSemanticConfigurationName(options.semanticConfigurationName())
                    .setCaptions(QueryCaptionType.EXTRACTIVE);
        }
        if (options.mode().usesVector()) {
            int candidates = options.mode() == AzureAISearchQueryMode.SEMANTIC_HYBRID
                    ? Math.max(options.topK(), 50)
                    : options.topK();
            VectorQuery vector = request.embedding() == null
                    ? new VectorizableTextQuery(request.query())
                            .setFields(initialization.vectorField())
                            .setKNearestNeighbors(candidates)
                    : new VectorizedQuery(request.embedding().values().stream()
                                    .map(Double::floatValue)
                                    .toList())
                            .setFields(initialization.vectorField())
                            .setKNearestNeighbors(candidates);
            search.setVectorQueries(vector).setVectorFilterMode(VectorFilterMode.PRE_FILTER);
        }
        return search;
    }

    private List<String> selectedFields() {
        return options.fieldMapping().desiredSelectFields();
    }

    private List<AzureAISearchResult> mapSearchResults(List<SearchResult> results) {
        try {
            ArrayList<AzureAISearchResult> mapped = new ArrayList<>();
            int rank = 1;
            for (SearchResult result : results) {
                if (result == null) {
                    throw new IllegalStateException("Search result is null.");
                }
                Map<String, Object> document = result.getAdditionalProperties();
                if (document == null) {
                    throw new IllegalStateException("Search result has no document.");
                }
                String key = requiredString(document, options.fieldMapping().keyField());
                String content = caption(result);
                if (content == null) {
                    content = requiredString(document, options.fieldMapping().contentField());
                }
                String title = optionalString(document, options.fieldMapping().titleField());
                String sourceUrl =
                        optionalString(document, options.fieldMapping().sourceUrlField());
                Double score = result.getRerankerScore() == null ? result.getScore() : result.getRerankerScore();
                mapped.add(new AzureAISearchResult(
                        key,
                        bounded(content),
                        citation(key, sourceUrl),
                        score,
                        rank++,
                        metadata("azure-ai-search", title)));
            }
            return List.copyOf(mapped);
        } catch (RuntimeException failure) {
            if (failure instanceof AzureAISearchException mapped) {
                throw mapped;
            }
            throw AzureAISearchSdkSupport.invalidResponse("search", failure);
        }
    }

    private String caption(SearchResult result) {
        if (!options.mode().usesSemantic() || result.getCaptions() == null) {
            return null;
        }
        return result.getCaptions().stream()
                .filter(Objects::nonNull)
                .map(QueryCaptionResult::getText)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    private CompletionStage<List<AzureAISearchResult>> agenticSearch(
            AzureAISearchRequest request, Initialization initialization, RunCancellation cancellation) {
        ArrayList<KnowledgeSourceParams> sourceParameters = new ArrayList<>();
        String filter = AzureAISearchFilter.forScope(options, request.scope());
        for (String sourceName : initialization.knowledgeSourceNames()) {
            sourceParameters.add(new SearchIndexKnowledgeSourceParams(sourceName)
                    .setFilterAddOn(filter)
                    .setIncludeReferences(true)
                    .setIncludeReferenceSourceData(true));
        }
        int runtimeSeconds = Math.max(
                1, Math.min(60, Math.toIntExact((options.operationTimeout().toMillis() + 999L) / 1000L)));
        KnowledgeBaseRetrievalOptions retrievalOptions = new KnowledgeBaseRetrievalOptions()
                .setIntents(new KnowledgeRetrievalSemanticIntent(request.query()))
                .setKnowledgeSourceParams(sourceParameters)
                .setMaxRuntimeInSeconds(runtimeSeconds)
                .setMaxOutputSizeInTokens(options.agenticMaxOutputSizeTokens())
                .setIncludeActivity(false);
        return AzureAISearchSdkSupport.stage(
                        clients.retrievalClient().retrieve(retrievalOptions), options, cancellation, "retrieve")
                .thenApply(this::mapAgenticResults);
    }

    private List<AzureAISearchResult> mapAgenticResults(KnowledgeBaseRetrievalResult retrieval) {
        try {
            if (retrieval == null) {
                throw new IllegalStateException("Agentic retrieval result is null.");
            }
            ArrayList<AzureAISearchResult> mapped = new ArrayList<>();
            if (retrieval.getReferences() != null) {
                for (KnowledgeBaseReference reference : retrieval.getReferences()) {
                    if (mapped.size() >= options.topK()) {
                        break;
                    }
                    if (!(reference instanceof KnowledgeBaseSearchIndexReference searchReference)) {
                        throw new IllegalStateException("Agentic retrieval returned a non-index reference.");
                    }
                    Map<String, Object> sourceData = searchReference.getSourceData();
                    if (sourceData == null || sourceData.isEmpty()) {
                        continue;
                    }
                    String content =
                            optionalString(sourceData, options.fieldMapping().contentField());
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    String key =
                            optionalString(sourceData, options.fieldMapping().keyField());
                    if (key == null || key.isBlank()) {
                        key = searchReference.getDocKey();
                    }
                    if (key == null || key.isBlank()) {
                        throw new IllegalStateException("Agentic retrieval reference has no document key.");
                    }
                    String title =
                            optionalString(sourceData, options.fieldMapping().titleField());
                    String sourceUrl =
                            optionalString(sourceData, options.fieldMapping().sourceUrlField());
                    mapped.add(new AzureAISearchResult(
                            key,
                            bounded(content),
                            citation(key, sourceUrl),
                            searchReference.getRerankerScore() == null
                                    ? null
                                    : searchReference.getRerankerScore().doubleValue(),
                            mapped.size() + 1,
                            metadata("azure-ai-search-agentic", title)));
                }
            }
            if (mapped.isEmpty()) {
                String response = responseText(retrieval.getResponse());
                if (response != null && !response.isBlank()) {
                    mapped.add(new AzureAISearchResult(
                            "agentic-response",
                            bounded(response),
                            "azure-search://agentic/response",
                            null,
                            1,
                            metadata("azure-ai-search-agentic", null)));
                }
            }
            return List.copyOf(mapped);
        } catch (RuntimeException failure) {
            if (failure instanceof AzureAISearchException mapped) {
                throw mapped;
            }
            throw AzureAISearchSdkSupport.invalidResponse("retrieve", failure);
        }
    }

    private static String responseText(List<KnowledgeBaseMessage> messages) {
        if (messages == null) {
            return null;
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .map(KnowledgeBaseMessage::getContent)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(KnowledgeBaseMessageTextContent.class::isInstance)
                .map(KnowledgeBaseMessageTextContent.class::cast)
                .map(KnowledgeBaseMessageTextContent::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String bounded(String value) {
        return value.length() <= options.maxSnippetCharacters()
                ? value
                : value.substring(0, options.maxSnippetCharacters());
    }

    private static String requiredString(Map<String, Object> document, String field) {
        String value = optionalString(document, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Search result is missing a required mapped field.");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> document, String field) {
        if (field == null || !document.containsKey(field)) {
            return null;
        }
        Object value = document.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException("Search result mapped field is not a string.");
        }
        return text;
    }

    private static SearchField requireField(SearchIndex index, String name) {
        return index.getFields().stream()
                .filter(Objects::nonNull)
                .filter(field -> name.equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new AzureAISearchValidationException(
                        "A configured field does not exist on the Azure AI Search index."));
    }

    private static void requireString(SearchField field, String name) {
        if (!SearchFieldDataType.STRING.equals(field.getType())) {
            throw new AzureAISearchValidationException("Configured field '" + name + "' must use Edm.String.");
        }
    }

    private static void requireVector(SearchField field) {
        if (field.getVectorSearchDimensions() == null
                || field.getVectorSearchProfileName() == null
                || field.getVectorSearchProfileName().isBlank()) {
            throw new AzureAISearchValidationException("The configured vector field is not a vector-search field.");
        }
    }

    private static boolean retrievable(SearchField field) {
        return !Boolean.FALSE.equals(field.isRetrievable());
    }

    private static Map<String, StateValue> metadata(String source, String title) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("source", StateValue.string(source));
        if (title != null && !title.isBlank()) {
            metadata.put("title", StateValue.string(title));
        }
        return Map.copyOf(metadata);
    }

    private static String citation(String key, String sourceUrl) {
        if (sourceUrl != null && sourceUrl.length() <= 512) {
            try {
                URI uri = URI.create(sourceUrl);
                if (uri.isAbsolute()
                        && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                    return sourceUrl;
                }
            } catch (IllegalArgumentException ignored) {
                // Use a stable opaque citation for malformed source URLs.
            }
        }
        return "azure-search://document/"
                + URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void validateOptionalRetrievableString(SearchIndex index, String fieldName) {
        if (fieldName == null) {
            return;
        }
        SearchField field = requireField(index, fieldName);
        requireString(field, fieldName);
        if (!retrievable(field)) {
            throw new AzureAISearchValidationException("Optional mapped result fields must be retrievable.");
        }
    }

    private record Initialization(String vectorField, Integer vectorDimensions, List<String> knowledgeSourceNames) {
        private Initialization {
            knowledgeSourceNames = List.copyOf(knowledgeSourceNames);
        }
    }
}
