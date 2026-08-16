// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides bounded Content Understanding analysis and analyzer lifecycle operations.
 *
 * <p>Analysis and analyzer creation are long-running operations represented by framework
 * {@link RunHandle} instances. Cancelling a handle cancels local HTTP/polling work; the stable
 * Content Understanding 1.0.0 SDK exposes no remote operation-cancel endpoint.
 */
public final class AzureContentUnderstandingClient implements AutoCloseable {
    private final AzureContentUnderstandingOptions options;
    private final ContentUnderstandingTransport transport;
    private final java.util.Set<RunHandle<?>> activeRuns = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a client using the stable Azure Content Understanding 1.0.0 SDK. */
    public AzureContentUnderstandingClient(AzureContentUnderstandingOptions options) {
        this(options, ContentUnderstandingSdkTransport.create(options));
    }

    AzureContentUnderstandingClient(AzureContentUnderstandingOptions options, ContentUnderstandingTransport transport) {
        this.options = Objects.requireNonNull(options, "options");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** Returns immutable client options. */
    public AzureContentUnderstandingOptions options() {
        return options;
    }

    /** Starts a long-running analysis. */
    public RunHandle<ContentAnalysisResult> startAnalysis(ContentAnalysisRequest request) {
        return startAnalysis(request, new DefaultRunCancellation());
    }

    /** Starts a long-running analysis linked to caller cancellation. */
    public RunHandle<ContentAnalysisResult> startAnalysis(
            ContentAnalysisRequest request, RunCancellation cancellation) {
        validateInputs(request);
        RunHandleSource<ContentAnalysisResult> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        RunHandle<ContentAnalysisResult> handle = source.handle();
        synchronized (lifecycleLock) {
            ensureOpen();
            activeRuns.add(handle);
        }
        CompletionStage<ContentAnalysisResult> operation;
        try {
            operation = transport.analyzeAsync(request, source.cancellation());
        } catch (RuntimeException failure) {
            activeRuns.remove(handle);
            source.tryFail(failure);
            return handle;
        }
        operation.whenComplete((result, failure) -> {
            activeRuns.remove(handle);
            if (failure != null) {
                source.tryFail(unwrap(failure));
            } else {
                source.tryComplete(result);
            }
        });
        return handle;
    }

    /** Runs an analysis asynchronously. */
    public CompletionStage<ContentAnalysisResult> analyzeAsync(
            ContentAnalysisRequest request, RunCancellation cancellation) {
        return startAnalysis(request, cancellation).resultAsync();
    }

    /** Starts long-running analyzer creation. */
    public RunHandle<ContentAnalyzerDefinition> startCreateAnalyzer(
            ContentAnalyzerRequest request, RunCancellation cancellation) {
        RunHandleSource<ContentAnalyzerDefinition> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        RunHandle<ContentAnalyzerDefinition> handle = source.handle();
        synchronized (lifecycleLock) {
            ensureOpen();
            activeRuns.add(handle);
        }
        CompletionStage<ContentAnalyzerDefinition> operation;
        try {
            operation =
                    transport.createAnalyzerAsync(Objects.requireNonNull(request, "request"), source.cancellation());
        } catch (RuntimeException failure) {
            activeRuns.remove(handle);
            source.tryFail(failure);
            return handle;
        }
        operation.whenComplete((result, failure) -> {
            activeRuns.remove(handle);
            if (failure != null) {
                source.tryFail(unwrap(failure));
            } else {
                source.tryComplete(result);
            }
        });
        return handle;
    }

    /** Gets an analyzer. */
    public CompletionStage<ContentAnalyzerDefinition> getAnalyzerAsync(
            String analyzerId, RunCancellation cancellation) {
        ensureOpen();
        return transport.getAnalyzerAsync(analyzerId, Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Updates an analyzer with a complete replacement definition. */
    public CompletionStage<ContentAnalyzerDefinition> updateAnalyzerAsync(
            ContentAnalyzerRequest request, RunCancellation cancellation) {
        ensureOpen();
        return transport.updateAnalyzerAsync(
                Objects.requireNonNull(request, "request"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Deletes an explicitly selected analyzer. */
    public CompletionStage<Void> deleteAnalyzerAsync(String analyzerId, RunCancellation cancellation) {
        ensureOpen();
        return transport.deleteAnalyzerAsync(analyzerId, Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Lists one bounded analyzer page. */
    public CompletionStage<ContentUnderstandingPage<ContentAnalyzerDefinition>> listAnalyzersAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        if (limit <= 0 || limit > options.maxPageSize()) {
            throw new IllegalArgumentException("limit must be between 1 and " + options.maxPageSize() + ".");
        }
        return transport.listAnalyzersAsync(limit, after, Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Cancels active local pollers and never deletes remote analyzers or results. */
    @Override
    public void close() {
        List<RunHandle<?>> runs;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            runs = List.copyOf(activeRuns);
        }
        runs.forEach(RunHandle::cancel);
        activeRuns.clear();
    }

    private void validateInputs(ContentAnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.inputs().size() > options.maxInputs()) {
            throw new IllegalArgumentException("inputs exceeds maxInputs " + options.maxInputs() + ".");
        }
        long bytes = request.inputs().stream()
                .filter(ContentBytesInput.class::isInstance)
                .map(ContentBytesInput.class::cast)
                .mapToLong(ContentBytesInput::byteLength)
                .sum();
        if (bytes > options.maxInputBytes()) {
            throw new IllegalArgumentException(
                    "Aggregate byte content exceeds maxInputBytes " + options.maxInputBytes() + ".");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AzureContentUnderstandingClient is closed.");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
