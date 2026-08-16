// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

/** Controls whether eligible transient retrieval failures fail the run. */
public enum AzureAISearchFailurePolicy {
    /** Propagates every retrieval failure. */
    FAIL_RUN,

    /** Continues without search context only for explicitly classified transient failures. */
    CONTINUE_WITHOUT_CONTEXT
}
