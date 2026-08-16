// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.ai.projects.BetaEvaluatorsAsyncClient;
import com.azure.ai.projects.ConnectionsAsyncClient;
import com.azure.ai.projects.DatasetsAsyncClient;
import com.azure.ai.projects.DeploymentsAsyncClient;
import com.azure.ai.projects.IndexesAsyncClient;
import com.azure.ai.projects.models.AIProjectIndex;
import com.azure.ai.projects.models.Connection;
import com.azure.ai.projects.models.DatasetVersion;
import com.azure.ai.projects.models.Deployment;
import com.azure.ai.projects.models.EvaluatorVersion;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.microsoft.agents.azure.AzureAuthenticationProvider;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.core.internal.http.BoundedBodyHandlers;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provides Foundry cloud evaluation lifecycle and project-resource discovery.
 *
 * <p>This module is a cloud integration rather than the future provider-neutral evaluator
 * framework. Public signatures contain only framework and JDK types. Evaluator management is an
 * explicit preview opt-in because {@code azure-ai-projects:2.3.0} exposes it through
 * {@code BetaEvaluatorsAsyncClient}.
 */
public final class FoundryEvaluationClient implements AutoCloseable {
    private static final String AI_SCOPE = "https://ai.azure.com/.default";
    private static final StrictJsonCodec JSON =
            new StrictJsonCodec(16 * 1024 * 1024, 16 * 1024 * 1024, 64, 1_048_576, 256, 100_000);

    private final FoundryEvaluationClientOptions options;
    private final java.net.http.HttpClient httpClient;
    private final ExecutorService ownedExecutor;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService ownedScheduler;
    private final ConnectionsAsyncClient connections;
    private final DatasetsAsyncClient datasets;
    private final DeploymentsAsyncClient deployments;
    private final IndexesAsyncClient indexes;
    private final BetaEvaluatorsAsyncClient evaluators;
    private final Set<PollOperation> polls = ConcurrentHashMap.newKeySet();
    private final Set<HttpOperation> requests = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a client using JDK HTTP for OpenAI Evals and the verified Azure Projects SDK for
     * project discovery.
     *
     * @param options immutable options
     */
    public FoundryEvaluationClient(FoundryEvaluationClientOptions options) {
        this(options, null, null);
    }

