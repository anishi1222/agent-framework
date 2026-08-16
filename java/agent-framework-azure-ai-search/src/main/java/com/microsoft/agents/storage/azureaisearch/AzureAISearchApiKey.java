// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.ValidationException;
import java.util.Objects;

/** Holds an Azure AI Search API key without rendering it in diagnostics. */
public final class AzureAISearchApiKey {
    private static final int MAX_CHARACTERS = 4096;

    private final String value;

    private AzureAISearchApiKey(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new ValidationException("Azure AI Search API key must not be blank.");
        }
        if (value.length() > MAX_CHARACTERS) {
            throw new ValidationException("Azure AI Search API key exceeds the supported length.");
        }
        this.value = value;
    }

    /**
     * Wraps one API key.
     *
     * @param value secret key value
     * @return redacting key wrapper
     */
    public static AzureAISearchApiKey of(String value) {
        return new AzureAISearchApiKey(value);
    }

    String secretValue() {
        return value;
    }

    @Override
    public String toString() {
        return "AzureAISearchApiKey[value=[REDACTED]]";
    }
}
