// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.core.internal.StructuredOutputSupport;
import com.microsoft.agents.providers.mistral.MistralAuthenticationMode;
import com.microsoft.agents.providers.mistral.MistralChatClient;
import com.microsoft.agents.providers.mistral.MistralChatClientOptions;
import com.microsoft.agents.providers.mistral.MistralProviderException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integrates the process-neutral Foundry Local REST service with {@link ChatClient}.
 *
 * <p>Current Foundry Local documentation exposes OpenAI-compatible Chat Completions, not the
 * Responses API, and publishes no Java native-management SDK. This client therefore reuses the
 * framework's strict Chat Completions adapter, exposes bounded status/model discovery, and never
 * installs, launches, downloads, loads, or unloads native models.
 */
public final class FoundryLocalChatClient implements ChatClient {
    private final FoundryLocalChatClientOptions options;

    private final HttpClient httpClient;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final StrictJsonCodec codec;

    private final MistralChatClient delegate;

    private final AtomicBoolean closed = new AtomicBoolean();

    private FoundryLocalChatClient(
            FoundryLocalChatClientOptions options,
            HttpClient httpClient,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.options = options;
        this.httpClient = httpClient;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        codec = new StrictJsonCodec(
                options.maxRequestBytes(),
                options.maxResponseBytes(),
                options.maxNestingDepth(),
                options.maxStringLength(),
                1_000,
                options.maxCollectionEntries());
        MistralChatClientOptions.Builder delegateOptions = MistralChatClientOptions.builder()
                .model(options.model())
                .endpoint(options.endpoint().resolve("v1/"))
                .allowedHosts(options.allowedHosts())
                .allowInsecureLoopback(options.allowInsecureLoopback())
                .timeout(options.timeout())
                .maxBufferedUpdates(options.maxBufferedUpdates())
                .maxRequestBytes(options.maxRequestBytes())
                .maxResponseBytes(options.maxResponseBytes())
                .maxEventBytes(options.maxEventBytes())
                .maxNestingDepth(options.maxNestingDepth())
                .maxStringLength(options.maxStringLength())
                .maxCollectionEntries(options.maxCollectionEntries())
                .maxConcurrentRequests(options.maxConcurrentRequests());
        if (options.hasBearerToken()) {
            delegateOptions.apiKey(options.bearerToken().value());
        } else {
            delegateOptions.authenticationMode(MistralAuthenticationMode.NONE);
        }
        delegate = MistralChatClient.builder()
                .options(delegateOptions.build())
                .httpClient(httpClient)
                .executor(executor)
                .build();
    }

    /** Creates a client builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns immutable options. */
    public FoundryLocalChatClientOptions options() {
        return options;
    }

