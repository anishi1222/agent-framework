// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.azure.ai.contentunderstanding.ContentUnderstandingAsyncClient;
import com.azure.ai.contentunderstanding.ContentUnderstandingClientBuilder;
import com.azure.ai.contentunderstanding.models.AnalysisContent;
import com.azure.ai.contentunderstanding.models.AnalysisInput;
import com.azure.ai.contentunderstanding.models.AnalysisResult;
import com.azure.ai.contentunderstanding.models.ContentAnalyzer;
import com.azure.ai.contentunderstanding.models.ContentAnalyzerAnalyzeOperationStatus;
import com.azure.ai.contentunderstanding.models.ContentAnalyzerOperationStatus;
import com.azure.ai.contentunderstanding.models.UsageDetails;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.rest.RequestOptions;
import com.azure.core.models.ResponseError;
import com.azure.core.util.BinaryData;
import com.azure.core.util.polling.PollerFlux;
import com.microsoft.agents.azure.AzureAuthenticationProvider;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import reactor.core.publisher.Mono;

final class ContentUnderstandingSdkTransport implements ContentUnderstandingTransport {
    private static final String SCOPE = "https://cognitiveservices.azure.com/.default";

    private final AzureContentUnderstandingOptions options;
    private final ContentUnderstandingAsyncClient client;
    private final StrictJsonCodec json;

    private ContentUnderstandingSdkTransport(
            AzureContentUnderstandingOptions options, ContentUnderstandingAsyncClient client) {
        this.options = options;
        this.client = client;
        json = new StrictJsonCodec(
                options.maxJsonBytes(),
                options.maxJsonBytes(),
                64,
                Math.min(options.maxJsonBytes(), 1_048_576),
                256,
                100_000);
    }

    static ContentUnderstandingSdkTransport create(AzureContentUnderstandingOptions options) {
        return create(options, null);
    }

