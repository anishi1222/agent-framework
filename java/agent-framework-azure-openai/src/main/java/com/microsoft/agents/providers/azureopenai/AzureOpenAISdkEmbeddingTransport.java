// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.OpenAIServiceVersion;
import com.azure.ai.openai.models.EmbeddingItem;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.KeyCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.rest.RequestOptions;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingTransport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

final class AzureOpenAISdkEmbeddingTransport implements OpenAIEmbeddingTransport {
    static final String INPUT_TYPE_METADATA_KEY = "azureOpenAI.inputType";

    private final AzureOpenAIEmbeddingClientOptions options;

    private final OpenAIAsyncClient client;

    private final AtomicBoolean closed = new AtomicBoolean();

    private AzureOpenAISdkEmbeddingTransport(AzureOpenAIEmbeddingClientOptions options, OpenAIAsyncClient client) {
        this.options = options;
        this.client = client;
    }

    static AzureOpenAISdkEmbeddingTransport create(AzureOpenAIEmbeddingClientOptions options) {
        return create(options, null);
    }

    static AzureOpenAISdkEmbeddingTransport create(AzureOpenAIEmbeddingClientOptions options, HttpClient httpClient) {
        Objects.requireNonNull(options, "options");
        OpenAIClientBuilder builder = new OpenAIClientBuilder()
                .endpoint(options.endpoint().toString())
                .serviceVersion(serviceVersion(options.apiVersion()))
                .retryOptions(new RetryOptions(new ExponentialBackoffOptions().setMaxRetries(options.maxRetries())))
                .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE))
                .addPolicy(new AzureOpenAIFeatureUsagePolicy());
        if (httpClient != null) {
            builder.httpClient(httpClient);
        }
        if (options.authenticationMode() == AzureOpenAIAuthenticationMode.API_KEY) {
            builder.credential(new KeyCredential(options.apiKey()));
        } else {
            builder.credential(options.tokenCredential());
        }
        return new AzureOpenAISdkEmbeddingTransport(options, builder.buildAsyncClient());
    }

    @Override
    public CompletionStage<Response> generateAsync(Request request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        CompletableFuture<com.azure.core.http.rest.Response<Embeddings>> responseFuture;
        try {
            EmbeddingsOptions sdkOptions = new EmbeddingsOptions(request.values());
            if (request.dimensions() != null) {
                sdkOptions.setDimensions(request.dimensions());
            }
            if (request.user() != null) {
                sdkOptions.setUser(request.user());
            }
            StateValue inputType = request.metadata().get(INPUT_TYPE_METADATA_KEY);
            if (inputType instanceof StateValue.StringValue string) {
                sdkOptions.setInputType(string.value());
            }
            Mono<com.azure.core.http.rest.Response<Embeddings>> response = client.getEmbeddingsWithResponse(
                            request.model(), sdkOptions, new RequestOptions())
                    .timeout(options.timeout());
            responseFuture = response.toFuture();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(AzureOpenAIErrorMapper.map(failure));
        }

        CompletableFuture<Response> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> responseFuture.cancel(true)));
        responseFuture.whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(AzureOpenAIErrorMapper.map(failure));
                } else if (response == null || response.getValue() == null) {
                    result.completeExceptionally(protocol("null_embedding_response"));
                } else {
                    result.complete(map(request, response));
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
    public void close() {
        closed.set(true);
    }

    private static Response map(Request request, com.azure.core.http.rest.Response<Embeddings> response) {
        Embeddings sdkResponse = response.getValue();
        ArrayList<Item> items = new ArrayList<>();
        if (sdkResponse.getData() != null) {
            for (EmbeddingItem item : sdkResponse.getData()) {
                items.add(new Item(item.getPromptIndex(), FloatEmbeddingVector.fromFloats(item.getEmbedding())));
            }
        }
        items.sort(Comparator.comparingInt(Item::index));
        UsageDetails usage = sdkResponse.getUsage() == null
                ? null
                : UsageDetails.builder()
                        .inputTokens(sdkResponse.getUsage().getPromptTokens())
                        .totalTokens(sdkResponse.getUsage().getTotalTokens())
                        .build();
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        String requestId = header(response, "x-request-id", "apim-request-id", "x-ms-request-id");
        if (AzureOpenAIProviderException.safeIdentifier(requestId) != null) {
            metadata.put("openai.requestId", StateValue.string(requestId));
        }
        return new Response(items, request.model(), usage, metadata);
    }

    private static String header(com.azure.core.http.rest.Response<?> response, String... names) {
        for (String name : names) {
            String value = response.getHeaders().getValue(HttpHeaderName.fromString(name));
            if (AzureOpenAIProviderException.safeIdentifier(value) != null) {
                return value;
            }
        }
        return null;
    }

    private static OpenAIServiceVersion serviceVersion(String version) {
        return switch (version) {
            case "2022-12-01" -> OpenAIServiceVersion.V2022_12_01;
            case "2023-05-15" -> OpenAIServiceVersion.V2023_05_15;
            case "2023-06-01-preview" -> OpenAIServiceVersion.V2023_06_01_PREVIEW;
            case "2023-07-01-preview" -> OpenAIServiceVersion.V2023_07_01_PREVIEW;
            case "2024-02-01" -> OpenAIServiceVersion.V2024_02_01;
            case "2024-02-15-preview" -> OpenAIServiceVersion.V2024_02_15_PREVIEW;
            case "2024-03-01-preview" -> OpenAIServiceVersion.V2024_03_01_PREVIEW;
            case "2024-04-01-preview" -> OpenAIServiceVersion.V2024_04_01_PREVIEW;
            case "2024-05-01-preview" -> OpenAIServiceVersion.V2024_05_01_PREVIEW;
            case "2024-06-01" -> OpenAIServiceVersion.V2024_06_01;
            case "2024-07-01-preview" -> OpenAIServiceVersion.V2024_07_01_PREVIEW;
            case "2024-08-01-preview" -> OpenAIServiceVersion.V2024_08_01_PREVIEW;
            case "2024-09-01-preview" -> OpenAIServiceVersion.V2024_09_01_PREVIEW;
            case "2024-10-01-preview" -> OpenAIServiceVersion.V2024_10_01_PREVIEW;
            case "2025-01-01-preview" -> OpenAIServiceVersion.V2025_01_01_PREVIEW;
            default -> throw new IllegalArgumentException("Unsupported Azure OpenAI API version.");
        };
    }

    private static AzureOpenAIProviderException protocol(String serviceCode) {
        return new AzureOpenAIProviderException(
                "Azure OpenAI embedding protocol mapping failed.",
                AzureOpenAIProviderException.Kind.PROTOCOL,
                null,
                null,
                null,
                serviceCode);
    }

    private static AzureOpenAIProviderException closedFailure() {
        return new AzureOpenAIProviderException(
                "Azure OpenAI embedding transport is closed.",
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