    /** Returns the supported process-neutral capability flags. */
    public FoundryLocalCapabilities capabilities() {
        return FoundryLocalCapabilities.current();
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(failure("client_closed", null, null));
        }
        ChatClientRequest mapped;
        try {
            mapped = mapRequest(request);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        delegate.completeAsync(mapped, cancellation).whenComplete((response, delegateFailure) -> {
            if (delegateFailure != null) {
                result.completeExceptionally(mapFailure(delegateFailure));
            } else {
                result.complete(remap(response));
            }
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return failedPublisher(failure("client_closed", null, null));
        }
        ChatClientRequest mapped;
        try {
            mapped = mapRequest(request);
        } catch (RuntimeException exception) {
            return failedPublisher(exception);
        }
        Flow.Publisher<ChatResponseUpdate> source = delegate.completeStreaming(mapped, cancellation);
        return subscriber -> source.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                subscriber.onNext(remap(item));
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(mapFailure(throwable));
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }

    /**
     * Gets the process-neutral service status.
     *
     * @return stage producing service status
     */
    public CompletionStage<FoundryLocalStatus> statusAsync() {
        return statusAsync(new DefaultRunCancellation());
    }

    /**
     * Gets the process-neutral service status.
     *
     * @param cancellation explicit cancellation signal
     * @return stage producing service status
     */
    public CompletionStage<FoundryLocalStatus> statusAsync(RunCancellation cancellation) {
        return get("openai/status", cancellation).thenApply(this::parseStatus);
    }

    /**
     * Lists cached and registered model names.
     *
     * @return stage producing immutable model names
     */
    public CompletionStage<List<String>> cachedModelsAsync() {
        return cachedModelsAsync(new DefaultRunCancellation());
    }

    /**
     * Lists cached and registered model names.
     *
     * @param cancellation explicit cancellation signal
     * @return stage producing immutable model names
     */
    public CompletionStage<List<String>> cachedModelsAsync(RunCancellation cancellation) {
        return get("openai/models", cancellation).thenApply(this::parseCachedModels);
    }

    /**
     * Lists catalog models exposed by the running service.
     *
     * @return stage producing immutable model descriptions
     */
    public CompletionStage<List<FoundryLocalModel>> catalogAsync() {
        return catalogAsync(new DefaultRunCancellation());
    }

    /**
     * Lists catalog models exposed by the running service.
     *
     * @param cancellation explicit cancellation signal
     * @return stage producing immutable model descriptions
     */
    public CompletionStage<List<FoundryLocalModel>> catalogAsync(RunCancellation cancellation) {
        return get("foundry/list", cancellation).thenApply(this::parseCatalog);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        delegate.close();
        if (ownsExecutor) {
            executor.close();
        }
    }

    private CompletionStage<StateValue> get(String relativePath, RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(failure("client_closed", null, null));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        URI uri = options.endpoint().resolve(relativePath);
        if (!Objects.equals(uri.getHost(), options.endpoint().getHost())
                || !Objects.equals(uri.getScheme(), options.endpoint().getScheme())) {
            return CompletableFuture.failedFuture(failure("invalid_endpoint", null, null));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(options.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", "agent-framework-java/foundry-local")
                .GET();
        if (options.hasBearerToken()) {
            request.header("Authorization", "Bearer " + options.bearerToken().value());
        }
        CompletableFuture<StateValue> result = new CompletableFuture<>();
        AtomicReference<InputStream> input = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> call =
                httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            closeQuietly(input.get());
            call.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        });
        call.whenCompleteAsync(
                (response, callFailure) -> {
                    try {
                        if (callFailure != null) {
                            result.completeExceptionally(mapFailure(callFailure));
                            return;
                        }
                        input.set(response.body());
                        byte[] body = readBounded(response.body());
                        String requestId = requestId(response);
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw failure("http_error", response.statusCode(), requestId);
                        }
                        result.complete(codec.parse(body));
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    } finally {
                        closeQuietly(input.getAndSet(null));
                        registration.close();
                    }
                },
                executor);
        return result;
    }

