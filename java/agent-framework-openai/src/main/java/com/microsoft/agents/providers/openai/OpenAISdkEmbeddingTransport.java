// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class OpenAISdkEmbeddingTransport implements OpenAIEmbeddingTransport {
    private final OpenAIClientAsync client;

    private final AtomicBoolean closed = new AtomicBoolean();

    OpenAISdkEmbeddingTransport(OpenAIClientAsync client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    static OpenAISdkEmbeddingTransport create(OpenAIEmbeddingClientOptions options) {
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
        return new OpenAISdkEmbeddingTransport(builder.build());
    }

    @Override
    public CompletionStage<Response> generateAsync(Request request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new OpenAISdkException("transport_closed"));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        CompletableFuture<HttpResponseFor<CreateEmbeddingResponse>> responseFuture;
        try {
            responseFuture = client.embeddings().withRawResponse().create(params(request));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(mapFailure(failure));
        }

        CompletableFuture<Response> result = new CompletableFuture<>();
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
                        result.complete(
                                map(rawResponse.parse(), rawResponse.requestId().orElse(null)));
                    }
                }
            } catch (RuntimeException mappingFailure) {
                result.completeExceptionally(mapFailure(mappingFailure));
            } finally {
                closeRegistration(registration.getAndSet(null));
            }
        });
        return result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }

    private static EmbeddingCreateParams params(Request request) {
        EmbeddingCreateParams.Builder builder = EmbeddingCreateParams.builder()
                .inputOfArrayOfStrings(request.values())
                .model(request.model())
                .encodingFormat(
                        request.encodingFormat() == OpenAIEmbeddingEncodingFormat.BASE64
                                ? EmbeddingCreateParams.EncodingFormat.BASE64
                                : EmbeddingCreateParams.EncodingFormat.FLOAT);
        if (request.dimensions() != null) {
            builder.dimensions(request.dimensions());
        }
        if (request.user() != null) {
            builder.user(request.user());
        }
        return builder.build();
    }

    private static Response map(CreateEmbeddingResponse response, String requestId) {
        ArrayList<Item> items = new ArrayList<>(response.data().size());
        for (com.openai.models.embeddings.Embedding item : response.data()) {
            long rawIndex = item.index();
            if (rawIndex < 0 || rawIndex > Integer.MAX_VALUE) {
                throw protocol(requestId, "invalid_embedding_index");
            }
            items.add(new Item((int) rawIndex, vector(item.embeddingValue(), requestId)));
        }
        items.sort(Comparator.comparingInt(Item::index));
        UsageDetails usage = UsageDetails.builder()
                .inputTokens(response.usage().promptTokens())
                .totalTokens(response.usage().totalTokens())
                .build();
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        String safeRequestId = OpenAIProviderException.safeIdentifier(requestId);
        if (safeRequestId != null) {
            metadata.put("openai.requestId", StateValue.string(safeRequestId));
        }
        return new Response(items, response.model(), usage, metadata);
    }

    private static FloatEmbeddingVector vector(EmbeddingValue value, String requestId) {
        if (value.isFloats()) {
            return FloatEmbeddingVector.fromFloats(value.asFloats());
        }
        if (!value.isBase64()) {
            throw protocol(requestId, "unsupported_embedding_encoding");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.asBase64());
        } catch (IllegalArgumentException failure) {
            throw protocol(requestId, "invalid_base64_embedding");
        }
        if (bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            throw protocol(requestId, "invalid_base64_embedding");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        ArrayList<Double> values = new ArrayList<>(bytes.length / Float.BYTES);
        while (buffer.hasRemaining()) {
            values.add((double) buffer.getFloat());
        }
        return new FloatEmbeddingVector(values);
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

    private static OpenAIProtocolException protocol(String requestId, String code) {
        return new OpenAIProtocolException("OpenAI returned an invalid embedding response.", requestId, code);
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }
}
