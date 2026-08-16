// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

/** Selects the Azure AI Search retrieval strategy. */
public enum AzureAISearchQueryMode {
    /** Lexical full-text retrieval. */
    FULL_TEXT,

    /** Vector-only retrieval. */
    VECTOR,

    /** Reciprocal-rank-fused full-text and vector retrieval. */
    HYBRID,

    /** Semantic reranking over full-text candidates. */
    SEMANTIC,

    /** Semantic reranking over full-text and vector candidates. */
    SEMANTIC_HYBRID,

    /** Existing Azure AI Search knowledge-base retrieval using stable minimal semantic intent. */
    AGENTIC;

    boolean usesText() {
        return this == FULL_TEXT || this == HYBRID || this == SEMANTIC || this == SEMANTIC_HYBRID;
    }

    boolean usesVector() {
        return this == VECTOR || this == HYBRID || this == SEMANTIC_HYBRID;
    }

    boolean usesSemantic() {
        return this == SEMANTIC || this == SEMANTIC_HYBRID;
    }
}