    private FoundryLocalStatus parseStatus(StateValue value) {
        StateValue.ObjectValue root = object(value);
        StateValue.ArrayValue endpointValues = array(root, "Endpoints");
        ArrayList<URI> endpoints = new ArrayList<>();
        for (StateValue endpoint : endpointValues.values()) {
            if (!(endpoint instanceof StateValue.StringValue string)) {
                throw failure("invalid_status", null, null);
            }
            URI uri = URI.create(string.value());
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw failure("invalid_status", null, null);
            }
            endpoints.add(uri);
        }
        return new FoundryLocalStatus(
                endpoints, optionalString(root, "ModelDirPath"), optionalString(root, "PipeName"));
    }

    private List<String> parseCachedModels(StateValue value) {
        if (!(value instanceof StateValue.ArrayValue models)) {
            throw failure("invalid_models", null, null);
        }
        ArrayList<String> result = new ArrayList<>();
        for (StateValue model : models.values()) {
            if (!(model instanceof StateValue.StringValue string)
                    || string.value().isBlank()) {
                throw failure("invalid_models", null, null);
            }
            result.add(string.value());
        }
        return List.copyOf(result);
    }

    private List<FoundryLocalModel> parseCatalog(StateValue value) {
        StateValue.ObjectValue root = object(value);
        StateValue.ArrayValue models = array(root, "models");
        ArrayList<FoundryLocalModel> result = new ArrayList<>();
        for (StateValue modelValue : models.values()) {
            StateValue.ObjectValue model = object(modelValue);
            String name = requiredString(model, "name");
            result.add(new FoundryLocalModel(
                    name,
                    optionalString(model, "alias"),
                    optionalString(model, "displayName"),
                    optionalString(model, "providerType"),
                    optionalString(model, "version"),
                    optionalString(model, "modelType"),
                    optionalString(model, "task"),
                    optionalBoolean(model, "supportsToolCalling"),
                    optionalString(model, "license")));
        }
        return List.copyOf(result);
    }

    private ChatClientRequest mapRequest(ChatClientRequest request) {
        for (Message message : request.messages()) {
            for (Content content : message.contents()) {
                if (!(content instanceof TextContent
                        || content instanceof FunctionCallContent
                        || content instanceof FunctionResultContent)) {
                    throw new ValidationException(
                            "Foundry Local Chat Completions supports text and function content only; received '"
                                    + content.kind()
                                    + "'.");
                }
            }
        }
        ChatOptions source = request.options();
        StructuredOutputOptions structuredOutput =
                StructuredOutputSupport.resolve(source, "foundryLocal.responseSchema");
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(source.metadata());
        metadata.remove("foundryLocal.responseSchema");
        metadata.keySet().stream()
                .filter(key -> key.startsWith("foundryLocal."))
                .findFirst()
                .ifPresent(key -> {
                    throw new ValidationException("Unsupported Foundry Local request option '" + key + "'.");
                });
        ChatOptions.Builder optionsBuilder = ChatOptions.builder().metadata(metadata);
        if (source.model() != null) {
            optionsBuilder.model(source.model());
        }
        if (source.temperature() != null) {
            optionsBuilder.temperature(source.temperature());
        }
        if (source.topP() != null) {
            optionsBuilder.topP(source.topP());
        }
        if (source.maxTokens() != null) {
            optionsBuilder.maxTokens(source.maxTokens());
        }
        optionsBuilder.stop(source.stop());
        if (source.seed() != null) {
            optionsBuilder.seed(source.seed());
        }
        if (source.frequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(source.frequencyPenalty());
        }
        if (source.presencePenalty() != null) {
            optionsBuilder.presencePenalty(source.presencePenalty());
        }
        if (source.toolChoice() != null) {
            optionsBuilder.toolChoice(source.toolChoice());
        }
        if (source.allowMultipleToolCalls() != null) {
            optionsBuilder.allowMultipleToolCalls(source.allowMultipleToolCalls());
        }
        if (source.user() != null) {
            optionsBuilder.user(source.user());
        }
        if (source.store() != null) {
            optionsBuilder.store(source.store());
        }
        if (source.conversationId() != null) {
            optionsBuilder.conversationId(source.conversationId());
        }
        if (source.instructions() != null) {
            optionsBuilder.instructions(source.instructions());
        }
        if (structuredOutput != null) {
            optionsBuilder.structuredOutput(structuredOutput);
        }
        return new ChatClientRequest(
                request.messages(), optionsBuilder.build(), request.tools(), request.toolMode(), request.runContext());
    }

    private static ChatResponse remap(ChatResponse response) {
        return ChatResponse.builder()
                .messages(response.messages())
                .responseId(response.responseId())
                .conversationId(response.conversationId())
                .model(response.model())
                .createdAt(response.createdAt())
                .finishReason(response.finishReason())
                .usage(response.usage())
                .continuationToken(response.continuationToken())
                .metadata(remapMetadata(response.metadata()))
                .updateSequences(response.updateSequences())
                .build();
    }

    private static ChatResponseUpdate remap(ChatResponseUpdate update) {
        ChatResponseUpdate.Builder builder =
                ChatResponseUpdate.builder().contents(update.contents()).metadata(remapMetadata(update.metadata()));
        if (update.sequence() != null) {
            builder.sequence(update.sequence());
        }
        if (update.role() != null) {
            builder.role(update.role());
        }
        if (update.authorName() != null) {
            builder.authorName(update.authorName());
        }
        if (update.responseId() != null) {
            builder.responseId(update.responseId());
        }
        if (update.messageId() != null) {
            builder.messageId(update.messageId());
        }
        if (update.conversationId() != null) {
            builder.conversationId(update.conversationId());
        }
        if (update.model() != null) {
            builder.model(update.model());
        }
        if (update.createdAt() != null) {
            builder.createdAt(update.createdAt());
        }
        if (update.finishReason() != null) {
            builder.finishReason(update.finishReason());
        }
        if (update.usage() != null) {
            builder.usage(update.usage());
        }
        if (update.continuationToken() != null) {
            builder.continuationToken(update.continuationToken());
        }
        return builder.build();
    }

    private static Map<String, StateValue> remapMetadata(Map<String, StateValue> source) {
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                key.startsWith("mistral.") ? "foundryLocal." + key.substring("mistral.".length()) : key, value));
        return Map.copyOf(result);
    }

    private byte[] readBounded(InputStream input) {
        try {
            byte[] body = input.readNBytes(options.maxResponseBytes() + 1);
            if (body.length > options.maxResponseBytes()) {
                throw failure("response_too_large", null, null);
            }
            return body;
        } catch (IOException exception) {
            throw failure("response_io", null, null);
        }
    }

    private static RuntimeException mapFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        if (current instanceof MistralProviderException mistral) {
            return new FoundryLocalProviderException(
                    "chat_" + mistral.kind(), mistral.statusCode(), mistral.requestId());
        }
        if (current instanceof com.microsoft.agents.core.AgentFrameworkException framework) {
            return framework;
        }
        return failure("transport_error", null, null);
    }

    private static StateValue.ObjectValue object(StateValue value) {
        if (!(value instanceof StateValue.ObjectValue object)) {
            throw failure("invalid_response", null, null);
        }
        return object;
    }

    private static StateValue.ArrayValue array(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw failure("invalid_response", null, null);
        }
        return array;
    }

    private static String requiredString(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null || value.isBlank()) {
            throw failure("invalid_response", null, null);
        }
        return value;
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (!(value instanceof StateValue.StringValue string)) {
            throw failure("invalid_response", null, null);
        }
        return string.value();
    }

    private static boolean optionalBoolean(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return false;
        }
        if (!(value instanceof StateValue.BooleanValue bool)) {
            throw failure("invalid_response", null, null);
        }
        return bool.value();
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers()
                .firstValue("x-request-id")
                .or(() -> response.headers().firstValue("request-id"))
                .orElse(null);
    }

    private static FoundryLocalProviderException failure(String kind, Integer status, String requestId) {
        return new FoundryLocalProviderException(kind, status, requestId);
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private static Flow.Publisher<ChatResponseUpdate> failedPublisher(RuntimeException failure) {
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (!done) {
                        done = true;
                        subscriber.onError(failure);
                    }
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        };
    }

    /** Builds immutable {@link FoundryLocalChatClient} instances. */
    public static final class Builder {
        private FoundryLocalChatClientOptions options;

        private HttpClient httpClient;

        private ExecutorService executor;

        private Builder() {}

        /** Sets required immutable options. */
        public Builder options(FoundryLocalChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Injects a caller-owned redirect-free JDK HTTP client. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /** Injects a caller-owned executor. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Creates a configured client. */
        public FoundryLocalChatClient build() {
            FoundryLocalChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (httpClient != null && httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new IllegalArgumentException("Caller-supplied HttpClient must disable redirects.");
            }
            boolean ownsExecutor = executor == null;
            ExecutorService builtExecutor = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : executor;
            HttpClient builtClient = httpClient == null
                    ? HttpClient.newBuilder()
                            .connectTimeout(builtOptions.timeout())
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .executor(builtExecutor)
                            .build()
                    : httpClient;
            return new FoundryLocalChatClient(builtOptions, builtClient, builtExecutor, ownsExecutor);
        }
    }
}
