// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.azure.ai.openai.responses.AzureResponsesServiceVersion;
import com.azure.ai.openai.responses.ResponsesAsyncClient;
import com.azure.ai.openai.responses.ResponsesClientBuilder;
import com.azure.ai.openai.responses.models.CreateResponsesRequest;
import com.azure.ai.openai.responses.models.ResponsesFunctionCallItem;
import com.azure.ai.openai.responses.models.ResponsesItem;
import com.azure.ai.openai.responses.models.ResponsesResponse;
import com.azure.ai.openai.responses.models.ResponsesStreamEvent;
import com.azure.ai.openai.responses.models.ResponsesStreamEventCompleted;
import com.azure.ai.openai.responses.models.ResponsesStreamEventCreated;
import com.azure.ai.openai.responses.models.ResponsesStreamEventFailed;
import com.azure.ai.openai.responses.models.ResponsesStreamEventInProgress;
import com.azure.ai.openai.responses.models.ResponsesStreamEventIncomplete;
import com.azure.ai.openai.responses.models.ResponsesStreamEventOutputItemAdded;
import com.azure.ai.openai.responses.models.ResponsesStreamEventOutputItemDone;
import com.azure.core.credential.KeyCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.util.BinaryData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.providers.openai.OpenAIResponsesJsonCodec;
import com.microsoft.agents.providers.openai.OpenAITransport;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class AzureOpenAISdkTransport implements AzureOpenAITransport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AzureOpenAIChatClientOptions options;

    private final ResponsesAsyncClient client;

    private final AtomicBoolean closed = new AtomicBoolean();

    private AzureOpenAISdkTransport(AzureOpenAIChatClientOptions options, ResponsesAsyncClient client) {
        this.options = options;
        this.client = client;
    }

    static AzureOpenAISdkTransport create(AzureOpenAIChatClientOptions options) {
        return create(options, null);
    }

    static AzureOpenAISdkTransport create(AzureOpenAIChatClientOptions options, HttpClient httpClient) {
        Objects.requireNonNull(options, "options");
        ResponsesClientBuilder builder = new ResponsesClientBuilder()
                .endpoint(options.endpoint().toString())
                .serviceVersion(serviceVersion(options.apiVersion()))
                .retryOptions(new RetryOptions(new ExponentialBackoffOptions().setMaxRetries(options.maxRetries())))
                .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE));
        if (httpClient != null) {
            builder.httpClient(httpClient);
        }
        if (options.authenticationMode() == AzureOpenAIAuthenticationMode.API_KEY) {
            builder.credential(new KeyCredential(options.apiKey()));
        } else {
            builder.credential(options.tokenCredential());
        }
        return new AzureOpenAISdkTransport(options, builder.buildAsyncClient());
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

        CompletableFuture<com.azure.ai.openai.responses.models.ResponsesResponse> sdkFuture;
        try {
            CreateResponsesRequest sdkRequest = sdkRequest(request);
            Mono<com.azure.ai.openai.responses.models.ResponsesResponse> response =
                    client.createResponse(sdkRequest).timeout(options.timeout());
            sdkFuture = response.toFuture();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(AzureOpenAIErrorMapper.map(failure));
        }

        CompletableFuture<OpenAITransport.Response> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> sdkFuture.cancel(true)));
        sdkFuture.whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(AzureOpenAIErrorMapper.map(failure));
                } else if (response == null) {
                    result.completeExceptionally(protocol("null_response"));
                } else {
                    String json = responseJson(response);
                    result.complete(OpenAIResponsesJsonCodec.decodeResponse(json, null, request.model()));
                }
            } catch (RuntimeException mappingFailure) {
                result.completeExceptionally(AzureOpenAIErrorMapper.map(mappingFailure));
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
        if (closed.get()) {
            return failedPublisher(closedFailure());
        }
        if (cancellation.isCancellationRequested()) {
            return failedPublisher(new RunCancelledException());
        }

        try {
            CreateResponsesRequest sdkRequest = sdkRequest(request);
            Flux<OpenAITransport.StreamEvent> events = client.createResponseStream(sdkRequest)
                    .timeout(options.timeout())
                    .concatMapIterable(event -> {
                        String json = eventJson(event);
                        return OpenAIResponsesJsonCodec.decodeStreamEvent(json, null, request.model());
                    })
                    .onErrorMap(AzureOpenAIErrorMapper::map);
            return flowPublisher(events, cancellation);
        } catch (RuntimeException failure) {
            return failedPublisher(AzureOpenAIErrorMapper.map(failure));
        }
    }

    @Override
    public void close() {
        // ResponsesAsyncClient is not AutoCloseable and may share its Azure pipeline/HTTP transport.
        // Closing this adapter therefore prevents new work; active Reactor subscriptions own cleanup.
        closed.set(true);
    }

    private CreateResponsesRequest sdkRequest(OpenAITransport.Request request) {
        AzureOpenAIRequestValidation.validate(request);
        String json = OpenAIResponsesJsonCodec.encodeRequest(request);
        try {
            return BinaryData.fromString(json).toObject(CreateResponsesRequest.class);
        } catch (RuntimeException failure) {
            throw protocol("invalid_request_mapping");
        }
    }

    private static String responseJson(ResponsesResponse response) {
        try {
            ObjectNode root =
                    object(JSON.readTree(BinaryData.fromObject(response).toString()));
            JsonNode outputNode = root.get("output");
            if (outputNode instanceof ArrayNode output && response.getOutput() != null) {
                int count = Math.min(output.size(), response.getOutput().size());
                for (int index = 0; index < count; index++) {
                    ResponsesItem source = response.getOutput().get(index);
                    JsonNode target = output.get(index);
                    if (target instanceof ObjectNode item) {
                        if (source.getId() != null) {
                            item.put("id", source.getId());
                        }
                        if (source instanceof ResponsesFunctionCallItem functionCall) {
                            item.put("call_id", functionCall.getCallId());
                            item.put("name", functionCall.getName());
                            item.put("arguments", functionCall.getArguments());
                        }
                    }
                }
            }
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw protocol("invalid_response_json");
        }
    }

    static String eventJson(ResponsesStreamEvent event) {
        try {
            ObjectNode root = object(JSON.readTree(BinaryData.fromObject(event).toString()));
            ResponsesResponse response =
                    switch (event) {
                        case ResponsesStreamEventCreated created -> created.getResponse();
                        case ResponsesStreamEventInProgress inProgress -> inProgress.getResponse();
                        case ResponsesStreamEventCompleted completed -> completed.getResponse();
                        case ResponsesStreamEventIncomplete incomplete -> incomplete.getResponse();
                        case ResponsesStreamEventFailed failed -> failed.getResponse();
                        default -> null;
                    };
            if (response != null) {
                root.set("response", JSON.readTree(responseJson(response)));
            }
            ResponsesItem item =
                    switch (event) {
                        case ResponsesStreamEventOutputItemAdded added -> added.getItem();
                        case ResponsesStreamEventOutputItemDone done -> done.getItem();
                        default -> null;
                    };
            if (item != null && item.getId() != null && root.get("item") instanceof ObjectNode itemNode) {
                itemNode.put("id", item.getId());
            }
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw protocol("invalid_stream_json");
        }
    }

    private static ObjectNode object(JsonNode node) {
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw protocol("invalid_json");
    }

    private static Flow.Publisher<OpenAITransport.StreamEvent> flowPublisher(
            Flux<OpenAITransport.StreamEvent> flux, RunCancellation cancellation) {
        return downstream -> {
            Objects.requireNonNull(downstream, "downstream");
            flux.subscribe(new Subscriber<>() {
                private final AtomicBoolean terminated = new AtomicBoolean();

                private final AtomicReference<Subscription> upstream = new AtomicReference<>();

                private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

                @Override
                public void onSubscribe(Subscription subscription) {
                    if (!upstream.compareAndSet(null, subscription)) {
                        subscription.cancel();
                        return;
                    }
                    registration.set(RunCancellations.register(cancellation, subscription::cancel));
                    downstream.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long count) {
                            subscription.request(count);
                        }

                        @Override
                        public void cancel() {
                            if (terminated.compareAndSet(false, true)) {
                                cancellation.cancel();
                                subscription.cancel();
                                closeRegistration(registration.getAndSet(null));
                            }
                        }
                    });
                    if (cancellation.isCancellationRequested()) {
                        subscription.cancel();
                        fail(new RunCancelledException());
                    }
                }

                @Override
                public void onNext(OpenAITransport.StreamEvent event) {
                    if (!terminated.get()) {
                        downstream.onNext(event);
                    }
                }

                @Override
                public void onError(Throwable failure) {
                    fail(AzureOpenAIErrorMapper.map(failure));
                }

                @Override
                public void onComplete() {
                    if (terminated.compareAndSet(false, true)) {
                        closeRegistration(registration.getAndSet(null));
                        downstream.onComplete();
                    }
                }

                private void fail(RuntimeException failure) {
                    if (terminated.compareAndSet(false, true)) {
                        Subscription subscription = upstream.get();
                        if (subscription != null) {
                            subscription.cancel();
                        }
                        closeRegistration(registration.getAndSet(null));
                        downstream.onError(failure);
                    }
                }
            });
        };
    }

    private static Flow.Publisher<OpenAITransport.StreamEvent> failedPublisher(RuntimeException failure) {
        return downstream -> downstream.onSubscribe(new Flow.Subscription() {
            private boolean terminated;

            @Override
            public void request(long count) {
                if (!terminated) {
                    terminated = true;
                    downstream.onError(failure);
                }
            }

            @Override
            public void cancel() {
                terminated = true;
            }
        });
    }

    private static AzureResponsesServiceVersion serviceVersion(String version) {
        return switch (version) {
            case "2024-02-15-preview" -> AzureResponsesServiceVersion.V2024_02_15_PREVIEW;
            case "2024-04-01-preview" -> AzureResponsesServiceVersion.V2024_04_01_PREVIEW;
            case "2024-06-01" -> AzureResponsesServiceVersion.V2024_06_01;
            case "2024-08-01-preview" -> AzureResponsesServiceVersion.V2024_08_01_PREVIEW;
            case "2024-09-01-preview" -> AzureResponsesServiceVersion.V2024_09_01_PREVIEW;
            case "2024-10-01-preview" -> AzureResponsesServiceVersion.V2024_10_01_PREVIEW;
            case "2024-10-21" -> AzureResponsesServiceVersion.V2024_10_21;
            case "2024-12-01-preview" -> AzureResponsesServiceVersion.V2024_12_01_PREVIEW;
            case "2025-01-01-preview" -> AzureResponsesServiceVersion.V2025_01_01_PREVIEW;
            case "2025-03-01-preview" -> AzureResponsesServiceVersion.V2025_03_01_PREVIEW;
            default -> throw new IllegalArgumentException("Unsupported Azure OpenAI API version.");
        };
    }

    private static AzureOpenAIProviderException protocol(String serviceCode) {
        return new AzureOpenAIProviderException(
                "Azure OpenAI protocol mapping failed.",
                AzureOpenAIProviderException.Kind.PROTOCOL,
                null,
                null,
                null,
                serviceCode);
    }

    private static AzureOpenAIProviderException closedFailure() {
        return new AzureOpenAIProviderException(
                "Azure OpenAI transport is closed.",
                AzureOpenAIProviderException.Kind.TRANSPORT,
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
}
