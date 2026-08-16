// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.SdkProtocolVersion;
import com.github.copilot.generated.rpc.ModelBillingTokenPrices;
import com.github.copilot.generated.rpc.ModelBillingTokenPricesLongContext;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.ModelBilling;
import com.github.copilot.rpc.ModelCapabilities;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.ModelLimits;
import com.github.copilot.rpc.ModelSupports;
import com.github.copilot.rpc.ModelVisionLimits;
import com.github.copilot.rpc.SessionContext;
import com.github.copilot.rpc.SessionLifecycleEvent;
import com.github.copilot.rpc.SessionLifecycleEventMetadata;
import com.github.copilot.rpc.SessionLifecycleEventTypes;
import com.github.copilot.rpc.SessionListFilter;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.TelemetryConfig;
import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.RunHandles;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Provides lifecycle, model, and session operations over the official stable Copilot Java SDK.
 *
 * <p>The upstream SDK and generated types remain internal. The official SDK negotiates protocol
 * compatibility during {@link #startAsync()}; this module does not implement or parse Copilot RPC.
 * Stored CLI sessions remain external process storage rather than Agent Framework session wire
 * format.
 */
public final class GitHubCopilotClient implements AutoCloseable {
    private final GitHubCopilotClientOptions options;

    private volatile CopilotClient delegate;

    private final HardenedExternalCopilotCliLauncher hardenedLauncher;

    private final GitHubCopilotSdkMapper mapper;

    private final Executor executor;

    private final ExecutorService ownedExecutor;

    private final Semaphore concurrency;

    private final Set<GitHubCopilotSession> sessions = ConcurrentHashMap.newKeySet();

    private final AtomicReference<GitHubCopilotClientState> state = new AtomicReference<>(GitHubCopilotClientState.NEW);

    private CompletionStage<Void> startupStage;

    /**
     * Creates a client with fail-closed process configuration.
     *
     * @param options client options
     */
    public GitHubCopilotClient(GitHubCopilotClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        ownedExecutor = options.executor() == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        executor = ownedExecutor == null ? options.executor() : ownedExecutor;
        mapper = new GitHubCopilotSdkMapper(options.limits());
        concurrency = new Semaphore(options.limits().maxConcurrentRequests());
        if (options.externalServer() == null
                && options.cliLaunchMode() == GitHubCopilotCliLaunchMode.HARDENED_EXTERNAL) {
            hardenedLauncher = new HardenedExternalCopilotCliLauncher(options);
            delegate = null;
        } else {
            hardenedLauncher = null;
            delegate = new CopilotClient(sdkOptions(options, options.externalServer(), executor));
        }
    }

    GitHubCopilotClient(GitHubCopilotClientOptions options, CopilotClient delegate, Executor executor) {
        this.options = Objects.requireNonNull(options, "options");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
        ownedExecutor = null;
        hardenedLauncher = null;
        mapper = new GitHubCopilotSdkMapper(options.limits());
        concurrency = new Semaphore(options.limits().maxConcurrentRequests());
    }

    /**
     * Returns immutable client options.
     *
     * @return client options
     */
    public GitHubCopilotClientOptions options() {
        return options;
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return client state
     */
    public GitHubCopilotClientState state() {
        return state.get();
    }

    /**
     * Starts the CLI connection and negotiates protocol compatibility.
     *
     * @return startup stage
     */
    public synchronized CompletionStage<Void> startAsync() {
        GitHubCopilotClientState current = state.get();
        if (current == GitHubCopilotClientState.RUNNING) {
            return CompletableFuture.completedStage(null);
        }
        if (current == GitHubCopilotClientState.STARTING && startupStage != null) {
            return startupStage;
        }
        if (!state.compareAndSet(GitHubCopilotClientState.NEW, GitHubCopilotClientState.STARTING)
                && state.get() != GitHubCopilotClientState.STARTING) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("GitHubCopilotClient cannot start from state " + state.get() + "."));
        }
        CompletableFuture<Void> startup;
        if (delegate == null) {
            startup = CompletableFuture.supplyAsync(hardenedLauncher::start, executor)
                    .thenCompose(server -> {
                        delegate = new CopilotClient(sdkOptions(options, server, executor));
                        return delegate.start();
                    })
                    .orTimeout(options.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } else {
            startup = delegate.start().orTimeout(options.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        startupStage = result.minimalCompletionStage();
        startup.whenComplete((ignored, failure) -> {
            if (failure == null) {
                state.set(GitHubCopilotClientState.RUNNING);
                result.complete(null);
            } else {
                state.set(GitHubCopilotClientState.FAILED);
                CopilotClient currentDelegate = delegate;
                if (currentDelegate != null) {
                    currentDelegate.forceStop();
                }
                if (hardenedLauncher != null) {
                    hardenedLauncher.close();
                }
                result.completeExceptionally(normalize(failure, "startup"));
            }
        });
        return startupStage;
    }

    /**
     * Stops sessions and the owned CLI process deterministically.
     *
     * @return shutdown stage
     */
    public CompletionStage<Void> stopAsync() {
        GitHubCopilotClientState current = state.get();
        if (current == GitHubCopilotClientState.STOPPED || current == GitHubCopilotClientState.NEW) {
            state.set(GitHubCopilotClientState.STOPPED);
            closeOwnedExecutor();
            return CompletableFuture.completedStage(null);
        }
        state.set(GitHubCopilotClientState.STOPPING);
        sessions.forEach(GitHubCopilotSession::close);
        sessions.clear();
        CopilotClient currentDelegate = delegate;
        CompletableFuture<Void> stop = currentDelegate == null
                ? CompletableFuture.completedFuture(null)
                : currentDelegate.stop().orTimeout(options.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Void> result = new CompletableFuture<>();
        stop.whenComplete((ignored, failure) -> {
            if (failure != null && currentDelegate != null) {
                try {
                    currentDelegate.forceStop().get(options.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception forceFailure) {
                    failure.addSuppressed(forceFailure);
                }
            }
            if (hardenedLauncher != null) {
                hardenedLauncher.close();
            }
            state.set(GitHubCopilotClientState.STOPPED);
            closeOwnedExecutor();
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(normalize(failure, "shutdown"));
            }
        });
        return result.minimalCompletionStage();
    }

    /**
     * Creates a new externally persisted Copilot session.
     *
     * @param config session configuration
     * @return created session
     */
    public CompletionStage<GitHubCopilotSession> createSessionAsync(GitHubCopilotSessionConfig config) {
        Objects.requireNonNull(config, "config");
        return request(() -> delegate().createSession(mapper.sessionConfig(config)))
                .thenApply(session -> register(new GitHubCopilotSession(
                        session,
                        config,
                        mapper,
                        options.requestTimeout(),
                        options.limits(),
                        executor,
                        sessions::remove)));
    }

    /**
     * Resumes an external Copilot session by identity.
     *
     * @param sessionId external session identity
     * @param config current handlers and session configuration
     * @return resumed session
     */
    public CompletionStage<GitHubCopilotSession> resumeSessionAsync(
            String sessionId, GitHubCopilotSessionConfig config) {
        String id = requireNonBlank(sessionId, "sessionId");
        Objects.requireNonNull(config, "config");
        return request(() -> delegate().resumeSession(id, mapper.resumeConfig(config)))
                .thenApply(session -> register(new GitHubCopilotSession(
                        session,
                        config,
                        mapper,
                        options.requestTimeout(),
                        options.limits(),
                        executor,
                        sessions::remove)));
    }

    /**
     * Lists external CLI session metadata.
     *
     * @return immutable metadata list
     */
    public CompletionStage<List<GitHubCopilotSessionMetadata>> listSessionsAsync() {
        return request(() -> delegate().listSessions())
                .thenApply(values -> values.stream()
                        .map(GitHubCopilotClient::sessionMetadata)
                        .toList());
    }

    /**
     * Lists external CLI sessions using the official SDK exact-match filter.
     *
     * @param filter session filter
     * @return immutable metadata list
     */
    public CompletionStage<List<GitHubCopilotSessionMetadata>> listSessionsAsync(GitHubCopilotSessionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return request(() -> delegate().listSessions(sessionFilter(filter)))
                .thenApply(values -> values.stream()
                        .map(GitHubCopilotClient::sessionMetadata)
                        .toList());
    }

    /**
     * Retrieves one session's metadata using the official SDK O(1) lookup.
     *
     * @param sessionId external session identity
     * @return metadata, or {@code null} when absent
     */
    public CompletionStage<GitHubCopilotSessionMetadata> getSessionMetadataAsync(String sessionId) {
        String id = requireNonBlank(sessionId, "sessionId");
        return request(() -> delegate().getSessionMetadata(id))
                .thenApply(metadata -> metadata == null ? null : sessionMetadata(metadata));
    }

    /**
     * Subscribes to all official SDK client-level session lifecycle events.
     *
     * @param handler lifecycle handler
     * @return subscription handle
     */
    public AutoCloseable onSessionLifecycle(Consumer<GitHubCopilotSessionLifecycleEvent> handler) {
        Consumer<GitHubCopilotSessionLifecycleEvent> safe = Objects.requireNonNull(handler, "handler");
        return delegate().onLifecycle(event -> safe.accept(sessionLifecycleEvent(event)));
    }

    /**
     * Subscribes to one official SDK client-level lifecycle event type.
     *
     * @param type event type
     * @param handler lifecycle handler
     * @return subscription handle
     */
    public AutoCloseable onSessionLifecycle(
            GitHubCopilotSessionLifecycleEventType type, Consumer<GitHubCopilotSessionLifecycleEvent> handler) {
        GitHubCopilotSessionLifecycleEventType eventType = Objects.requireNonNull(type, "type");
        if (eventType == GitHubCopilotSessionLifecycleEventType.OTHER) {
            throw new IllegalArgumentException("OTHER cannot be used as an official SDK lifecycle filter.");
        }
        Consumer<GitHubCopilotSessionLifecycleEvent> safe = Objects.requireNonNull(handler, "handler");
        return delegate().onLifecycle(lifecycleSdkValue(eventType), event -> safe.accept(sessionLifecycleEvent(event)));
    }

    /**
     * Returns the protocol version reported by the pinned official SDK.
     *
     * <p>This value is diagnostic only. Compatibility is determined by
     * {@link #startAsync()} through the official SDK/client startup handshake.
     *
     * @return official SDK protocol version
     */
    public int sdkProtocolVersion() {
        return SdkProtocolVersion.get();
    }

    /**
     * Deletes one external CLI session.
     *
     * @param sessionId external session identity
     * @return deletion stage
     */
    public CompletionStage<Void> deleteSessionAsync(String sessionId) {
        String id = requireNonBlank(sessionId, "sessionId");
        return request(() -> delegate().deleteSession(id));
    }

    /**
     * Lists models reported by the connected CLI.
     *
     * @return immutable model list
     */
    public CompletionStage<List<GitHubCopilotModel>> listModelsAsync() {
        return request(() -> delegate().listModels())
                .thenApply(values ->
                        values.stream().map(GitHubCopilotClient::model).toList());
    }

    /**
     * Stops the client and owned resources.
     */
    @Override
    public void close() {
        try {
            stopAsync().toCompletableFuture().get(options.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            CopilotClient currentDelegate = delegate;
            if (currentDelegate != null) {
                currentDelegate.forceStop();
            }
            if (hardenedLauncher != null) {
                hardenedLauncher.close();
            }
            throw new GitHubCopilotProviderException(
                    "GitHub Copilot client close was interrupted.", exception, "shutdown", "interrupted");
        } catch (Exception exception) {
            CopilotClient currentDelegate = delegate;
            if (currentDelegate != null) {
                currentDelegate.forceStop();
            }
            if (hardenedLauncher != null) {
                hardenedLauncher.close();
            }
            throw normalize(exception, "shutdown");
        }
    }

    static RuntimeException normalize(Throwable failure, String kind) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof AgentFrameworkException framework) {
            return framework;
        }
        if (cause instanceof CompletionException completion && completion.getCause() != null) {
            cause = completion.getCause();
        }
        return new GitHubCopilotProviderException(
                "GitHub Copilot " + kind + " failed.", cause, kind, protocolCode(cause));
    }

    private <T> CompletionStage<T> request(Supplier<CompletableFuture<T>> operation) {
        return startAsync().thenCompose(ignored -> {
            if (!concurrency.tryAcquire()) {
                return CompletableFuture.failedFuture(new GitHubCopilotProviderException(
                        "GitHub Copilot concurrent-request limit exceeded.", null, "concurrency", "limit_exceeded"));
            }
            CompletableFuture<T> stage;
            try {
                stage = Objects.requireNonNull(operation.get(), "Copilot operation returned null.");
            } catch (RuntimeException failure) {
                concurrency.release();
                return CompletableFuture.failedFuture(normalize(failure, "request"));
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            stage.orTimeout(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((value, failure) -> {
                        concurrency.release();
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(normalize(failure, "request"));
                        }
                    });
            return result;
        });
    }

    private GitHubCopilotSession register(GitHubCopilotSession session) {
        sessions.add(session);
        return session;
    }

    private void closeOwnedExecutor() {
        if (ownedExecutor == null || ownedExecutor.isShutdown()) {
            return;
        }
        ownedExecutor.shutdown();
        try {
            if (!ownedExecutor.awaitTermination(options.closeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                ownedExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ownedExecutor.shutdownNow();
        }
    }

    static CopilotClientOptions sdkOptions(
            GitHubCopilotClientOptions source, GitHubCopilotExternalServer server, Executor executor) {
        CopilotClientOptions target = new CopilotClientOptions()
                .setAutoStart(false)
                .setExecutor(executor)
                .setLogLevel("error")
                .setMode(
                        source.clientMode() == GitHubCopilotClientMode.EMPTY
                                ? CopilotClientMode.EMPTY
                                : CopilotClientMode.COPILOT_CLI);
        if (source.copilotHome() != null) {
            target.setCopilotHome(source.copilotHome().toString());
        }
        if (server != null) {
            return target.setCliUrl("http://" + server.authorityHost() + ":" + server.port())
                    .setTcpConnectionToken(server.connectionToken());
        }
        target.setUseStdio(true)
                .setCwd(source.workingDirectory().toString())
                .setEnvironment(source.environment())
                .setUseLoggedInUser(source.useLoggedInUser())
                .setSessionIdleTimeoutSeconds(
                        Math.toIntExact(source.idleTimeout().toSeconds()));
        if (source.cliExecutable() != null) {
            target.setCliPath(source.cliExecutable().toString());
        }
        if (source.credential() != null) {
            target.setGitHubToken(source.credential().reveal());
        }
        if (source.telemetry() != null) {
            target.setTelemetry(telemetry(source.telemetry()));
        }
        return target;
    }

    private CopilotClient delegate() {
        CopilotClient current = delegate;
        if (current == null) {
            throw new IllegalStateException("GitHubCopilotClient is not started.");
        }
        return current;
    }

    static GitHubCopilotModel model(ModelInfo source) {
        Objects.requireNonNull(source, "source");
        ModelCapabilities capabilities = source.getCapabilities();
        ModelSupports supports = capabilities == null ? null : capabilities.getSupports();
        ModelLimits limits = capabilities == null ? null : capabilities.getLimits();
        return new GitHubCopilotModel(
                source.getId(),
                source.getName() == null || source.getName().isBlank() ? source.getId() : source.getName(),
                supports != null && supports.isVision(),
                supports != null && supports.isReasoningEffort(),
                limits == null ? null : limits.getMaxPromptTokens(),
                limits == null ? 0 : limits.getMaxContextWindowTokens(),
                limits == null ? null : visionLimits(limits.getVision()),
                modelBilling(source.getBilling()),
                source.getSupportedReasoningEfforts() == null ? List.of() : source.getSupportedReasoningEfforts(),
                source.getDefaultReasoningEffort());
    }

    private static GitHubCopilotSessionMetadata sessionMetadata(SessionMetadata source) {
        SessionContext context = source.getContext();
        return new GitHubCopilotSessionMetadata(
                source.getSessionId(),
                instant(source.getStartTime()),
                instant(source.getModifiedTime()),
                source.getSummary(),
                context == null ? null : context.getCwd(),
                context == null ? null : context.getGitRoot(),
                context == null ? null : context.getRepository(),
                context == null ? null : context.getBranch());
    }

    private static SessionListFilter sessionFilter(GitHubCopilotSessionFilter source) {
        SessionListFilter target = new SessionListFilter();
        if (source.workingDirectory() != null) {
            target.setCwd(source.workingDirectory());
        }
        if (source.gitRoot() != null) {
            target.setGitRoot(source.gitRoot());
        }
        if (source.repository() != null) {
            target.setRepository(source.repository());
        }
        if (source.branch() != null) {
            target.setBranch(source.branch());
        }
        return target;
    }

    private static GitHubCopilotSessionLifecycleEvent sessionLifecycleEvent(SessionLifecycleEvent source) {
        SessionLifecycleEventMetadata metadata = source.getMetadata();
        return new GitHubCopilotSessionLifecycleEvent(
                lifecycleType(source.getType()),
                requireNonBlank(source.getType(), "lifecycle event type"),
                requireNonBlank(source.getSessionId(), "lifecycle sessionId"),
                metadata == null ? null : instant(metadata.startTime()),
                metadata == null ? null : instant(metadata.modifiedTime()),
                metadata == null ? null : metadata.summary());
    }

    private static GitHubCopilotSessionLifecycleEventType lifecycleType(String source) {
        if (SessionLifecycleEventTypes.CREATED.equals(source)) {
            return GitHubCopilotSessionLifecycleEventType.CREATED;
        }
        if (SessionLifecycleEventTypes.DELETED.equals(source)) {
            return GitHubCopilotSessionLifecycleEventType.DELETED;
        }
        if (SessionLifecycleEventTypes.UPDATED.equals(source)) {
            return GitHubCopilotSessionLifecycleEventType.UPDATED;
        }
        if (SessionLifecycleEventTypes.FOREGROUND.equals(source)) {
            return GitHubCopilotSessionLifecycleEventType.FOREGROUND;
        }
        if (SessionLifecycleEventTypes.BACKGROUND.equals(source)) {
            return GitHubCopilotSessionLifecycleEventType.BACKGROUND;
        }
        return GitHubCopilotSessionLifecycleEventType.OTHER;
    }

    private static String lifecycleSdkValue(GitHubCopilotSessionLifecycleEventType source) {
        return switch (source) {
            case CREATED -> SessionLifecycleEventTypes.CREATED;
            case DELETED -> SessionLifecycleEventTypes.DELETED;
            case UPDATED -> SessionLifecycleEventTypes.UPDATED;
            case FOREGROUND -> SessionLifecycleEventTypes.FOREGROUND;
            case BACKGROUND -> SessionLifecycleEventTypes.BACKGROUND;
            case OTHER -> throw new IllegalArgumentException("OTHER has no official SDK filter value.");
        };
    }

    private static GitHubCopilotModelVisionLimits visionLimits(ModelVisionLimits source) {
        return source == null
                ? null
                : new GitHubCopilotModelVisionLimits(
                        source.getSupportedMediaTypes(), source.getMaxPromptImages(), source.getMaxPromptImageSize());
    }

    private static GitHubCopilotModelBilling modelBilling(ModelBilling source) {
        if (source == null) {
            return null;
        }
        Double multiplier = source.getMultiplierOpt().isPresent()
                ? source.getMultiplierOpt().getAsDouble()
                : null;
        return new GitHubCopilotModelBilling(multiplier, tokenPrices(source.getTokenPrices()));
    }

    private static GitHubCopilotModelBilling.TokenPrices tokenPrices(ModelBillingTokenPrices source) {
        if (source == null) {
            return null;
        }
        return new GitHubCopilotModelBilling.TokenPrices(
                source.inputPrice(),
                source.outputPrice(),
                source.cachePrice(),
                source.cacheReadPrice(),
                source.cacheWritePrice(),
                source.batchSize(),
                source.contextMax(),
                source.maxPromptTokens(),
                longContextPrices(source.longContext()));
    }

    private static GitHubCopilotModelBilling.LongContextPrices longContextPrices(
            ModelBillingTokenPricesLongContext source) {
        if (source == null) {
            return null;
        }
        return new GitHubCopilotModelBilling.LongContextPrices(
                source.inputPrice(),
                source.outputPrice(),
                source.cachePrice(),
                source.cacheReadPrice(),
                source.cacheWritePrice(),
                source.contextMax(),
                source.maxPromptTokens());
    }

    private static TelemetryConfig telemetry(GitHubCopilotTelemetryConfig source) {
        TelemetryConfig target = new TelemetryConfig();
        if (source.otlpEndpoint() != null) {
            target.setOtlpEndpoint(source.otlpEndpoint().toString());
        }
        if (source.otlpProtocol() != null) {
            target.setOtlpProtocol(source.otlpProtocol());
        }
        if (source.filePath() != null) {
            target.setFilePath(source.filePath().toString());
        }
        if (source.exporterType() != null) {
            target.setExporterType(source.exporterType());
        }
        if (source.sourceName() != null) {
            target.setSourceName(source.sourceName());
        }
        if (source.captureContent() != null) {
            target.setCaptureContent(source.captureContent());
        }
        return target;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String protocolCode(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        if (message != null && message.contains("protocol version mismatch")) {
            return "protocol_version_mismatch";
        }
        return null;
    }
}
