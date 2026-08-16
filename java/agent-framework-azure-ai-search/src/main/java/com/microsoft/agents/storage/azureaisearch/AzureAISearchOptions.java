// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.agents.memory.EmbeddingProvider;
import com.microsoft.agents.agents.memory.MemoryQuery;
import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.Objects;

/** Configures one bounded Azure AI Search retrieval provider. */
public final class AzureAISearchOptions {
    private final AzureAISearchEndpoint endpoint;

    private final AzureAISearchAuthentication authentication;

    private final AzureAISearchAudience audience;

    private final AzureAISearchQueryMode mode;

    private final String indexName;

    private final String knowledgeBaseName;

    private final AzureAISearchFieldMapping fieldMapping;

    private final String semanticConfigurationName;

    private final EmbeddingProvider embeddingProvider;

    private final String staticFilter;

    private final int topK;

    private final int contextCharacterBudget;

    private final int maxQueryCharacters;

    private final int maxSnippetCharacters;

    private final int agenticMessageHistoryCount;

    private final int agenticMaxOutputSizeTokens;

    private final Duration operationTimeout;

    private final AzureAISearchFailurePolicy failurePolicy;

    private AzureAISearchOptions(Builder builder) {
        endpoint = Objects.requireNonNull(builder.endpoint, "endpoint");
        authentication = Objects.requireNonNull(builder.authentication, "authentication");
        audience = Objects.requireNonNull(builder.audience, "audience");
        mode = Objects.requireNonNull(builder.mode, "mode");
        indexName = optionalName(builder.indexName, "indexName");
        knowledgeBaseName = optionalName(builder.knowledgeBaseName, "knowledgeBaseName");
        fieldMapping = Objects.requireNonNull(builder.fieldMapping, "fieldMapping");
        semanticConfigurationName = optionalName(builder.semanticConfigurationName, "semanticConfigurationName");
        embeddingProvider = builder.embeddingProvider;
        staticFilter = staticFilter(builder.staticFilter);
        topK = range(builder.topK, 1, MemoryQuery.MAX_TOP_K, "topK");
        contextCharacterBudget = range(builder.contextCharacterBudget, 256, 100_000, "contextCharacterBudget");
        maxQueryCharacters = range(builder.maxQueryCharacters, 1, 100_000, "maxQueryCharacters");
        maxSnippetCharacters = range(builder.maxSnippetCharacters, 1, contextCharacterBudget, "maxSnippetCharacters");
        agenticMessageHistoryCount = range(builder.agenticMessageHistoryCount, 1, 100, "agenticMessageHistoryCount");
        agenticMaxOutputSizeTokens =
                range(builder.agenticMaxOutputSizeTokens, 128, 32_000, "agenticMaxOutputSizeTokens");
        operationTimeout = positive(builder.operationTimeout, "operationTimeout");
        if (operationTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new ValidationException("operationTimeout must not exceed 10 minutes.");
        }
        failurePolicy = Objects.requireNonNull(builder.failurePolicy, "failurePolicy");
        validateMode();
    }

    /**
     * Creates an index-backed retrieval builder.
     *
     * @param endpoint Azure AI Search service endpoint
     * @param indexName search index name
     * @param authentication authentication configuration
     * @return new builder
     */
    public static Builder forIndex(
            AzureAISearchEndpoint endpoint, String indexName, AzureAISearchAuthentication authentication) {
        return new Builder(endpoint, authentication, indexName, null, AzureAISearchQueryMode.FULL_TEXT);
    }

    /**
     * Creates an existing-knowledge-base retrieval builder.
     *
     * @param endpoint Azure AI Search service endpoint
     * @param knowledgeBaseName existing knowledge-base name
     * @param authentication authentication configuration
     * @return new builder
     */
    public static Builder forKnowledgeBase(
            AzureAISearchEndpoint endpoint, String knowledgeBaseName, AzureAISearchAuthentication authentication) {
        return new Builder(endpoint, authentication, null, knowledgeBaseName, AzureAISearchQueryMode.AGENTIC);
    }

    /** Returns the service endpoint. */
    public AzureAISearchEndpoint endpoint() {
        return endpoint;
    }

    /** Returns the redacting authentication configuration. */
    public AzureAISearchAuthentication authentication() {
        return authentication;
    }

