// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.StateValue;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class FoundryEvaluationClientTest {
    @Test
    void lifecycle_shouldUseExactPathsHeadersBodiesPollingAndPagination() {
        StubJdkHttpClient http = new StubJdkHttpClient();
        AtomicReference<AzureTokenRequest> tokenRequest = new AtomicReference<>();
        AtomicInteger runGets = new AtomicInteger();
        http.handler = request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/openai/v1/evals")) {
                return response(request, 200, """
                        {"id":"eval-one","name":"quality","created_at":1,"metadata":{}}
                        """);
            }
            if (path.endsWith("/evals/eval-one/runs") && request.method().equals("POST")) {
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"queued","created_at":2}
                        """);
            }
            if (path.endsWith("/runs/run-one/output_items")) {
                if (request.uri().getQuery().contains("after=item-one")) {
                    return response(request, 200, """
                            {"data":[{"id":"item-two","status":"completed","created_at":4,
                            "results":[],"sample":{}}],"has_more":false}
                            """);
                }
                return response(request, 200, """
                        {"data":[{"id":"item-one","status":"completed","created_at":3,
                        "results":[],"sample":{}}],"has_more":true,"last_id":"item-one"}
                        """);
            }
            if (path.endsWith("/evals/eval-one/runs/run-one")
                    && request.method().equals("GET")) {
                return response(request, 200, runGets.incrementAndGet() == 1 ? """
                                  {"id":"run-one","eval_id":"eval-one","status":"in_progress",
                                   "created_at":2}
                                  """ : """
                                  {"id":"run-one","eval_id":"eval-one","status":"completed",
                                   "created_at":2,"report_url":"https://ai.azure.com/report/one"}
                                  """);
            }
            throw new AssertionError("Unexpected request: " + request.method() + " " + request.uri());
        };
        FoundryEvaluationClientOptions options = options(tokenRequest)
                .initialPollDelay(Duration.ofMillis(1))
                .maxPollDelay(Duration.ofMillis(2))
                .build();
        try (FoundryEvaluationClient client = new FoundryEvaluationClient(options, http, null)) {
            FoundryEvaluation evaluation = client.createEvaluationAsync(
                            evaluationRequest(), new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            FoundryEvaluationResult result = client.startRun(new FoundryEvaluationRunRequest(
                            evaluation.id(),
                            "quality run",
                            StateValue.object(Map.of(
                                    "type", StateValue.string("jsonl"),
                                    "source",
                                            StateValue.object(Map.of(
                                                    "type",
                                                    StateValue.string("file_content"),
                                                    "content",
                                                    StateValue.array(List.of()))))),
                            Map.of()))
                    .resultAsync()
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            assertThat(result.run().status()).isEqualTo(FoundryEvaluationStatus.COMPLETED);
            assertThat(result.outputItems())
                    .extracting(FoundryEvaluationOutputItem::id)
                    .containsExactly("item-one", "item-two");
            assertThat(tokenRequest.get().scopes()).containsExactly("https://ai.azure.com/.default");
            assertThat(http.requests).allSatisfy(request -> {
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer token-secret");
                assertThat(request.headers().firstValue("User-Agent"))
                        .contains("agent-framework-java-foundry-evaluations");
            });
            String createBody = body(http.requests.getFirst());
            assertThat(createBody)
                    .contains("\"data_source_config\"")
                    .contains("\"testing_criteria\"")
                    .doesNotContain("token-secret");
        }
    }

    @Test
    void cancellation_shouldIssueServiceCancelAndCompleteLogicallyCancelled() throws Exception {
        StubJdkHttpClient http = new StubJdkHttpClient();
        CountDownLatch getStarted = new CountDownLatch(1);
        CountDownLatch cancelSent = new CountDownLatch(1);
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = new CompletableFuture<>();
        http.handler = request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/evals/eval-one/runs") && request.method().equals("POST")) {
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"queued","created_at":2}
                        """);
            }
            if (path.endsWith("/runs/run-one") && request.method().equals("GET")) {
                getStarted.countDown();
                return pending;
            }
            if (path.endsWith("/runs/run-one") && request.method().equals("POST")) {
                assertThat(request.bodyPublisher()).isPresent();
                assertThat(request.bodyPublisher().orElseThrow().contentLength())
                        .isZero();
                cancelSent.countDown();
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"cancelled","created_at":2}
                        """);
            }
            throw new AssertionError("Unexpected request: " + request.uri());
        };
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).build(), http, null)) {
            RunHandle<FoundryEvaluationResult> handle = client.startRun(new FoundryEvaluationRunRequest(
                    "eval-one", null, StateValue.object(Map.of("type", StateValue.string("jsonl"))), Map.of()));
            assertThat(getStarted.await(5, TimeUnit.SECONDS)).isTrue();

            handle.cancel();

            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(cancelSent.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void awaitRunCancellation_shouldStopOnlyLocalObservationByDefault() throws Exception {
        StubJdkHttpClient http = new StubJdkHttpClient();
        CountDownLatch getStarted = new CountDownLatch(1);
        AtomicInteger cancelCalls = new AtomicInteger();
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = new CompletableFuture<>();
        http.handler = request -> {
            if (request.method().equals("GET")) {
                getStarted.countDown();
                return pending;
            }
            cancelCalls.incrementAndGet();
            return response(request, 200, """
                    {"id":"run-one","eval_id":"eval-one","status":"cancelled","created_at":2}
                    """);
        };
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).build(), http, null)) {
            java.util.concurrent.CompletionStage<FoundryEvaluationRun> observation =
                    client.awaitRunAsync("eval-one", "run-one", cancellation);
            assertThat(getStarted.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.cancel();

            assertThatThrownBy(() -> observation.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(cancelCalls).hasValue(0);
        }
    }

    @Test
    void awaitRunCancellation_shouldRequestRemoteCancellationOnlyWhenExplicitlyEnabled() throws Exception {
        StubJdkHttpClient http = new StubJdkHttpClient();
        CountDownLatch getStarted = new CountDownLatch(1);
        CountDownLatch cancelSent = new CountDownLatch(1);
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = new CompletableFuture<>();
        http.handler = request -> {
            if (request.method().equals("GET")) {
                getStarted.countDown();
                return pending;
            }
            cancelSent.countDown();
            return response(request, 200, """
                    {"id":"run-one","eval_id":"eval-one","status":"cancelled","created_at":2}
                    """);
        };
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).build(), http, null)) {
            java.util.concurrent.CompletionStage<FoundryEvaluationRun> observation =
                    client.awaitRunAsync("eval-one", "run-one", cancellation, true);
            assertThat(getStarted.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.cancel();

            assertThatThrownBy(() -> observation.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(cancelSent.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void awaitRunTimeout_shouldStopOnlyLocalObservationByDefault() {
        StubJdkHttpClient http = new StubJdkHttpClient();
        AtomicInteger cancelCalls = new AtomicInteger();
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = new CompletableFuture<>();
        http.handler = request -> {
            if (request.method().equals("GET")) {
                return pending;
            }
            cancelCalls.incrementAndGet();
            return response(request, 200, """
                    {"id":"run-one","eval_id":"eval-one","status":"cancelled","created_at":2}
                    """);
        };
        try (FoundryEvaluationClient client = new FoundryEvaluationClient(
                options(null).operationTimeout(Duration.ofMillis(25)).build(), http, null)) {
            java.util.concurrent.CompletionStage<FoundryEvaluationRun> observation =
                    client.awaitRunAsync("eval-one", "run-one", new DefaultRunCancellation());

            assertThatThrownBy(() -> observation
                            .toCompletableFuture()
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join())
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(cancelCalls).hasValue(0);
        }
    }

    @Test
    void polling_shouldRejectUnknownFutureStatus() {
        StubJdkHttpClient http = new StubJdkHttpClient();
        http.handler = request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/evals/eval-one/runs") && request.method().equals("POST")) {
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"queued","created_at":2}
                        """);
            }
            if (path.endsWith("/runs/run-one") && request.method().equals("GET")) {
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"future_state","created_at":2}
                        """);
            }
            throw new AssertionError("Unexpected request: " + request.method() + " " + request.uri());
        };
        try (FoundryEvaluationClient client = new FoundryEvaluationClient(
                options(null).initialPollDelay(Duration.ofMillis(1)).build(), http, null)) {
            RunHandle<FoundryEvaluationResult> handle = client.startRun(new FoundryEvaluationRunRequest(
                    "eval-one", null, StateValue.object(Map.of("type", StateValue.string("jsonl"))), Map.of()));

            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(FoundryEvaluationException.class)
                    .rootCause()
                    .extracting(failure -> ((FoundryEvaluationException) failure).serviceCode())
                    .isEqualTo("unknown_evaluation_status");
        }
    }

    @Test
    void timeout_shouldCompleteWhenPollRequestNeverReturnsAndIssueServiceCancel() {
        StubJdkHttpClient http = new StubJdkHttpClient();
        AtomicInteger cancelCalls = new AtomicInteger();
        CompletableFuture<java.net.http.HttpResponse<byte[]>> pending = new CompletableFuture<>();
        http.handler = request -> {
            String path = request.uri().getPath();
            if (path.endsWith("/evals/eval-one/runs") && request.method().equals("POST")) {
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"queued","created_at":2}
                        """);
            }
            if (path.endsWith("/runs/run-one") && request.method().equals("GET")) {
                return pending;
            }
            if (path.endsWith("/runs/run-one") && request.method().equals("POST")) {
                cancelCalls.incrementAndGet();
                return response(request, 200, """
                        {"id":"run-one","eval_id":"eval-one","status":"cancelled","created_at":2}
                        """);
            }
            throw new AssertionError("Unexpected request: " + request.method() + " " + request.uri());
        };
        try (FoundryEvaluationClient client = new FoundryEvaluationClient(
                options(null).operationTimeout(Duration.ofMillis(25)).build(), http, null)) {
            RunHandle<FoundryEvaluationResult> handle = client.startRun(new FoundryEvaluationRunRequest(
                    "eval-one", null, StateValue.object(Map.of("type", StateValue.string("jsonl"))), Map.of()));

            assertThatThrownBy(() -> handle.resultAsync()
                            .toCompletableFuture()
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join())
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(cancelCalls).hasValue(1);
        }
    }

    @Test
    void outputPagination_shouldRejectRepeatedCursor() {
        StubJdkHttpClient http = new StubJdkHttpClient();
        http.handler = request -> response(request, 200, """
                {"data":[{"id":"item-one","status":"completed","created_at":3,
                "results":[],"sample":{}}],"has_more":true,"last_id":"item-one"}
                """);
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).build(), http, null)) {
            assertThatThrownBy(() -> client.listOutputItemsAsync(
                                    "eval-one", "run-one", 10, "item-one", new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(FoundryEvaluationException.class)
                    .rootCause()
                    .extracting(failure -> ((FoundryEvaluationException) failure).serviceCode())
                    .isEqualTo("output_cursor_loop");
        }
    }

    @Test
    void errors_shouldPreserveStatusRequestIdRetryAfterAndRedactBodySecrets() {
        StubJdkHttpClient http = new StubJdkHttpClient();
        http.handler = request -> response(
                request, 429, """
                {"error":{"code":"rate_limit","message":"token=credential-secret"}}
                """, Map.of("x-request-id", List.of("request-one"), "retry-after", List.of("7")));
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).maxRetries(0).build(), http, null)) {
            assertThatThrownBy(() -> client.getRunAsync("eval-one", "run-one", new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(FoundryEvaluationException.class)
                    .rootCause()
                    .satisfies(failure -> {
                        FoundryEvaluationException mapped = (FoundryEvaluationException) failure;
                        assertThat(mapped.statusCode()).isEqualTo(429);
                        assertThat(mapped.requestId()).isEqualTo("request-one");
                        assertThat(mapped.serviceCode()).isEqualTo("rate_limit");
                        assertThat(mapped.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                        assertThat(mapped.getMessage()).contains("[REDACTED]").doesNotContain("credential-secret");
                    });
        }
    }

    @Test
    void retryBackoff_shouldCancelImmediatelyAndNeverSendAnotherRequest() throws Exception {
        StubJdkHttpClient http = new StubJdkHttpClient();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        http.handler = request -> {
            calls.incrementAndGet();
            firstRequest.countDown();
            return response(request, 429, "{}", Map.of("retry-after", List.of(Long.toString(Long.MAX_VALUE))));
        };
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).maxRetries(1).build(), http, null)) {
            java.util.concurrent.CompletionStage<FoundryEvaluation> result =
                    client.getEvaluationAsync("eval-one", cancellation);
            assertThat(firstRequest.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.cancel();

            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            Thread.sleep(1_200);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void closeDuringRetryBackoff_shouldCancelTimerAndNeverSendAnotherRequest() throws Exception {
        StubJdkHttpClient http = new StubJdkHttpClient();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        http.handler = request -> {
            calls.incrementAndGet();
            firstRequest.countDown();
            return response(request, 503, "{}", Map.of("retry-after", List.of("1")));
        };
        FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).maxRetries(1).build(), http, null);
        java.util.concurrent.CompletionStage<FoundryEvaluation> result =
                client.getEvaluationAsync("eval-one", new DefaultRunCancellation());
        assertThat(firstRequest.await(5, TimeUnit.SECONDS)).isTrue();

        client.close();

        assertThatThrownBy(() -> result.toCompletableFuture().join())
                .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
        Thread.sleep(1_200);
        assertThat(calls).hasValue(1);
    }

    @Test
    void discovery_shouldUseAzureProjects230BuilderAndV1Path() {
        AzureRecordingHttpClient azureHttp = new AzureRecordingHttpClient();
        try (FoundryEvaluationClient client =
                new FoundryEvaluationClient(options(null).build(), new StubJdkHttpClient(), azureHttp)) {
            FoundryEvaluationPage<FoundryProjectResource> page = client.listDeploymentsAsync(
                            10, null, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(page.items()).extracting(FoundryProjectResource::name).containsExactly("deployment-one");
            assertThat(azureHttp.request.get().getUrl().toString())
                    .contains("/deployments")
                    .contains("api-version=v1");
            assertThat(azureHttp.request.get().getHeaders().getValue(HttpHeaderName.AUTHORIZATION))
                    .isEqualTo("Bearer token-secret");
        }
    }

    @Test
    void close_shouldLeaveCallerExecutorsUntouched() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        FoundryEvaluationClient client = new FoundryEvaluationClient(
                options(null).executor(executor).scheduler(scheduler).build(), new StubJdkHttpClient(), null);

        client.close();

        assertThat(executor.isShutdown()).isFalse();
        assertThat(scheduler.isShutdown()).isFalse();
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    private static FoundryEvaluationClientOptions.Builder options(AtomicReference<AzureTokenRequest> tokenRequest) {
        return FoundryEvaluationClientOptions.builder()
                .projectEndpoint("https://resource.services.ai.azure.com/api/projects/project-one")
                .authenticationProvider((request, cancellation) -> {
                    if (tokenRequest != null) {
                        tokenRequest.set(request);
                    }
                    return CompletableFuture.completedStage(
                            new AzureAccessToken("token-secret", Instant.now().plusSeconds(3600)));
                })
                .requestTimeout(Duration.ofSeconds(5))
                .operationTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .maxPageSize(100)
                .maxPages(10);
    }

    private static FoundryEvaluationRequest evaluationRequest() {
        return new FoundryEvaluationRequest(
                "quality",
                StateValue.object(Map.of(
                        "type", StateValue.string("custom"),
                        "item_schema", StateValue.object(Map.of("type", StateValue.string("object"))))),
                List.of(StateValue.object(Map.of(
                        "type", StateValue.string("string_check"),
                        "name", StateValue.string("contains")))),
                Map.of());
    }

    private static CompletableFuture<java.net.http.HttpResponse<byte[]>> response(
            java.net.http.HttpRequest request, int status, String body) {
        return response(request, status, body, Map.of());
    }

    private static CompletableFuture<java.net.http.HttpResponse<byte[]>> response(
            java.net.http.HttpRequest request, int status, String body, Map<String, List<String>> headers) {
        return CompletableFuture.completedFuture(new StubJdkResponse(request, status, body, headers));
    }

    private static String body(java.net.http.HttpRequest request) {
        BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ArrayList<ByteBuffer> buffers = new ArrayList<>();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                buffers.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        complete.join();
        int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer result = ByteBuffer.allocate(size);
        buffers.forEach(result::put);
        return new String(result.array(), StandardCharsets.UTF_8);
    }

    private static final class StubJdkHttpClient extends HttpClient {
        private final List<java.net.http.HttpRequest> requests = new ArrayList<>();
        private java.util.function.Function<
                        java.net.http.HttpRequest, CompletableFuture<java.net.http.HttpResponse<byte[]>>>
                handler = request -> response(request, 500, "{}");

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(request);
            return (CompletableFuture<java.net.http.HttpResponse<T>>) (CompletableFuture<?>) handler.apply(request);
        }

        @Override
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record StubJdkResponse(
            java.net.http.HttpRequest request, int statusCode, byte[] body, java.net.http.HttpHeaders headers)
            implements java.net.http.HttpResponse<byte[]> {
        private StubJdkResponse(
                java.net.http.HttpRequest request, int statusCode, String body, Map<String, List<String>> headers) {
            this(
                    request,
                    statusCode,
                    body.getBytes(StandardCharsets.UTF_8),
                    java.net.http.HttpHeaders.of(headers, (name, value) -> true));
        }

        @Override
        public Optional<java.net.http.HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }

    private static final class AzureRecordingHttpClient implements com.azure.core.http.HttpClient {
        private final AtomicReference<HttpRequest> request = new AtomicReference<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            this.request.set(request);
            return Mono.just(new AzureStringResponse(request, """
                    {"value":[{"type":"Deployment","name":"deployment-one"}]}
                    """));
        }
    }

    private static final class AzureStringResponse extends HttpResponse {
        private final byte[] body;
        private final HttpHeaders headers;

        private AzureStringResponse(HttpRequest request, String body) {
            super(request);
            this.body = body.getBytes(StandardCharsets.UTF_8);
            headers = new HttpHeaders()
                    .set(HttpHeaderName.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderName.CONTENT_LENGTH, Integer.toString(this.body.length));
        }

        @Override
        public int getStatusCode() {
            return 200;
        }

        @SuppressWarnings("deprecation")
        @Override
        public String getHeaderValue(String name) {
            return headers.getValue(HttpHeaderName.fromString(name));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.just(ByteBuffer.wrap(body));
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(body.clone());
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(body, charset));
        }

        @Override
        public BinaryData getBodyAsBinaryData() {
            return BinaryData.fromBytes(body);
        }
    }
}
