// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

interface ContentUnderstandingTransport {
    CompletionStage<ContentAnalysisResult> analyzeAsync(ContentAnalysisRequest request, RunCancellation cancellation);

    CompletionStage<ContentAnalyzerDefinition> createAnalyzerAsync(
            ContentAnalyzerRequest request, RunCancellation cancellation);

    CompletionStage<ContentAnalyzerDefinition> getAnalyzerAsync(String analyzerId, RunCancellation cancellation);

    CompletionStage<ContentAnalyzerDefinition> updateAnalyzerAsync(
            ContentAnalyzerRequest request, RunCancellation cancellation);

    CompletionStage<Void> deleteAnalyzerAsync(String analyzerId, RunCancellation cancellation);

    CompletionStage<ContentUnderstandingPage<ContentAnalyzerDefinition>> listAnalyzersAsync(
            int limit, String after, RunCancellation cancellation);
}