    /** Returns the Azure cloud audience. */
    public AzureAISearchAudience audience() {
        return audience;
    }

    /** Returns the retrieval mode. */
    public AzureAISearchQueryMode mode() {
        return mode;
    }

    /** Returns the search index name, or {@code null} in agentic mode. */
    public String indexName() {
        return indexName;
    }

    /** Returns the existing knowledge-base name, or {@code null} in index mode. */
    public String knowledgeBaseName() {
        return knowledgeBaseName;
    }

    /** Returns the strict field mapping. */
    public AzureAISearchFieldMapping fieldMapping() {
        return fieldMapping;
    }

    /** Returns the semantic configuration name when semantic ranking is enabled. */
    public String semanticConfigurationName() {
        return semanticConfigurationName;
    }

    /** Returns the optional client-side embedding provider. */
    public EmbeddingProvider embeddingProvider() {
        return embeddingProvider;
    }

    /** Returns the optional trusted static OData filter. */
    public String staticFilter() {
        return staticFilter;
    }

    /** Returns the maximum number of references. */
    public int topK() {
        return topK;
    }

    /** Returns the total injected-character budget. */
    public int contextCharacterBudget() {
        return contextCharacterBudget;
    }

    /** Returns the source-query character limit. */
    public int maxQueryCharacters() {
        return maxQueryCharacters;
    }

    /** Returns the per-reference character limit. */
    public int maxSnippetCharacters() {
        return maxSnippetCharacters;
    }

    /** Returns the maximum recent input messages used for stable agentic intent. */
    public int agenticMessageHistoryCount() {
        return agenticMessageHistoryCount;
    }

    /** Returns the agentic retrieval output-token bound. */
    public int agenticMaxOutputSizeTokens() {
        return agenticMaxOutputSizeTokens;
    }

    /** Returns the total deadline for one public operation. */
    public Duration operationTimeout() {
        return operationTimeout;
    }

    /** Returns the context-provider failure policy. */
    public AzureAISearchFailurePolicy failurePolicy() {
        return failurePolicy;
    }

    @Override
    public String toString() {
        return "AzureAISearchOptions{endpoint="
                + endpoint
                + ", authentication="
                + authentication
                + ", audience="
                + audience
                + ", mode="
                + mode
                + ", indexName="
                + (indexName == null ? null : "[REDACTED]")
                + ", knowledgeBaseName="
                + (knowledgeBaseName == null ? null : "[REDACTED]")
                + ", fieldMapping="
                + fieldMapping
                + ", semanticConfigurationName="
                + (semanticConfigurationName == null ? null : "[CONFIGURED]")
                + ", embeddingProvider="
                + (embeddingProvider == null ? null : "<caller-owned>")
                + ", staticFilter="
                + (staticFilter == null ? null : "[CONFIGURED]")
                + ", topK="
                + topK
                + ", contextCharacterBudget="
                + contextCharacterBudget
                + ", maxQueryCharacters="
                + maxQueryCharacters
                + ", maxSnippetCharacters="
                + maxSnippetCharacters
                + ", agenticMessageHistoryCount="
                + agenticMessageHistoryCount
                + ", agenticMaxOutputSizeTokens="
                + agenticMaxOutputSizeTokens
                + ", operationTimeout="
                + operationTimeout
                + ", failurePolicy="
                + failurePolicy
                + '}';
    }

    private void validateMode() {
        if (mode == AzureAISearchQueryMode.AGENTIC) {
            if (knowledgeBaseName == null || indexName != null) {
                throw new ValidationException("AGENTIC mode requires exactly one existing knowledgeBaseName.");
            }
            if (semanticConfigurationName != null || embeddingProvider != null) {
                throw new ValidationException(
                        "AGENTIC mode does not accept semanticConfigurationName or embeddingProvider.");
            }
            return;
        }
        if (indexName == null || knowledgeBaseName != null) {
            throw new ValidationException("Index retrieval modes require exactly one indexName.");
        }
        if ((mode == AzureAISearchQueryMode.SEMANTIC || mode == AzureAISearchQueryMode.SEMANTIC_HYBRID)
                && semanticConfigurationName == null) {
            throw new ValidationException("Semantic retrieval requires semanticConfigurationName.");
        }
        if (mode != AzureAISearchQueryMode.SEMANTIC
                && mode != AzureAISearchQueryMode.SEMANTIC_HYBRID
                && semanticConfigurationName != null) {
            throw new ValidationException("semanticConfigurationName is supported only by semantic retrieval modes.");
        }
    }

