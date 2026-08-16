// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exposes one OpenAI Responses SSE run as bounded encoded frames and explicit cancellation. */
public final class OpenAIResponsesHostedRun {
    private final String responseId;

    private final String hostingRunId;

    private final Flow.Publisher<byte[]> frames;

    private final CompletionStage<Void> completionAsync;

    private final RunCancellation cancellation;

    private final Runnable discard;

    private final AtomicBoolean discarded = new AtomicBoolean();

    OpenAIResponsesHostedRun(
            String responseId,
            String hostingRunId,
            Flow.Publisher<byte[]> frames,
            CompletionStage<Void> completionAsync,
            RunCancellation cancellation,
            Runnable discard) {
        this.responseId = requireNonBlank(responseId, "responseId");
        this.hostingRunId = requireNonBlank(hostingRunId, "hostingRunId");
        this.frames = java.util.Objects.requireNonNull(frames, "frames");
        this.completionAsync = java.util.Objects.requireNonNull(completionAsync, "completionAsync");
        this.cancellation = java.util.Objects.requireNonNull(cancellation, "cancellation");
        this.discard = java.util.Objects.requireNonNull(discard, "discard");
    }

    /**
     * Returns the OpenAI response identifier.
     *
     * @return response identifier
     */
    public String responseId() {
        return responseId;
    }

    /**
     * Returns the underlying host-generated run identifier.
     *
     * @return hosting run identifier
     */
    public String hostingRunId() {
        return hostingRunId;
    }

    /**
     * Returns the cold single-subscriber encoded SSE frame publisher.
     *
     * @return frame publisher
     */
    public Flow.Publisher<byte[]> frames() {
        return frames;
    }

    /**
     * Returns completion after all terminal frames have been delivered.
     *
     * @return completion stage
     */
    public CompletionStage<Void> completionAsync() {
        return completionAsync;
    }

    /**
     * Requests cancellation.
     *
     * @return {@code true} only when this call initiated cancellation
     */
    public boolean cancel() {
        return cancellation.cancel();
    }

    /** Cancels and discards state when the transport cannot deliver the complete stream. */
    public void discardUndelivered() {
        if (discarded.compareAndSet(false, true)) {
            cancellation.cancel();
            discard.run();
        }
    }

    private static String requireNonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