    FoundryEvaluationClient(
            FoundryEvaluationClientOptions options,
            java.net.http.HttpClient injectedHttp,
            com.azure.core.http.HttpClient injectedAzureHttp) {
        this.options = Objects.requireNonNull(options, "options");
        Executor executor = options.executor();
        if (executor == null) {
            ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            executor = ownedExecutor;
        } else {
            ownedExecutor = null;
        }
        httpClient = injectedHttp == null
                ? java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .connectTimeout(options.requestTimeout())
                        .executor(executor)
                        .build()
                : injectedHttp;

        if (options.scheduler() == null) {
            ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(
                    1,
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("agent-framework-foundry-evals-", 0)
                            .factory());
            created.setRemoveOnCancelPolicy(true);
            scheduler = created;
            ownedScheduler = created;
        } else {
            scheduler = options.scheduler();
            ownedScheduler = null;
        }

        RetryOptions retries = new RetryOptions(new ExponentialBackoffOptions()
                .setMaxRetries(options.maxRetries())
                .setBaseDelay(Duration.ofMillis(200))
                .setMaxDelay(Duration.ofSeconds(5)));
        AIProjectClientBuilder builder = new AIProjectClientBuilder()
                .endpoint(options.projectEndpoint().toString())
                .credential(tokenCredential(options.authenticationProvider()))
                .retryOptions(retries)
                .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE));
        if (injectedAzureHttp != null) {
            builder.httpClient(injectedAzureHttp);
        }
        connections = builder.buildConnectionsAsyncClient();
        datasets = builder.buildDatasetsAsyncClient();
        deployments = builder.buildDeploymentsAsyncClient();
        indexes = builder.buildIndexesAsyncClient();
        evaluators = options.previewEvaluatorManagement() ? builder.beta().buildBetaEvaluatorsAsyncClient() : null;
    }

    /** Returns immutable client options. */
    public FoundryEvaluationClientOptions options() {
        return options;
    }

    /** Creates an evaluation definition through the Foundry OpenAI Evals endpoint. */
    public CompletionStage<FoundryEvaluation> createEvaluationAsync(
            FoundryEvaluationRequest request, RunCancellation cancellation) {
        ensureOpen();
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        if (request.name() != null) {
            body.put("name", StateValue.string(request.name()));
        }
        body.put("data_source_config", request.dataSourceConfig());
        body.put("testing_criteria", StateValue.array(request.testingCriteria()));
        if (!request.metadata().isEmpty()) {
            body.put("metadata", stringMap(request.metadata()));
        }
        return sendJsonAsync(
                        "POST",
                        evalsUri(),
                        StateValue.object(body),
                        Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(payload -> evaluation(payload.body()));
    }

    /** Gets an evaluation definition. */
    public CompletionStage<FoundryEvaluation> getEvaluationAsync(String evaluationId, RunCancellation cancellation) {
        ensureOpen();
        return sendJsonAsync("GET", evalUri(evaluationId), null, Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(payload -> evaluation(payload.body()));
    }

    /** Deletes an explicitly selected evaluation definition. */
    public CompletionStage<Void> deleteEvaluationAsync(String evaluationId, RunCancellation cancellation) {
        ensureOpen();
        return sendJsonAsync(
                        "DELETE", evalUri(evaluationId), null, Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(ignored -> null);
    }

    /** Creates an evaluation run and returns its immediate state. */
    public CompletionStage<FoundryEvaluationRun> createRunAsync(
            FoundryEvaluationRunRequest request, RunCancellation cancellation) {
        ensureOpen();
        LinkedHashMap<String, StateValue> body = new LinkedHashMap<>();
        if (request.name() != null) {
            body.put("name", StateValue.string(request.name()));
        }
        body.put("data_source", request.dataSource());
        if (!request.metadata().isEmpty()) {
            body.put("metadata", stringMap(request.metadata()));
        }
        return sendJsonAsync(
                        "POST",
                        runsUri(request.evaluationId()),
                        StateValue.object(body),
                        Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(payload -> run(payload.body(), request.evaluationId()));
    }

    /** Gets the current state of an evaluation run. */
    public CompletionStage<FoundryEvaluationRun> getRunAsync(
            String evaluationId, String runId, RunCancellation cancellation) {
        ensureOpen();
        return sendJsonAsync(
                        "GET", runUri(evaluationId, runId), null, Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(payload -> run(payload.body(), evaluationId));
    }

    /** Requests cancellation of an evaluation run. */
    public CompletionStage<FoundryEvaluationRun> cancelRunAsync(
            String evaluationId, String runId, RunCancellation cancellation) {
        ensureOpen();
        return sendJsonAsync(
                        "POST", runUri(evaluationId, runId), null, Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(payload -> run(payload.body(), evaluationId));
    }

    /** Lists one bounded page of evaluation output items. */
    public CompletionStage<FoundryEvaluationPage<FoundryEvaluationOutputItem>> listOutputItemsAsync(
            String evaluationId, String runId, int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        int safeLimit = pageSize(limit);
        String query = "?limit=" + safeLimit;
        if (after != null) {
            query += "&after=" + encode(nonBlank(after, "after"));
        }
        String inputCursor = after;
        return sendJsonAsync(
                        "GET",
                        URI.create(runUri(evaluationId, runId) + "/output_items" + query),
                        null,
                        Objects.requireNonNull(cancellation, "cancellation"))
                .thenApply(payload -> outputPage(payload.body(), inputCursor));
    }

    /**
     * Starts an evaluation run, polls it, and fetches all bounded output pages.
     *
     * <p>Cancellation or timeout requests best-effort service-side cancellation because this
     * operation created the run.
     *
     * @param request run request
     * @return explicitly cancellable run handle
     */
    public RunHandle<FoundryEvaluationResult> startRun(FoundryEvaluationRunRequest request) {
        return startRun(request, new DefaultRunCancellation());
    }

    /**
     * Starts an evaluation run linked to caller-owned cancellation.
     *
     * <p>Cancellation or timeout requests best-effort service-side cancellation because this
     * operation created the run.
     *
     * @param request run request
     * @param cancellation cancellation signal
     * @return explicitly cancellable run handle
     */
    public RunHandle<FoundryEvaluationResult> startRun(
            FoundryEvaluationRunRequest request, RunCancellation cancellation) {
        ensureOpen();
        RunHandleSource<FoundryEvaluationResult> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        createRunAsync(Objects.requireNonNull(request, "request"), source.cancellation())
                .whenComplete((created, createFailure) -> {
                    if (createFailure != null) {
                        source.tryFail(unwrap(createFailure));
                        return;
                    }
                    if (source.isTerminal()) {
                        if (source.cancellation().isCancellationRequested()) {
                            cancelRunBestEffort(created.evaluationId(), created.id());
                        }
                        return;
                    }
                    try {
                        awaitRunAsync(created.evaluationId(), created.id(), source.cancellation(), true)
                                .thenCompose(terminal -> {
                                    if (terminal.status().equals(FoundryEvaluationStatus.CANCELLED)) {
                                        return CompletableFuture.failedFuture(new RunCancelledException());
                                    }
                                    if (terminal.status().equals(FoundryEvaluationStatus.FAILED)) {
                                        return CompletableFuture.failedFuture(new FoundryEvaluationException(
                                                "Foundry evaluation run failed.",
                                                null,
                                                FoundryEvaluationException.Kind.SERVICE,
                                                null,
                                                null,
                                                terminal.errorCode() == null
                                                        ? "evaluation_failed"
                                                        : terminal.errorCode(),
                                                null));
                                    }
                                    return collectOutputItemsAsync(
                                                    terminal.evaluationId(), terminal.id(), source.cancellation())
                                            .thenApply(items -> new FoundryEvaluationResult(terminal, items));
                                })
                                .whenComplete((result, failure) -> {
                                    if (failure != null) {
                                        source.tryFail(unwrap(failure));
                                    } else {
                                        source.tryComplete(result);
                                    }
                                });
                    } catch (RuntimeException pollHandoffFailure) {
                        cancelRunBestEffort(created.evaluationId(), created.id());
                        source.tryFail(pollHandoffFailure);
                    }
                });
        return source.handle();
    }

    /**
     * Observes an existing run with bounded exponential delay and cancellation.
     *
     * <p>Cancellation and timeout stop local polling only. They do not request service-side
     * cancellation of a run that this client did not start.
     *
     * @param evaluationId evaluation identifier
     * @param runId run identifier
     * @param cancellation cancellation signal for local polling
     * @return terminal run stage
     */
    public CompletionStage<FoundryEvaluationRun> awaitRunAsync(
            String evaluationId, String runId, RunCancellation cancellation) {
        return awaitRunAsync(evaluationId, runId, cancellation, false);
    }

    /**
     * Observes an existing run and optionally requests service-side cancellation when local polling
     * is cancelled or times out.
     *
     * @param evaluationId evaluation identifier
     * @param runId run identifier
     * @param cancellation cancellation signal for local polling
     * @param cancelRemoteOnTimeoutOrCancellation whether to request best-effort service cancellation
     * @return terminal run stage
     */
    public CompletionStage<FoundryEvaluationRun> awaitRunAsync(
            String evaluationId,
            String runId,
            RunCancellation cancellation,
            boolean cancelRemoteOnTimeoutOrCancellation) {
        PollOperation poll;
        synchronized (lifecycleLock) {
            ensureOpen();
            poll = new PollOperation(
                    nonBlank(evaluationId, "evaluationId"),
                    nonBlank(runId, "runId"),
                    Objects.requireNonNull(cancellation, "cancellation"),
                    cancelRemoteOnTimeoutOrCancellation);
            polls.add(poll);
        }
        poll.result.whenComplete((ignored, failure) -> {
            polls.remove(poll);
            poll.close();
        });
        poll.poll();
        return poll.result.minimalCompletionStage();
    }

    /** Lists latest dataset versions through Azure Projects 2.3.0. */
    public CompletionStage<FoundryEvaluationPage<FoundryDataset>> listDatasetsAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return discoveryPage(
                datasets.listLatestDatasetVersions().map(FoundryEvaluationClient::dataset),
                pageSize(limit),
                after,
                item -> item.name() + ":" + item.version(),
                cancellation);
    }

    /** Lists Foundry project connections without requesting credentials. */
    public CompletionStage<FoundryEvaluationPage<FoundryProjectResource>> listConnectionsAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return discoveryPage(
                connections.listConnections().map(FoundryEvaluationClient::connection),
                pageSize(limit),
                after,
                FoundryProjectResource::name,
                cancellation);
    }

    /** Lists model and agent deployments through Azure Projects 2.3.0. */
    public CompletionStage<FoundryEvaluationPage<FoundryProjectResource>> listDeploymentsAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return discoveryPage(
                deployments.listDeployments().map(FoundryEvaluationClient::deployment),
                pageSize(limit),
                after,
                FoundryProjectResource::name,
                cancellation);
    }

    /** Lists latest project index versions through Azure Projects 2.3.0. */
    public CompletionStage<FoundryEvaluationPage<FoundryProjectResource>> listIndexesAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return discoveryPage(
                indexes.listLatestIndexVersions().map(FoundryEvaluationClient::index),
                pageSize(limit),
                after,
                item -> item.name() + ":" + item.version(),
                cancellation);
    }

    /**
     * Lists preview evaluator versions.
     *
     * @throws IllegalStateException unless preview evaluator management was explicitly enabled
     */
    public CompletionStage<FoundryEvaluationPage<FoundryEvaluator>> listEvaluatorsAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        if (evaluators == null) {
            throw new IllegalStateException(
                    "Evaluator management is preview; enable previewEvaluatorManagement explicitly.");
        }
        return discoveryPage(
                evaluators.listLatestEvaluatorVersions().map(FoundryEvaluationClient::evaluator),
                pageSize(limit),
                after,
                item -> item.name() + ":" + item.version(),
                cancellation);
    }

    /** Cancels pollers and releases only framework-created executors. */
    @Override
    public void close() {
        List<PollOperation> activePolls;
        List<HttpOperation> activeRequests;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            activePolls = List.copyOf(polls);
            activeRequests = List.copyOf(requests);
        }
        activePolls.forEach(PollOperation::cancel);
        activeRequests.forEach(HttpOperation::cancel);
        requests.clear();
        if (ownedScheduler != null) {
            ownedScheduler.shutdownNow();
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
        awaitTermination(ownedScheduler, "scheduler");
        awaitTermination(ownedExecutor, "executor");
    }

    private CompletionStage<List<FoundryEvaluationOutputItem>> collectOutputItemsAsync(
            String evaluationId, String runId, RunCancellation cancellation) {
        ArrayList<FoundryEvaluationOutputItem> items = new ArrayList<>();
        CompletableFuture<List<FoundryEvaluationOutputItem>> result = new CompletableFuture<>();
        collectOutputPage(evaluationId, runId, null, 0, items, cancellation, result);
        return result.minimalCompletionStage();
    }

    private void collectOutputPage(
            String evaluationId,
            String runId,
            String cursor,
            int page,
            ArrayList<FoundryEvaluationOutputItem> items,
            RunCancellation cancellation,
            CompletableFuture<List<FoundryEvaluationOutputItem>> result) {
        if (page >= options.maxPages()) {
            result.completeExceptionally(protocol("output_page_limit"));
            return;
        }
        listOutputItemsAsync(evaluationId, runId, options.maxPageSize(), cursor, cancellation)
                .whenComplete((next, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure));
                    } else {
                        items.addAll(next.items());
                        if (next.hasMore()) {
                            collectOutputPage(
                                    evaluationId, runId, next.nextCursor(), page + 1, items, cancellation, result);
                        } else {
                            result.complete(List.copyOf(items));
                        }
                    }
                });
    }

    private CompletionStage<HttpPayload> sendJsonAsync(
            String method, URI uri, StateValue body, RunCancellation cancellation) {
        HttpOperation operation;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("FoundryEvaluationClient is closed."));
            }
            operation = new HttpOperation(method, uri, body, cancellation);
            requests.add(operation);
        }
        operation.result.whenComplete((ignored, failure) -> {
            requests.remove(operation);
            operation.close();
        });
        operation.sendAttempt(0);
        return operation.result.minimalCompletionStage();
    }

    private final class HttpOperation implements AutoCloseable {
        private final String method;
        private final URI uri;
        private final StateValue body;
        private final RunCancellation cancellation;
        private final CompletableFuture<HttpPayload> result = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> retryTask = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<HttpResponse<byte[]>>> upstream = new AtomicReference<>();
        private final Object requestDispatchLock = new Object();
        private final RunCancellationRegistration registration;

        private HttpOperation(String method, URI uri, StateValue body, RunCancellation cancellation) {
            this.method = method;
            this.uri = uri;
            this.body = body;
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            registration = RunCancellations.register(cancellation, this::cancel);
        }

        private void sendAttempt(int attempt) {
            if (finished.get()) {
                return;
            }
            if (cancellation.isCancellationRequested()) {
                cancel();
                return;
            }
            options.authenticationProvider()
                    .getTokenAsync(AzureTokenRequest.forScopes(AI_SCOPE), cancellation)
                    .whenComplete((token, authFailure) -> {
                        if (finished.get()) {
                            return;
                        }
                        if (authFailure != null) {
                            fail(new FoundryEvaluationException(
                                    "Foundry evaluation authentication failed.",
                                    unwrap(authFailure),
                                    FoundryEvaluationException.Kind.AUTHENTICATION,
                                    null,
                                    null,
                                    "authentication_failed",
                                    null));
                            return;
                        }
                        try {
                            byte[] bytes = body == null ? null : JSON.write(body);
                            if (bytes != null && bytes.length > options.maxResponseBytes()) {
                                fail(protocol("request_too_large"));
                                return;
                            }
                            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                                    .timeout(options.requestTimeout())
                                    .header("Authorization", "Bearer " + token.token())
                                    .header("Accept", "application/json")
                                    .header("User-Agent", "agent-framework-java-foundry-evaluations");
                            if (bytes == null) {
                                request.method(method, HttpRequest.BodyPublishers.noBody());
                            } else {
                                request.header("Content-Type", "application/json")
                                        .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
                            }
                            CompletableFuture<HttpResponse<byte[]>> pending;
                            synchronized (requestDispatchLock) {
                                if (finished.get()) {
                                    return;
                                }
                                pending = httpClient.sendAsync(
                                        request.build(),
                                        BoundedBodyHandlers.byteArray(
                                                options.maxResponseBytes(), () -> protocol("response_too_large")));
                                upstream.set(pending);
                            }
                            pending.whenComplete((response, transportFailure) ->
                                    handleResponse(pending, response, transportFailure, attempt));
                        } catch (RuntimeException failure) {
                            fail(failure);
                        }
                    });
        }

        private void handleResponse(
                CompletableFuture<HttpResponse<byte[]>> pending,
                HttpResponse<byte[]> response,
                Throwable transportFailure,
                int attempt) {
            upstream.compareAndSet(pending, null);
            if (finished.get()) {
                return;
            }
            if (transportFailure != null) {
                Throwable cause = unwrap(transportFailure);
                if (cause instanceof FoundryEvaluationException) {
                    fail(cause);
                    return;
                }
                fail(new FoundryEvaluationException(
                        "Foundry evaluation transport failed.",
                        cause,
                        FoundryEvaluationException.Kind.TRANSPORT,
                        null,
                        null,
                        "transport_failed",
                        null));
                return;
            }
            byte[] responseBytes = response.body() == null ? new byte[0] : response.body();
            if (responseBytes.length > options.maxResponseBytes()) {
                fail(protocol("response_too_large"));
                return;
            }
            int status = response.statusCode();
            Duration retryAfter = retryAfter(response);
            if ((status == 429 || status >= 500) && attempt < options.maxRetries()) {
                long delay = retryAfter == null ? Math.min(5000L, 200L << attempt) : cappedRetryDelayMillis(retryAfter);
                try {
                    scheduleRetry(attempt + 1, delay);
                } catch (RuntimeException failure) {
                    fail(failure);
                }
                return;
            }
            if (status < 200 || status >= 300) {
                fail(serviceFailure(status, response, responseBytes, retryAfter));
                return;
            }
            try {
                StateValue parsed = responseBytes.length == 0 ? StateValue.object(Map.of()) : parse(responseBytes);
                succeed(new HttpPayload(
                        requireObject(parsed, "response"),
                        response.headers().firstValue("x-request-id").orElse(null)));
            } catch (RuntimeException failure) {
                fail(failure);
            }
        }

        private void scheduleRetry(int attempt, long delay) {
            AtomicReference<ScheduledFuture<?>> holder = new AtomicReference<>();
            ScheduledFuture<?> next = scheduler.schedule(
                    () -> {
                        retryTask.compareAndSet(holder.get(), null);
                        sendAttempt(attempt);
                    },
                    Math.max(1, delay),
                    TimeUnit.MILLISECONDS);
            holder.set(next);
            ScheduledFuture<?> prior = retryTask.getAndSet(next);
            if (prior != null) {
                prior.cancel(false);
            }
            if (finished.get() && retryTask.compareAndSet(next, null)) {
                next.cancel(false);
            }
        }

        private void succeed(HttpPayload payload) {
            if (finished.compareAndSet(false, true)) {
                result.complete(payload);
            }
        }

        private void fail(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                result.completeExceptionally(failure);
            }
        }

        private void cancel() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cancelPending();
            result.completeExceptionally(new RunCancelledException());
        }

        private void cancelPending() {
            ScheduledFuture<?> scheduled = retryTask.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            CompletableFuture<HttpResponse<byte[]>> pending;
            synchronized (requestDispatchLock) {
                pending = upstream.getAndSet(null);
            }
            if (pending != null) {
                pending.cancel(true);
            }
        }

        @Override
        public void close() {
            registration.close();
            cancelPending();
        }
    }

    private <T> CompletionStage<FoundryEvaluationPage<T>> discoveryPage(
            Flux<T> flux,
            int limit,
            String after,
            java.util.function.Function<T, String> cursor,
            RunCancellation cancellation) {
        int maximum = options.maxPages() * options.maxPageSize();
        return stage(flux.take(maximum).collectList(), cancellation).thenApply(all -> {
            int start = 0;
            if (after != null) {
                start = -1;
                for (int index = 0; index < all.size(); index++) {
                    if (after.equals(cursor.apply(all.get(index)))) {
                        start = index + 1;
                        break;
                    }
                }
                if (start < 0) {
                    return new FoundryEvaluationPage<>(List.of(), null, false);
                }
            }
            int end = Math.min(all.size(), start + limit);
            List<T> page = List.copyOf(all.subList(start, end));
            boolean hasMore = end < all.size();
            String next = hasMore ? cursor.apply(page.getLast()) : null;
            if (hasMore && Objects.equals(next, after)) {
                throw protocol("discovery_cursor_loop");
            }
            return new FoundryEvaluationPage<>(page, next, hasMore);
        });
    }

    private static <T> CompletionStage<T> stage(Mono<T> mono, RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletableFuture<T> upstream = mono.toFuture();
        CompletableFuture<T> result = new CompletableFuture<>();
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            result.completeExceptionally(new RunCancelledException());
            upstream.cancel(true);
        });
        upstream.whenComplete((value, failure) -> {
            registration.close();
            if (failure != null) {
                result.completeExceptionally(unwrap(failure));
            } else {
                result.complete(value);
            }
        });
        return result.minimalCompletionStage();
    }

    private final class PollOperation implements AutoCloseable {
        private final String evaluationId;
        private final String runId;
        private final RunCancellation cancellation;
        private final DefaultRunCancellation requestCancellation = new DefaultRunCancellation();
        private final CompletableFuture<FoundryEvaluationRun> result = new CompletableFuture<>();
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private final ScheduledFuture<?> deadlineTask;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final RunCancellationRegistration registration;
        private final boolean cancelRemoteOnTimeoutOrCancellation;
        private int attempt;

        private PollOperation(
                String evaluationId,
                String runId,
                RunCancellation cancellation,
                boolean cancelRemoteOnTimeoutOrCancellation) {
            this.evaluationId = evaluationId;
            this.runId = runId;
            this.cancellation = cancellation;
            this.cancelRemoteOnTimeoutOrCancellation = cancelRemoteOnTimeoutOrCancellation;
            registration = RunCancellations.register(cancellation, this::cancel);
            deadlineTask = scheduler.schedule(
                    this::timeout, Math.max(1, options.operationTimeout().toMillis()), TimeUnit.MILLISECONDS);
        }

        private void poll() {
            if (finished.get()) {
                return;
            }
            getRunAsync(evaluationId, runId, requestCancellation).whenComplete((run, failure) -> {
                if (failure != null) {
                    if (finished.compareAndSet(false, true)) {
                        result.completeExceptionally(unwrap(failure));
                    }
                } else if (!run.status().isKnown()) {
                    if (finished.compareAndSet(false, true)) {
                        result.completeExceptionally(protocol("unknown_evaluation_status"));
                    }
                } else if (run.status().isTerminal()) {
                    if (finished.compareAndSet(false, true)) {
                        result.complete(run);
                    }
                } else {
                    long initial = options.initialPollDelay().toMillis();
                    long maximum = options.maxPollDelay().toMillis();
                    long delay = Math.min(maximum, initial << Math.min(attempt++, 20));
                    ScheduledFuture<?> next = scheduler.schedule(this::poll, Math.max(1, delay), TimeUnit.MILLISECONDS);
                    ScheduledFuture<?> prior = scheduled.getAndSet(next);
                    if (prior != null && !prior.isDone()) {
                        prior.cancel(false);
                    }
                }
            });
        }

        private void cancel() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            requestCancellation.cancel();
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            if (cancelRemoteOnTimeoutOrCancellation) {
                cancelRunBestEffort(evaluationId, runId);
            }
            result.completeExceptionally(new RunCancelledException());
        }

        private void timeout() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            requestCancellation.cancel();
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            if (cancelRemoteOnTimeoutOrCancellation) {
                cancelRunBestEffort(evaluationId, runId);
            }
            result.completeExceptionally(
                    new TimeoutException("Foundry evaluation did not complete before the configured timeout."));
        }

        @Override
        public void close() {
            registration.close();
            deadlineTask.cancel(false);
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private URI evalsUri() {
        return URI.create(options.projectEndpoint() + "/openai/v1/evals");
    }

    private URI evalUri(String evaluationId) {
        return URI.create(evalsUri() + "/" + encode(nonBlank(evaluationId, "evaluationId")));
    }

    private URI runsUri(String evaluationId) {
        return URI.create(evalUri(evaluationId) + "/runs");
    }

    private URI runUri(String evaluationId, String runId) {
        return URI.create(runsUri(evaluationId) + "/" + encode(nonBlank(runId, "runId")));
    }

    private void cancelRunBestEffort(String evaluationId, String runId) {
        try {
            sendJsonAsync("POST", runUri(evaluationId, runId), null, new DefaultRunCancellation());
        } catch (RuntimeException ignored) {
            // Logical cancellation has already won; remote cancellation is best effort.
        }
    }

    private static FoundryEvaluation evaluation(StateValue.ObjectValue value) {
        return new FoundryEvaluation(
                string(value, "id", true),
                string(value, "name", false),
                epoch(value, "created_at"),
                stringMap(value.values().get("metadata")));
    }

    private static FoundryEvaluationRun run(StateValue.ObjectValue value, String fallbackEvaluationId) {
        StateValue errorValue = value.values().get("error");
        StateValue.ObjectValue error = errorValue instanceof StateValue.ObjectValue object ? object : null;
        String report = string(value, "report_url", false);
        URI reportUri = report == null ? null : safeReportUri(report);
        return new FoundryEvaluationRun(
                string(value, "id", true),
                OptionalString.of(string(value, "eval_id", false)).orElse(fallbackEvaluationId),
                FoundryEvaluationStatus.fromValue(string(value, "status", true)),
                reportUri,
                error == null ? null : string(error, "code", false),
                error == null ? null : sanitize(string(error, "message", false)),
                epoch(value, "created_at"));
    }

    private static FoundryEvaluationPage<FoundryEvaluationOutputItem> outputPage(
            StateValue.ObjectValue value, String inputCursor) {
        StateValue dataValue = value.values().get("data");
        if (!(dataValue instanceof StateValue.ArrayValue data)) {
            throw protocol("missing_output_data");
        }
        List<FoundryEvaluationOutputItem> items = data.values().stream()
                .map(item -> requireObject(item, "output item"))
                .map(FoundryEvaluationClient::outputItem)
                .toList();
        boolean hasMore = bool(value, "has_more", false);
        String next = hasMore ? string(value, "last_id", false) : null;
        if (hasMore && next == null && !items.isEmpty()) {
            next = items.getLast().id();
        }
        if (hasMore && (next == null || next.equals(inputCursor))) {
            throw protocol("output_cursor_loop");
        }
        return new FoundryEvaluationPage<>(items, next, hasMore);
    }

    private static FoundryEvaluationOutputItem outputItem(StateValue.ObjectValue value) {
        StateValue resultValue = value.values().get("results");
        List<StateValue.ObjectValue> results = resultValue instanceof StateValue.ArrayValue array
                ? array.values().stream()
                        .map(item -> requireObject(item, "evaluator result"))
                        .toList()
                : List.of();
        StateValue sampleValue = value.values().get("sample");
        StateValue.ObjectValue sample = sampleValue instanceof StateValue.ObjectValue object ? object : null;
        return new FoundryEvaluationOutputItem(
                string(value, "id", true), string(value, "status", true), results, sample, epoch(value, "created_at"));
    }

    private static FoundryDataset dataset(DatasetVersion value) {
        return new FoundryDataset(
                value.getId(),
                value.getName(),
                value.getVersion(),
                value.getType() == null ? null : value.getType().toString(),
                value.getDescription(),
                value.getTags());
    }

    private static FoundryProjectResource connection(Connection value) {
        return new FoundryProjectResource(
                "connection",
                value.getId(),
                value.getName(),
                null,
                value.getType() == null ? null : value.getType().toString(),
                null,
                value.getMetadata());
    }

    private static FoundryProjectResource deployment(Deployment value) {
        return new FoundryProjectResource(
                "deployment",
                null,
                value.getName(),
                null,
                value.getType() == null ? null : value.getType().toString(),
                null,
                Map.of());
    }

    private static FoundryProjectResource index(AIProjectIndex value) {
        return new FoundryProjectResource(
                "index",
                value.getId(),
                value.getName(),
                value.getVersion(),
                value.getType() == null ? null : value.getType().toString(),
                value.getDescription(),
                value.getTags());
    }

    private static FoundryEvaluator evaluator(EvaluatorVersion value) {
        return new FoundryEvaluator(
                value.getId(),
                value.getName(),
                value.getVersion(),
                value.getEvaluatorType() == null
                        ? null
                        : value.getEvaluatorType().toString(),
                value.getDisplayName(),
                value.getDescription(),
                value.getMetadata(),
                value.getCreatedAt() == null ? null : value.getCreatedAt().toInstant(),
                true);
    }

    private static TokenCredential tokenCredential(AzureAuthenticationProvider provider) {
        return context -> Mono.fromCompletionStage(provider.getTokenAsync(
                        new AzureTokenRequest(context.getScopes(), context.getTenantId()),
                        new DefaultRunCancellation()))
                .map(token -> new AccessToken(
                        token.token(), java.time.OffsetDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC)));
    }

    private FoundryEvaluationException serviceFailure(
            int status, HttpResponse<byte[]> response, byte[] body, Duration retryAfter) {
        String code = "http_" + status;
        String message = null;
        try {
            StateValue.ObjectValue object = requireObject(parse(body), "error response");
            StateValue errorValue = object.values().get("error");
            if (errorValue instanceof StateValue.ObjectValue error) {
                code = OptionalString.of(string(error, "code", false)).orElse(code);
                message = sanitize(string(error, "message", false));
            }
        } catch (RuntimeException ignored) {
            // Error bodies are never retained when malformed.
        }
        return new FoundryEvaluationException(
                message == null ? "Foundry evaluation request failed with HTTP " + status + "." : message,
                null,
                status == 401 || status == 403
                        ? FoundryEvaluationException.Kind.AUTHENTICATION
                        : FoundryEvaluationException.Kind.SERVICE,
                status,
                response.headers().firstValue("x-request-id").orElse(null),
                code,
                retryAfter);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("retry-after").orElse(null);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long cappedRetryDelayMillis(Duration retryAfter) {
        return retryAfter.compareTo(Duration.ofSeconds(30)) >= 0 ? 30_000L : retryAfter.toMillis();
    }

    private static StateValue parse(byte[] body) {
        if (body.length == 0) {
            return StateValue.object(Map.of());
        }
        return JSON.parse(body);
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw protocol(name.replace(' ', '_') + "_not_object");
        }
        return object;
    }

    private static String string(StateValue.ObjectValue object, String name, boolean required) {
        StateValue value = object.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            if (required) {
                throw protocol("missing_" + name);
            }
            return null;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw protocol("invalid_" + name);
        }
        return string.value();
    }

    private static boolean bool(StateValue.ObjectValue object, String name, boolean fallback) {
        StateValue value = object.values().get(name);
        return value instanceof StateValue.BooleanValue bool ? bool.value() : fallback;
    }

    private static Instant epoch(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (!(value instanceof StateValue.NumberValue number)) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(number.value().longValueExact());
        } catch (ArithmeticException failure) {
            throw protocol("invalid_" + name);
        }
    }

    private static StateValue.ObjectValue stringMap(Map<String, String> values) {
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, StateValue.string(value)));
        return StateValue.object(result);
    }

    private static Map<String, String> stringMap(StateValue value) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        object.values().forEach((key, item) -> {
            if (item instanceof StateValue.StringValue string) {
                result.put(key, string.value());
            }
        });
        return Map.copyOf(result);
    }

    private static URI safeReportUri(String value) {
        URI uri = URI.create(value);
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw protocol("invalid_report_uri");
        }
        return uri;
    }

    private int pageSize(int value) {
        if (value <= 0 || value > options.maxPageSize()) {
            throw new IllegalArgumentException("limit must be between 1 and " + options.maxPageSize() + ".");
        }
        return value;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("FoundryEvaluationClient is closed.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("(?i)(bearer|token|secret|api[-_ ]?key)\\s*[:=]?\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();
        return clean.substring(0, Math.min(clean.length(), 512));
    }

    private static FoundryEvaluationException protocol(String code) {
        return new FoundryEvaluationException(
                "Foundry evaluation protocol mapping failed.",
                null,
                FoundryEvaluationException.Kind.PROTOCOL,
                null,
                null,
                code,
                null);
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

    private static void awaitTermination(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Foundry evaluation " + name + " did not terminate.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Foundry evaluation " + name + " close was interrupted.", failure);
        }
    }

    private record HttpPayload(StateValue.ObjectValue body, String requestId) {}

    private static final class OptionalString {
        private final String value;

        private OptionalString(String value) {
            this.value = value;
        }

        private static OptionalString of(String value) {
            return new OptionalString(value);
        }

        private String orElse(String fallback) {
            return value == null ? fallback : value;
        }
    }
}
