// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.RunCancellation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Continues until the latest response contains one configured completion marker. */
public final class CompletionMarkerLoopEvaluator implements LoopEvaluator {
    /** Placeholder replaced by the configured completion marker. */
    public static final String COMPLETION_MARKER_PLACEHOLDER = "{completion_marker}";

    /** Placeholder replaced by the latest response text. */
    public static final String LAST_RESPONSE_PLACEHOLDER = "{last_response}";

    /** Default continuation feedback template. */
    public static final String DEFAULT_FEEDBACK_MESSAGE_TEMPLATE =
            "Continue working on the request. When you have fully completed the task, end your "
                    + "response with the marker '"
                    + COMPLETION_MARKER_PLACEHOLDER
                    + "' to indicate completion.";

    private final String marker;

    private final String feedbackTemplate;

    /**
     * Creates a marker evaluator with a default reminder.
     *
     * @param marker completion marker
     */
    public CompletionMarkerLoopEvaluator(String marker) {
        this(marker, DEFAULT_FEEDBACK_MESSAGE_TEMPLATE);
    }

    /**
     * Creates a marker evaluator.
     *
     * @param marker completion marker
     * @param feedbackTemplate reminder with optional {@code {completion_marker}} and {@code
     *     {last_response}} placeholders
     */
    public CompletionMarkerLoopEvaluator(String marker, String feedbackTemplate) {
        if (marker == null || marker.isBlank()) {
            throw new IllegalArgumentException("marker must not be blank.");
        }
        this.marker = marker;
        this.feedbackTemplate = Objects.requireNonNull(feedbackTemplate, "feedbackTemplate");
    }

    @Override
    public CompletionStage<LoopEvaluation> evaluateAsync(LoopContext context, RunCancellation cancellation) {
        String text = context.lastResponse().text();
        if (text.contains(marker)) {
            return CompletableFuture.completedFuture(LoopEvaluation.stop());
        }
        return CompletableFuture.completedFuture(LoopEvaluation.continueWithFeedback(feedbackTemplate
                .replace(COMPLETION_MARKER_PLACEHOLDER, marker)
                .replace(LAST_RESPONSE_PLACEHOLDER, text)));
    }
}
