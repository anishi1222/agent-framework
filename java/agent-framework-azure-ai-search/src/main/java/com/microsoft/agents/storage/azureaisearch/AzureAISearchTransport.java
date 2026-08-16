// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.concurrent.CompletionStage;

interface AzureAISearchTransport {
    CompletionStage<List<AzureAISearchResult>> searchAsync(AzureAISearchRequest request, RunCancellation cancellation);
}