    static ContentUnderstandingSdkTransport create(AzureContentUnderstandingOptions options, HttpClient httpClient) {
        RetryOptions retries = new RetryOptions(new ExponentialBackoffOptions()
                .setMaxRetries(options.maxRetries())
                .setBaseDelay(Duration.ofMillis(200))
                .setMaxDelay(Duration.ofSeconds(5)));
        ContentUnderstandingClientBuilder builder = new ContentUnderstandingClientBuilder()
                .endpoint(options.endpoint().toString())
                .credential(tokenCredential(options.authenticationProvider()))
                .retryOptions(retries)
                .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE));
        if (httpClient != null) {
            builder.httpClient(httpClient);
        }
        return new ContentUnderstandingSdkTransport(options, builder.buildAsyncClient());
    }

    @Override
    public CompletionStage<ContentAnalysisResult> analyzeAsync(
            ContentAnalysisRequest request, RunCancellation cancellation) {
        List<AnalysisInput> inputs = request.inputs().stream()
                .map(ContentUnderstandingSdkTransport::input)
                .toList();
        PollerFlux<ContentAnalyzerAnalyzeOperationStatus, AnalysisResult> poller = client.beginAnalyze(
                        request.analyzerId(),
                        inputs,
                        request.modelDeployments().isEmpty() ? null : request.modelDeployments(),
                        null)
                .setPollInterval(options.pollInterval());
        Mono<ContentAnalysisResult> terminal = poller.last()
                .flatMap(response -> response.getFinalResult().map(result -> analysis(response.getValue(), result)));
        return stage(terminal.timeout(options.operationTimeout()), cancellation);
    }

    @Override
    public CompletionStage<ContentAnalyzerDefinition> createAnalyzerAsync(
            ContentAnalyzerRequest request, RunCancellation cancellation) {
        ContentAnalyzer sdk = analyzerModel(request.definition());
        PollerFlux<ContentAnalyzerOperationStatus, ContentAnalyzer> poller =
                client.beginCreateAnalyzer(request.analyzerId(), sdk).setPollInterval(options.pollInterval());
        Mono<ContentAnalyzerDefinition> terminal =
                poller.last().flatMap(response -> response.getFinalResult().map(this::analyzer));
        return stage(terminal.timeout(options.operationTimeout()), cancellation);
    }

    @Override
    public CompletionStage<ContentAnalyzerDefinition> getAnalyzerAsync(
            String analyzerId, RunCancellation cancellation) {
        return stage(client.getAnalyzer(nonBlank(analyzerId, "analyzerId")), cancellation)
                .thenApply(this::analyzer);
    }

    @Override
    public CompletionStage<ContentAnalyzerDefinition> updateAnalyzerAsync(
            ContentAnalyzerRequest request, RunCancellation cancellation) {
        byte[] definition = json.write(request.definition());
        return stage(
                        client.updateAnalyzerWithResponse(
                                        request.analyzerId(), BinaryData.fromBytes(definition), new RequestOptions())
                                .map(response -> response.getValue().toObject(ContentAnalyzer.class)),
                        cancellation)
                .thenApply(this::analyzer);
    }

    @Override
    public CompletionStage<Void> deleteAnalyzerAsync(String analyzerId, RunCancellation cancellation) {
        return stage(client.deleteAnalyzer(nonBlank(analyzerId, "analyzerId")), cancellation);
    }

    @Override
    public CompletionStage<ContentUnderstandingPage<ContentAnalyzerDefinition>> listAnalyzersAsync(
            int limit, String after, RunCancellation cancellation) {
        int maximum = options.maxPageSize() * options.maxPages();
        return stage(client.listAnalyzers().map(this::analyzer).take(maximum).collectList(), cancellation)
                .thenApply(all -> {
                    int start = 0;
                    if (after != null) {
                        start = -1;
                        for (int index = 0; index < all.size(); index++) {
                            if (after.equals(all.get(index).analyzerId())) {
                                start = index + 1;
                                break;
                            }
                        }
                        if (start < 0) {
                            return new ContentUnderstandingPage<>(List.of(), null, false);
                        }
                    }
                    int end = Math.min(all.size(), start + limit);
                    List<ContentAnalyzerDefinition> items = List.copyOf(all.subList(start, end));
                    boolean hasMore = end < all.size();
                    String next = hasMore ? items.getLast().analyzerId() : null;
                    if (hasMore && next.equals(after)) {
                        throw protocol("analyzer_cursor_loop");
                    }
                    return new ContentUnderstandingPage<>(items, next, hasMore);
                });
    }

    private ContentAnalysisResult analysis(ContentAnalyzerAnalyzeOperationStatus operation, AnalysisResult result) {
        if (operation == null || operation.getId() == null || operation.getStatus() == null) {
            throw protocol("missing_analysis_operation");
        }
        ContentOperationStatus status =
                ContentOperationStatus.fromValue(operation.getStatus().toString());
        if (!status.isKnown() || !status.equals(ContentOperationStatus.SUCCEEDED)) {
            throw protocol("unexpected_analysis_terminal_status");
        }
        List<ContentWarning> warnings = result.getWarnings() == null
                ? List.of()
                : result.getWarnings().stream()
                        .map(ContentUnderstandingSdkTransport::warning)
                        .toList();
        List<AnalyzedContent> contents = result.getContents() == null
                ? List.of()
                : result.getContents().stream().map(this::content).toList();
        return new ContentAnalysisResult(
                operation.getId(),
                status,
                result.getAnalyzerId(),
                result.getApiVersion(),
                result.getCreatedAt() == null ? null : result.getCreatedAt().toInstant(),
                result.getStringEncoding(),
                warnings,
                contents,
                usage(operation.getUsage()));
    }

    private AnalyzedContent content(AnalysisContent value) {
        StateValue fields = value.getFields() == null
                ? StateValue.object(Map.of())
                : json.parse(BinaryData.fromObject(value.getFields()).toBytes());
        if (!(fields instanceof StateValue.ObjectValue fieldObject)) {
            throw protocol("analysis_fields_not_object");
        }
        return new AnalyzedContent(
                value.getKind() == null ? "unknown" : value.getKind().toString(),
                value.getMimeType(),
                value.getAnalyzerId(),
                value.getCategory(),
                value.getPath(),
                value.getMarkdown(),
                fieldObject);
    }

    private ContentAnalyzer analyzerModel(StateValue.ObjectValue definition) {
        byte[] bytes = json.write(definition);
        return BinaryData.fromBytes(bytes).toObject(ContentAnalyzer.class);
    }

    private ContentAnalyzerDefinition analyzer(ContentAnalyzer value) {
        byte[] bytes = BinaryData.fromObject(value).toBytes();
        StateValue parsed = json.parse(bytes);
        if (!(parsed instanceof StateValue.ObjectValue definition)) {
            throw protocol("analyzer_not_object");
        }
        return new ContentAnalyzerDefinition(
                value.getAnalyzerId(),
                value.getStatus() == null ? null : value.getStatus().toString(),
                value.getDescription(),
                value.getBaseAnalyzerId(),
                definition,
                value.getTags(),
                value.getCreatedAt() == null ? null : value.getCreatedAt().toInstant(),
                value.getLastModifiedAt() == null
                        ? null
                        : value.getLastModifiedAt().toInstant());
    }

    private static AnalysisInput input(ContentInput value) {
        AnalysisInput sdk = new AnalysisInput().setName(value.name()).setMimeType(value.mimeType());
        if (value.contentRange() != null) {
            sdk.setContentRange(contentRange(value.contentRange()));
        }
        return switch (value) {
            case ContentUrlInput url -> sdk.setUrl(url.uri().toString());
            case ContentBytesInput bytes -> sdk.setData(bytes.bytes());
        };
    }

    private static com.azure.ai.contentunderstanding.models.ContentRange contentRange(String value) {
        try {
            String[] parts = value.split(",", -1);
            ArrayList<com.azure.ai.contentunderstanding.models.ContentRange> ranges = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                int separator = trimmed.indexOf('-');
                if (separator < 0) {
                    ranges.add(com.azure.ai.contentunderstanding.models.ContentRange.page(Integer.parseInt(trimmed)));
                } else if (separator == trimmed.length() - 1) {
                    ranges.add(com.azure.ai.contentunderstanding.models.ContentRange.pagesFrom(
                            Integer.parseInt(trimmed.substring(0, separator))));
                } else {
                    ranges.add(com.azure.ai.contentunderstanding.models.ContentRange.pages(
                            Integer.parseInt(trimmed.substring(0, separator)),
                            Integer.parseInt(trimmed.substring(separator + 1))));
                }
            }
            return ranges.size() == 1
                    ? ranges.getFirst()
                    : com.azure.ai.contentunderstanding.models.ContentRange.combine(
                            ranges.toArray(com.azure.ai.contentunderstanding.models.ContentRange[]::new));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "contentRange must use one-based document pages such as 1-3,5,9-.", failure);
        }
    }

    private static ContentWarning warning(ResponseError value) {
        return new ContentWarning(safe(value.getCode()), safe(value.getMessage()));
    }

    private static Map<String, Number> usage(UsageDetails value) {
        if (value == null) {
            return Map.of();
        }
        LinkedHashMap<String, Number> result = new LinkedHashMap<>();
        put(result, "documentPagesMinimal", value.getDocumentPagesMinimal());
        put(result, "documentPagesBasic", value.getDocumentPagesBasic());
        put(result, "documentPagesStandard", value.getDocumentPagesStandard());
        put(result, "audioHours", value.getAudioHours());
        put(result, "videoHours", value.getVideoHours());
        put(result, "contextualizationTokens", value.getContextualizationTokens());
        if (value.getTokens() != null) {
            value.getTokens().forEach((key, count) -> put(result, "tokens." + key, count));
        }
        return Map.copyOf(result);
    }

    private static void put(Map<String, Number> target, String key, Number value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static TokenCredential tokenCredential(AzureAuthenticationProvider provider) {
        return context -> Mono.fromCompletionStage(provider.getTokenAsync(
                        new AzureTokenRequest(context.getScopes(), context.getTenantId()),
                        new DefaultRunCancellation()))
                .map(token -> new AccessToken(
                        token.token(), java.time.OffsetDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC)));
    }

    private static <T> CompletionStage<T> stage(Mono<T> mono, RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
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
                result.completeExceptionally(mapFailure(failure));
            } else {
                result.complete(value);
            }
        });
        return result.minimalCompletionStage();
    }

    private static RuntimeException mapFailure(Throwable failure) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        if (cause instanceof ContentUnderstandingException mapped) {
            return mapped;
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return new ContentUnderstandingException(
                    "Content Understanding operation timed out.",
                    cause,
                    ContentUnderstandingException.Kind.TRANSPORT,
                    null,
                    null,
                    "operation_timeout",
                    null);
        }
        if (cause instanceof ClientAuthenticationException) {
            return new ContentUnderstandingException(
                    "Content Understanding authentication failed.",
                    cause,
                    ContentUnderstandingException.Kind.AUTHENTICATION,
                    401,
                    null,
                    "authentication_failed",
                    null);
        }
        if (cause instanceof HttpResponseException responseFailure) {
            int status = responseFailure.getResponse().getStatusCode();
            String requestId =
                    responseFailure.getResponse().getHeaders().getValue(HttpHeaderName.fromString("x-request-id"));
            String retry =
                    responseFailure.getResponse().getHeaders().getValue(HttpHeaderName.fromString("retry-after"));
            return new ContentUnderstandingException(
                    "Content Understanding request failed with HTTP " + status + ".",
                    cause,
                    ContentUnderstandingException.Kind.SERVICE,
                    status,
                    requestId,
                    "http_" + status,
                    parseRetryAfter(retry));
        }
        return new ContentUnderstandingException(
                "Content Understanding transport failed.",
                cause,
                ContentUnderstandingException.Kind.TRANSPORT,
                null,
                null,
                "transport_failed",
                null);
    }

    private static Duration parseRetryAfter(String value) {
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

    private static ContentUnderstandingException protocol(String code) {
        return new ContentUnderstandingException(
                "Content Understanding protocol mapping failed.",
                null,
                ContentUnderstandingException.Kind.PROTOCOL,
                null,
                null,
                code,
                null);
    }

    private static String safe(Object value) {
        if (value == null) {
            return null;
        }
        String clean = value.toString()
                .replaceAll("(?i)(bearer|token|secret|api[-_ ]?key)\\s*[:=]?\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();
        return clean.substring(0, Math.min(clean.length(), 512));
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
