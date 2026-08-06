// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

/** Identifies the configured Azure OpenAI authentication mechanism. */
public enum AzureOpenAIAuthenticationMode {
    /** Azure OpenAI API-key authentication. */
    API_KEY,
    /** Microsoft Entra bearer-token authentication. */
    TOKEN_CREDENTIAL
}