    private static String optionalName(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 128) {
            throw new ValidationException(name + " must be non-blank and at most 128 characters.");
        }
        return value;
    }

    private static String staticFilter(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 16_384) {
            throw new ValidationException("staticFilter must be non-blank and at most 16384 characters.");
        }
        return value;
    }

    private static int range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new ValidationException(name + " must be between " + minimum + " and " + maximum + ".");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }

    /** Builds immutable {@link AzureAISearchOptions} instances. */
    public static final class Builder {
        private final AzureAISearchEndpoint endpoint;

        private final AzureAISearchAuthentication authentication;

        private final String indexName;

        private final String knowledgeBaseName;

        private AzureAISearchAudience audience = AzureAISearchAudience.AZURE_PUBLIC_CLOUD;

        private AzureAISearchQueryMode mode;

        private AzureAISearchFieldMapping fieldMapping = AzureAISearchFieldMapping.defaults();

        private String semanticConfigurationName;

        private EmbeddingProvider embeddingProvider;

        private String staticFilter;

        private int topK = 5;

        private int contextCharacterBudget = 8_000;

        private int maxQueryCharacters = 8_000;

        private int maxSnippetCharacters = 1_500;

        private int agenticMessageHistoryCount = 10;

        private int agenticMaxOutputSizeTokens = 4_096;

        private Duration operationTimeout = Duration.ofSeconds(30);

        private AzureAISearchFailurePolicy failurePolicy = AzureAISearchFailurePolicy.FAIL_RUN;

        private Builder(
                AzureAISearchEndpoint endpoint,
                AzureAISearchAuthentication authentication,
                String indexName,
                String knowledgeBaseName,
                AzureAISearchQueryMode mode) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            this.authentication = Objects.requireNonNull(authentication, "authentication");
            this.indexName = indexName;
            this.knowledgeBaseName = knowledgeBaseName;
            this.mode = mode;
        }

        /** Sets the Azure cloud audience. */
        public Builder audience(AzureAISearchAudience value) {
            audience = value;
            return this;
        }

        /** Sets an index retrieval mode. */
        public Builder mode(AzureAISearchQueryMode value) {
            mode = value;
            return this;
        }

        /** Sets strict field names. */
        public Builder fieldMapping(AzureAISearchFieldMapping value) {
            fieldMapping = value;
            return this;
        }

        /** Sets the semantic configuration for semantic modes. */
        public Builder semanticConfigurationName(String value) {
            semanticConfigurationName = value;
            return this;
        }

        /** Sets a caller-owned embedding provider for client-side vectorization. */
        public Builder embeddingProvider(EmbeddingProvider value) {
            embeddingProvider = value;
            return this;
        }

        /** Sets an additional trusted static OData filter. */
        public Builder staticFilter(String value) {
            staticFilter = value;
            return this;
        }

        /** Sets the maximum number of references. */
        public Builder topK(int value) {
            topK = value;
            return this;
        }

        /** Sets the total context character budget. */
        public Builder contextCharacterBudget(int value) {
            contextCharacterBudget = value;
            return this;
        }

        /** Sets the source query character limit. */
        public Builder maxQueryCharacters(int value) {
            maxQueryCharacters = value;
            return this;
        }

        /** Sets the per-reference character limit. */
        public Builder maxSnippetCharacters(int value) {
            maxSnippetCharacters = value;
            return this;
        }

        /** Sets the recent-message count used by stable agentic retrieval. */
        public Builder agenticMessageHistoryCount(int value) {
            agenticMessageHistoryCount = value;
            return this;
        }

        /** Sets the stable agentic output-token bound. */
        public Builder agenticMaxOutputSizeTokens(int value) {
            agenticMaxOutputSizeTokens = value;
            return this;
        }

        /** Sets the total public-operation deadline. */
        public Builder operationTimeout(Duration value) {
            operationTimeout = value;
            return this;
        }

        /** Sets the context-provider retrieval failure policy. */
        public Builder failurePolicy(AzureAISearchFailurePolicy value) {
            failurePolicy = value;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public AzureAISearchOptions build() {
            return new AzureAISearchOptions(this);
        }
    }
}
