// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

/** Selects the Azure cloud audience used by Azure AI Search authentication. */
public enum AzureAISearchAudience {
    /** Azure public cloud. */
    AZURE_PUBLIC_CLOUD,

    /** Azure Government. */
    AZURE_GOVERNMENT,

    /** Azure operated by 21Vianet. */
    AZURE_CHINA
}
