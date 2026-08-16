// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.async.ResponseServiceAsync;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

final class OpenAISdkTransport implements OpenAITransport {
    private final OpenAIClientAsync client;

    private final int maxBufferedUpdates;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<StreamingResources> streams = ConcurrentHashMap.newKeySet();

    OpenAISdkTransport(OpenAIClientAsync client, int maxBufferedUpdates) {
        this.client = client;
        this.maxBufferedUpdates = maxBufferedUpdates;
    }

    static OpenAISdkTransport create(OpenAIChatClientOptions options) {
        OpenAIOkHttpClientAsync.Builder builder = OpenAIOkHttpClientAsync.builder();
        if (options.hasApiKey()) {
            builder.apiKey(options.apiKey().reveal());
        } else {
            builder.fromEnv();
        }
        options.baseUrl().ifPresent(value -> builder.baseUrl(value.toString()));
        options.organization().ifPresent(builder::organization);
        options.project().ifPresent(builder::project);
        options.timeout().ifPresent(builder::timeout);
        builder.maxRetries(options.maxRetries());
        return new OpenAISdkTransport(builder.build(), options.maxBufferedUpdates());
    }

    @Override
    public CompletionStage<OpenAITransport.Response> completeAsync(
            OpenAITransport.Request request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OpenAISdkException("transport_closed"));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        CompletableFuture<HttpResponseFor<com.openai.models.responses.Response>> responseFuture;
        try {
            ResponseCreateParams params = OpenAISdkRequestMapper.map(request);
            responseFuture = client.responses().withRawResponse().create(params);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(mapFailure(failure));
        }

        CompletableFuture<OpenAITransport.Response> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> responseFuture.cancel(true)));
        responseFuture.whenComplete((rawResponse, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(mapFailure(failure));
                } else if (rawResponse == null) {
                    result.completeExceptionally(new OpenAISdkException("null_response"));
                } else {
                    try (rawResponse) {
                        result.complete(OpenAISdkResponseMapper.map(
                                rawResponse.parse(),
                                OpenAIProviderException.safeIdentifier(
                                        rawResponse.requestId().orElse(null)),
                                request.responseOptions().imageOutputFormat()));
                    }
                }
            } catch (RuntimeException mappingFailure) {
                result.completeExceptionally(mapFailure(mappingFailure));
            } finally {
                closeRegistration(registration.get());
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
                maxBufferedUpdates,
                SingleSubscriberPublisher.UpdateMode.BUFFERED,
                OpenAIStreamingBufferOverflowException::new,
                run::cancelFromSubscriber);
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

    private static RuntimeException mapFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtime) {
            return OpenAIErrorMapper.map(runtime);
        }
        return new OpenAISdkException("sdk_error");
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private final class SdkStreamingRun {
        private final OpenAITransport.Request request;

        private final RunCancellation cancellation;

        private final AtomicReference<OpenAISdkResponseMapper.StreamEventMapper> mapper = new AtomicReference<>();

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
                fail(new OpenAISdkException("transport_closed"));
                return;
            }
            registration.set(RunCancellations.register(cancellation, this::cancelFromSignal));
            if (terminated.get()) {
                return;
            }
            streams.add(resources);
            try {
                ResponseServiceAsync.WithRawResponse rawService =
                        client.responses().withRawResponse();
                CompletableFuture<HttpResponseFor<StreamResponse<ResponseStreamEvent>>> responseFuture =
                        rawService.createStreaming(OpenAISdkRequestMapper.map(request));
                resources.attachFuture(responseFuture);
                responseFuture.whenComplete(this::rawResponseReady);
            } catch (RuntimeException failure) {
                fail(mapFailure(failure));
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
                fail(mapFailure(failure));
                return;
            }
            if (rawResponse == null) {
                fail(new OpenAISdkException("null_streaming_response"));
                return;
            }
            String safeRequestId = OpenAIProviderException.safeIdentifier(
                    rawResponse.requestId().orElse(null));
            requestId.set(safeRequestId);
            try {
                StreamResponse<ResponseStreamEvent> response = rawResponse.parse();
                if (!resources.attachStream(response)) {
                    return;
                }
                mapper.set(new OpenAISdkResponseMapper.StreamEventMapper(
                        safeRequestId, request.responseOptions().imageOutputFormat()));
                if (cancellation.isCancellationRequested() || closed.get()) {
                    fail(
                            cancellation.isCancellationRequested()
                                    ? new RunCancelledException()
                                    : new OpenAISdkException("transport_closed"));
                    return;
                }
                Thread.startVirtualThread(() -> consume(response));
            } catch (RuntimeException mappingFailure) {
                fail(mapStreamingFailure(mappingFailure));
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
                    fail(mapStreamingFailure(failure));
                }
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

        private void onNext(ResponseStreamEvent event) {
            if (terminated.get()) {
                return;
            }
            try {
                OpenAISdkResponseMapper.StreamEventMapper eventMapper =
                        Objects.requireNonNull(mapper.get(), "stream event mapper");
                for (OpenAITransport.StreamEvent mapped : eventMapper.map(event)) {
                    sink.emit(mapped);
                }
            } catch (RuntimeException failure) {
                fail(mapStreamingFailure(failure));
            }
        }

        private void onComplete() {
            OpenAISdkResponseMapper.StreamEventMapper eventMapper = mapper.get();
            if (eventMapper == null || !eventMapper.terminal()) {
                fail(new OpenAIProtocolException(
                        "OpenAI SDK stream closed without a terminal response.",
                        requestId.get(),
                        "missing_terminal_event"));
                return;
            }
            if (terminated.compareAndSet(false, true)) {
                cleanup();
                sink.complete();
            }
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

        private RuntimeException mapStreamingFailure(Throwable failure) {
            RuntimeException mapped = mapFailure(failure);
            String safeRequestId = requestId.get();
            if (safeRequestId == null
                    || mapped instanceof RunCancelledException
                    || mapped instanceof OpenAIProviderException provider
                            && provider.requestId().isPresent()) {
                return mapped;
            }
            Integer status = mapped instanceof OpenAIProviderException provider
                            && provider.statusCode().isPresent()
                    ? provider.statusCode().getAsInt()
                    : null;
            String errorCode = mapped instanceof OpenAIProviderException provider
                    ? provider.errorCode().orElse(null)
                    : "stream_error";
            return new OpenAIProviderException(
                    "OpenAI streaming request failed (request " + safeRequestId + ").",
                    status,
                    safeRequestId,
                    errorCode);
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
                throw new OpenAISdkException("duplicate_stream_future");
            }
            if (closed.get()) {
                value.cancel(true);
            }
        }

        private void attachRaw(HttpResponseFor<StreamResponse<ResponseStreamEvent>> value) {
            Objects.requireNonNull(value, "value");
            if (!raw.compareAndSet(null, value)) {
                closeQuietly(value);
                throw new OpenAISdkException("duplicate_raw_stream_response");
            }
            if (closed.get() && raw.compareAndSet(value, null)) {
                closeQuietly(value);
            }
        }

        private boolean attachStream(StreamResponse<ResponseStreamEvent> value) {
            Objects.requireNonNull(value, "value");
            if (!stream.compareAndSet(null, value)) {
                closeQuietly(value);
                throw new OpenAISdkException("duplicate_stream_response");
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
                // Closing is best effort after the terminal signal has already been selected.
            }
        }
    }
}
