// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.azure.ai.agents.AgentsClientBuilder;
import com.azure.ai.projects.AIProjectClientBuilder;
import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.microsoft.agents.providers.openai.OpenAIResponsesJsonCodec;
import com.microsoft.agents.providers.openai.OpenAITransport;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

final class FoundrySdkTransport implements FoundryTransport {
    private static final ObjectMapper JSON = ObjectMappers.jsonMapper();

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final FoundryChatClientOptions options;

    private final OpenAIClientAsync client;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<StreamingResources> streams = ConcurrentHashMap.newKeySet();

    FoundrySdkTransport(FoundryChatClientOptions options, OpenAIClientAsync client) {
        this.options = options;
        this.client = client;
    }

    static FoundrySdkTransport create(FoundryChatClientOptions options) {
        return create(options, null);
    }

    static FoundrySdkTransport create(FoundryChatClientOptions options, HttpClient httpClient) {
        Objects.requireNonNull(options, "options");
        RetryOptions retries = new RetryOptions(new ExponentialBackoffOptions().setMaxRetries(options.maxRetries()));
        HttpLogOptions logging = new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE);
        OpenAIClientAsync client;
        if (options.surface() == FoundrySurface.MODEL) {
            AIProjectClientBuilder builder = new AIProjectClientBuilder()
                    .endpoint(options.projectEndpoint().toString())
                    .credential(options.tokenCredential())
                    .retryOptions(retries)
                    .httpLogOptions(logging);
            if (httpClient != null) {
                builder.httpClient(httpClient);
            }
            client = builder.buildOpenAIAsyncClient();
        } else {
            AgentsClientBuilder builder = new AgentsClientBuilder()
                    .endpoint(options.projectEndpoint().toString())
                    .credential(options.tokenCredential())
                    .retryOptions(retries)
                    .httpLogOptions(logging);
            if (httpClient != null) {
                builder.httpClient(httpClient);
            }
            client = builder.buildOpenAIAsyncClient();
        }
        client = client.withOptions(clientOptions -> clientOptions.timeout(options.timeout()));
        return new FoundrySdkTransport(options, client);
    }

    @Override
    public CompletionStage<OpenAITransport.Response> completeAsync(
            OpenAITransport.Request request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        CompletableFuture<HttpResponseFor<com.openai.models.responses.Response>> responseFuture;
        try {
            responseFuture = client.responses().withRawResponse().create(params(request));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(FoundryErrorMapper.map(failure));
        }

        CompletableFuture<OpenAITransport.Response> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> responseFuture.cancel(true)));
        responseFuture.whenComplete((rawResponse, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(FoundryErrorMapper.map(failure));
                } else if (rawResponse == null) {
                    result.completeExceptionally(protocol("null_response"));
                } else {
                    try (rawResponse) {
                        String requestId = FoundryProviderException.safeIdentifier(
                                rawResponse.requestId().orElse(null));
                        String json = new String(rawResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                        result.complete(OpenAIResponsesJsonCodec.decodeResponse(json, requestId, request.model()));
                    }
                }
            } catch (IOException | RuntimeException mappingFailure) {
                result.completeExceptionally(FoundryErrorMapper.map(mappingFailure));
            } finally {
                closeRegistration(registration.getAndSet(null));
            }
        });
        return result;
    }

    @Override
    public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
            OpenAITransport.Request request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        SdkStreamingRun run = new SdkStreamingRun(request, cancellation);
        SingleSubscriberPublisher<OpenAITransport.StreamEvent> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancelFromSubscriber,
                options.maxBufferedUpdates(),
                ignored -> FoundryErrorMapper.streamOverflow());
        run.publisher(publisher);
        return publisher;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        streams.forEach(StreamingResources::close);
        streams.clear();
        client.close();
    }

    private ResponseCreateParams params(OpenAITransport.Request request) {
        try {
            LinkedHashMap<String, Object> body =
                    JSON.readValue(OpenAIResponsesJsonCodec.encodeRequest(request), MAP_TYPE);
            if (options.surface() == FoundrySurface.AGENT) {
                body.remove("model");
                LinkedHashMap<String, Object> agentReference = new LinkedHashMap<>();
                agentReference.put("type", "agent_reference");
                agentReference.put("name", options.agentName().orElseThrow());
                options.agentVersion().ifPresent(version -> agentReference.put("version", version));
                body.put("agent_reference", agentReference);
            }
            LinkedHashMap<String, JsonValue> additional = new LinkedHashMap<>();
            body.forEach((key, value) -> additional.put(key, JsonValue.from(value)));
            return ResponseCreateParams.builder()
                    .additionalBodyProperties(additional)
                    .build();
        } catch (JsonProcessingException failure) {
            throw protocol("invalid_request_mapping");
        }
    }

    private final class SdkStreamingRun {
        private final OpenAITransport.Request request;

        private final RunCancellation cancellation;

        private final StreamingResources resources = new StreamingResources();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final AtomicBoolean terminated = new AtomicBoolean();

        private final AtomicReference<String> requestId = new AtomicReference<>();

        private SingleSubscriberPublisher<OpenAITransport.StreamEvent> sink;

        private SdkStreamingRun(OpenAITransport.Request request, RunCancellation cancellation) {
            this.request = request;
            this.cancellation = cancellation;
        }

        private void publisher(SingleSubscriberPublisher<OpenAITransport.StreamEvent> publisher) {
            sink = publisher;
        }

        private void start() {
            if (closed.get()) {
                fail(closedFailure());
                return;
            }
            registration.set(RunCancellations.register(cancellation, this::cancelFromSignal));
            if (terminated.get()) {
                return;
            }
            streams.add(resources);
            try {
                CompletableFuture<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> future =
                        client.responses().withRawResponse().createStreaming(params(request));
                resources.attachFuture(future);
                future.whenComplete(this::rawResponseReady);
            } catch (RuntimeException failure) {
                fail(FoundryErrorMapper.map(failure));
            }
        }

        private void rawResponseReady(
                HttpResponseFor<StreamResponse<ResponseStreamEvent>> rawResponse, Throwable failure) {
            if (rawResponse != null) {
                resources.attachRaw(rawResponse);
            }
            if (terminated.get()) {
                cleanup();
                return;
            }
            if (failure != null) {
                fail(FoundryErrorMapper.map(failure));
                return;
            }
            if (rawResponse == null) {
                fail(protocol("null_streaming_response"));
                return;
            }
            requestId.set(FoundryProviderException.safeIdentifier(
                    rawResponse.requestId().orElse(null)));
            try {
                StreamResponse<ResponseStreamEvent> response = rawResponse.parse();
                if (!resources.attachStream(response)) {
                    return;
                }
                if (cancellation.isCancellationRequested() || closed.get()) {
                    fail(cancellation.isCancellationRequested() ? new RunCancelledException() : closedFailure());
                    return;
                }
                Thread.startVirtualThread(() -> consume(response));
            } catch (RuntimeException mappingFailure) {
                fail(FoundryErrorMapper.map(mappingFailure));
            }
        }

        private void consume(StreamResponse<ResponseStreamEvent> response) {
            try (Stream<ResponseStreamEvent> events = response.stream()) {
                Iterator<ResponseStreamEvent> iterator = events.iterator();
                while (!terminated.get() && iterator.hasNext()) {
                    onNext(iterator.next());
                }
                if (!terminated.get()) {
                    onComplete();
                }
            } catch (RuntimeException failure) {
                if (!terminated.get()) {
                    fail(FoundryErrorMapper.map(failure));
                }
            }
        }

        private void onNext(ResponseStreamEvent event) {
            if (terminated.get()) {
                return;
            }
            try {
                String json = JSON.writeValueAsString(event);
                for (OpenAITransport.StreamEvent mapped :
                        OpenAIResponsesJsonCodec.decodeStreamEvent(json, requestId.get(), request.model())) {
                    sink.emit(mapped);
                }
            } catch (JsonProcessingException | RuntimeException failure) {
                fail(FoundryErrorMapper.map(failure));
            }
        }

        private void onComplete() {
            if (terminated.compareAndSet(false, true)) {
                cleanup();
                sink.complete();
            }
        }

        private void cancelFromSignal() {
            fail(new RunCancelledException());
        }

        private void cancelFromSubscriber() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancellation.cancel();
            cleanup();
        }

        private void fail(RuntimeException failure) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cleanup();
            sink.fail(failure);
        }

        private void cleanup() {
            streams.remove(resources);
            resources.close();
            closeRegistration(registration.getAndSet(null));
        }
    }

    private static FoundryProviderException protocol(String code) {
        return new FoundryProviderException(
                "Microsoft Foundry protocol mapping failed.",
                FoundryProviderException.Kind.PROTOCOL,
                null,
                null,
                null,
                code);
    }

    private static FoundryProviderException closedFailure() {
        return new FoundryProviderException(
                "Microsoft Foundry transport is closed.",
                FoundryProviderException.Kind.TRANSPORT,
                null,
                null,
                null,
                "transport_closed");
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static final class StreamingResources implements AutoCloseable {
        private final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();

        private final AtomicReference<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> raw =
                new AtomicReference<>();

        private final AtomicReference<StreamResponse<ResponseStreamEvent>> stream = new AtomicReference<>();

        private final AtomicBoolean closed = new AtomicBoolean();

        private void attachFuture(CompletableFuture<?> value) {
            Objects.requireNonNull(value, "value");
            if (!future.compareAndSet(null, value)) {
                value.cancel(true);
                throw protocol("duplicate_stream_future");
            }
            if (closed.get()) {
                value.cancel(true);
            }
        }

        private void attachRaw(HttpResponseFor<StreamResponse<ResponseStreamEvent>> value) {
            Objects.requireNonNull(value, "value");
            if (!raw.compareAndSet(null, value)) {
                closeQuietly(value);
                throw protocol("duplicate_raw_stream_response");
            }
            if (closed.get() && raw.compareAndSet(value, null)) {
                closeQuietly(value);
            }
        }

        private boolean attachStream(StreamResponse<ResponseStreamEvent> value) {
            Objects.requireNonNull(value, "value");
            if (!stream.compareAndSet(null, value)) {
                closeQuietly(value);
                throw protocol("duplicate_stream_response");
            }
            if (closed.get() && stream.compareAndSet(value, null)) {
                closeQuietly(value);
                return false;
            }
            return true;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture<?> pending = future.getAndSet(null);
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
            StreamResponse<ResponseStreamEvent> parsed = stream.getAndSet(null);
            if (parsed != null) {
                closeQuietly(parsed);
            }
            HttpResponseFor<StreamResponse<ResponseStreamEvent>> rawResponse = raw.getAndSet(null);
            if (rawResponse != null) {
                closeQuietly(rawResponse);
            }
        }

        private static void closeQuietly(AutoCloseable resource) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Closing is best effort after a terminal signal has already been selected.
            }
        }
    }
}
