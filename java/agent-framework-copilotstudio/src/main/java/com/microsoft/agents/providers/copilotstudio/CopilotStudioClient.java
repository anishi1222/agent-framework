// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implements the current Copilot Studio Direct-to-Engine HTTP/SSE client with strict bounds.
 *
 * <p>Authorization uses caller-provided Entra tokens for the Power Platform audience. Tokens are
 * placed only in the authorization header and are never included in URIs, exceptions, or string
 * representations. Redirects are never followed.
 */
public final class CopilotStudioClient implements AutoCloseable {
    private final CopilotStudioClientOptions options;

    private final CopilotStudioTokenProvider tokenProvider;

    private final HttpClient httpClient;

    private final boolean ownsHttpClient;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final CopilotStudioWireCodec codec;

    private final Semaphore concurrency;

    private final Set<StreamRun> activeRuns = ConcurrentHashMap.newKeySet();

    private final Object tokenLock = new Object();

    private final AtomicBoolean closed = new AtomicBoolean();

    private CopilotStudioAccessToken cachedToken;

    private CompletionStage<CopilotStudioAccessToken> tokenRefresh;

    private CopilotStudioClient(
            CopilotStudioClientOptions options,
            CopilotStudioTokenProvider tokenProvider,
            HttpClient httpClient,
            boolean ownsHttpClient,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.options = options;
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        codec = new CopilotStudioWireCodec(options.limits());
        concurrency = new Semaphore(options.limits().maxConcurrentRequests());
    }

    /**
     * Creates a client builder.
     *
     * @return client builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable options.
     *
     * @return client options
     */
    public CopilotStudioClientOptions options() {
        return options;
    }

    /**
     * Starts a conversation and aggregates its initial activity stream.
     *
     * @param cancellation cancellation signal
     * @return conversation
     */
    public CompletionStage<CopilotStudioConversation> startConversationAsync(RunCancellation cancellation) {
        return collect(startConversationStreaming(cancellation), cancellation).thenApply(events -> {
            String conversationId = events.stream()
                    .map(CopilotStudioEvent::activity)
                    .map(CopilotStudioActivity::conversationId)
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> right)
                    .orElseThrow(() -> protocol(
                            "Copilot Studio start response omitted a conversation ID.", "missing_conversation_id"));
            CopilotStudioCursor cursor = events.isEmpty()
                    ? CopilotStudioCursor.empty()
                    : events.getLast().cursor();
            return new CopilotStudioConversation(
                    conversationId,
                    cursor,
                    events.stream().map(CopilotStudioEvent::activity).toList());
        });
    }

    /**
     * Starts a conversation with framework-owned cancellation.
     *
     * @return conversation
     */
    public CompletionStage<CopilotStudioConversation> startConversationAsync() {
        return startConversationAsync(new DefaultRunCancellation());
    }

    /**
     * Streams initial conversation activities.
     *
     * @param cancellation cancellation signal
     * @return cold bounded event stream
     */
    public Flow.Publisher<CopilotStudioEvent> startConversationStreaming(RunCancellation cancellation) {
        return stream(
                "POST",
                conversationUri(null, false),
                codec.startRequest(options.locale()),
                CopilotStudioCursor.empty(),
                cancellation,
                options.requestTimeout());
    }

    /**
     * Sends one activity and aggregates the returned activities.
     *
     * @param conversationId authorized conversation identity
     * @param activity new activity
     * @param cursor prior cursor
     * @param cancellation cancellation signal
     * @return de-duplicated response events
     */
    public CompletionStage<List<CopilotStudioEvent>> sendActivityAsync(
            String conversationId,
            CopilotStudioActivity activity,
            CopilotStudioCursor cursor,
            RunCancellation cancellation) {
        return collect(sendActivityStreaming(conversationId, activity, cursor, cancellation), cancellation);
    }

    /**
     * Streams the response to one activity.
     *
     * @param conversationId authorized conversation identity
     * @param activity new activity
     * @param cursor prior cursor
     * @param cancellation cancellation signal
     * @return cold bounded event stream
     */
    public Flow.Publisher<CopilotStudioEvent> sendActivityStreaming(
            String conversationId,
            CopilotStudioActivity activity,
            CopilotStudioCursor cursor,
            RunCancellation cancellation) {
        String id = required(conversationId, "conversationId");
        Objects.requireNonNull(activity, "activity");
        return stream(
                "POST",
                conversationUri(id, false),
                codec.activityRequest(id, activity),
                Objects.requireNonNull(cursor, "cursor"),
                cancellation,
                options.requestTimeout());
    }

    /**
     * Polls one Direct-to-Engine subscribe response using SSE {@code Last-Event-ID}.
     *
     * @param conversationId authorized conversation identity
     * @param cursor prior cursor
     * @param cancellation cancellation signal
     * @return events produced before the response closes
     */
    public CompletionStage<List<CopilotStudioEvent>> pollActivitiesAsync(
            String conversationId, CopilotStudioCursor cursor, RunCancellation cancellation) {
        return collect(subscribeStreaming(conversationId, cursor, cancellation), cancellation);
    }

    /**
     * Reconnects to the documented Direct-to-Engine subscription stream.
     *
     * @param conversationId authorized conversation identity
     * @param cursor prior cursor
     * @param cancellation cancellation signal
     * @return cold bounded event stream
     */
    public Flow.Publisher<CopilotStudioEvent> subscribeStreaming(
            String conversationId, CopilotStudioCursor cursor, RunCancellation cancellation) {
        return stream(
                "GET",
                conversationUri(required(conversationId, "conversationId"), true),
                null,
                Objects.requireNonNull(cursor, "cursor"),
                cancellation,
                options.reconnectTimeout());
    }

    /**
     * Alias for {@link #subscribeStreaming(String, CopilotStudioCursor, RunCancellation)}.
     *
     * @param conversationId authorized conversation identity
     * @param cursor prior cursor
     * @param cancellation cancellation signal
     * @return reconnect stream
     */
    public Flow.Publisher<CopilotStudioEvent> reconnectStreaming(
            String conversationId, CopilotStudioCursor cursor, RunCancellation cancellation) {
        return subscribeStreaming(conversationId, cursor, cancellation);
    }

    /**
     * Explicitly ends a conversation by sending an {@code endOfConversation} activity.
     *
     * @param conversationId authorized conversation identity
     * @param cursor prior cursor
     * @param cancellation cancellation signal
     * @return acknowledgement events
     */
    public CompletionStage<List<CopilotStudioEvent>> endConversationAsync(
            String conversationId, CopilotStudioCursor cursor, RunCancellation cancellation) {
        String id = required(conversationId, "conversationId");
        StateValue.ObjectValue raw = StateValue.object(java.util.Map.of(
                "type",
                StateValue.string("endOfConversation"),
                "conversation",
                StateValue.object(java.util.Map.of("id", StateValue.string(id)))));
        CopilotStudioActivity end = new CopilotStudioActivity(
                null,
                "endOfConversation",
                null,
                null,
                null,
                null,
                id,
                null,
                null,
                List.of(),
                List.of(),
                StateValue.nullValue(),
                java.util.Map.of(),
                raw);
        return sendActivityAsync(id, end, cursor, cancellation);
    }

    /**
     * Cancels active streams and closes only resources created by this client.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeRuns.forEach(StreamRun::cancel);
        activeRuns.clear();
        synchronized (tokenLock) {
            cachedToken = null;
            tokenRefresh = null;
        }
        if (ownsHttpClient) {
            httpClient.close();
        }
        if (ownsExecutor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    static RuntimeException normalize(Throwable failure, String kind) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof CopilotStudioException studio) {
            return studio;
        }
        if (cause instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        return new CopilotStudioException(
                "Copilot Studio " + kind + " failed.", cause, CopilotStudioException.Kind.TRANSPORT, null, null);
    }

    private Flow.Publisher<CopilotStudioEvent> stream(
            String method,
            URI uri,
            byte[] body,
            CopilotStudioCursor cursor,
            RunCancellation cancellation,
            Duration timeout) {
        Objects.requireNonNull(cancellation, "cancellation");
        StreamRun run = new StreamRun(method, uri, body, cursor, cancellation, timeout);
        SingleSubscriberPublisher<CopilotStudioEvent> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancel,
                options.limits().maxBufferedEvents(),
                ignored -> new CopilotStudioException(
                        "Copilot Studio event buffer overflow.",
                        null,
                        CopilotStudioException.Kind.LIMIT,
                        null,
                        "event_buffer"));
        run.sink = publisher;
        return publisher;
    }

    private CompletionStage<List<CopilotStudioEvent>> collect(
            Flow.Publisher<CopilotStudioEvent> publisher, RunCancellation cancellation) {
        CompletableFuture<List<CopilotStudioEvent>> result = new CompletableFuture<>();
        ArrayList<CopilotStudioEvent> events = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(CopilotStudioEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(events));
            }
        });
        RunCancellationRegistration registration = RunCancellations.register(
                cancellation, () -> result.completeExceptionally(new RunCancelledException()));
        result.whenComplete((ignored, failure) -> registration.close());
        return result.minimalCompletionStage();
    }

    private CompletionStage<CopilotStudioAccessToken> tokenAsync(RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        synchronized (tokenLock) {
            if (cachedToken != null
                    && cachedToken.expiresAt().isAfter(Instant.now().plus(options.tokenRefreshSkew()))) {
                return CompletableFuture.completedStage(cachedToken);
            }
            if (tokenRefresh != null) {
                return tokenRefresh;
            }
            CompletionStage<CopilotStudioAccessToken> requested;
            try {
                requested = Objects.requireNonNull(
                        tokenProvider.getTokenAsync(cancellation), "CopilotStudioTokenProvider returned null.");
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(authentication(failure));
            }
            CompletableFuture<CopilotStudioAccessToken> refreshed = new CompletableFuture<>();
            tokenRefresh = refreshed.minimalCompletionStage();
            requested.whenComplete((token, failure) -> {
                synchronized (tokenLock) {
                    tokenRefresh = null;
                    if (failure != null) {
                        refreshed.completeExceptionally(authentication(failure));
                    } else if (token == null
                            || !token.expiresAt().isAfter(Instant.now().plus(options.tokenRefreshSkew()))) {
                        refreshed.completeExceptionally(authentication(null));
                    } else if (cancellation.isCancellationRequested()) {
                        refreshed.completeExceptionally(new RunCancelledException());
                    } else {
                        cachedToken = token;
                        refreshed.complete(token);
                    }
                }
            });
            return refreshed.minimalCompletionStage();
        }
    }

    private URI conversationUri(String conversationId, boolean subscribe) {
        StringBuilder value = new StringBuilder(options.endpoint().toString()).append("/conversations");
        if (conversationId != null) {
            value.append('/').append(encodePathSegment(conversationId));
            if (subscribe) {
                value.append("/subscribe");
            }
        }
        value.append("?api-version=").append(CopilotStudioProtocol.API_VERSION);
        return URI.create(value.toString());
    }

    private static String encodePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '_'
                    || unsigned == '.'
                    || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append(Character.forDigit((unsigned >>> 4) & 0xf, 16));
                result.append(Character.forDigit(unsigned & 0xf, 16));
            }
        }
        return result.toString();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static CopilotStudioException authentication(Throwable cause) {
        return new CopilotStudioException(
                "Copilot Studio access token is unavailable or expires too soon.",
                cause,
                CopilotStudioException.Kind.AUTHENTICATION,
                null,
                "token_unavailable");
    }

    private static CopilotStudioException protocol(String message, String code) {
        return new CopilotStudioException(message, null, CopilotStudioException.Kind.PROTOCOL, null, code);
    }

    /** Builds {@link CopilotStudioClient} instances. */
    public static final class Builder {
        private CopilotStudioClientOptions options;

        private CopilotStudioTokenProvider tokenProvider;

        private HttpClient httpClient;

        private ExecutorService executor;

        private Builder() {}

        /** Sets required client options. */
        public Builder options(CopilotStudioClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Sets the required caller-owned token provider. */
        public Builder tokenProvider(CopilotStudioTokenProvider tokenProvider) {
            this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
            return this;
        }

        /**
         * Injects a caller-owned redirect-free JDK HTTP client.
         *
         * @param httpClient caller-owned client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Injects a caller-owned executor.
         *
         * @param executor caller-owned executor
         * @return this builder
         */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Creates the configured client.
         *
         * @return client
         */
        public CopilotStudioClient build() {
            CopilotStudioClientOptions builtOptions = Objects.requireNonNull(options, "options");
            CopilotStudioTokenProvider builtTokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
            ExecutorService builtExecutor = executor;
            boolean ownsExecutor = false;
            if (builtExecutor == null) {
                builtExecutor = Executors.newVirtualThreadPerTaskExecutor();
                ownsExecutor = true;
            }
            HttpClient builtHttpClient = httpClient;
            boolean ownsHttpClient = false;
            if (builtHttpClient == null) {
                builtHttpClient = HttpClient.newBuilder()
                        .connectTimeout(builtOptions.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .executor(builtExecutor)
                        .build();
                ownsHttpClient = true;
            } else if (builtHttpClient.followRedirects() != HttpClient.Redirect.NEVER) {
                if (ownsExecutor) {
                    builtExecutor.shutdownNow();
                }
                throw new IllegalArgumentException("Caller-owned HttpClient must disable redirects.");
            }
            return new CopilotStudioClient(
                    builtOptions, builtTokenProvider, builtHttpClient, ownsHttpClient, builtExecutor, ownsExecutor);
        }
    }

    private final class StreamRun {
        private final String method;

        private final URI uri;

        private final byte[] body;

        private final CopilotStudioCursor initialCursor;

        private final RunCancellation cancellation;

        private final Duration timeout;

        private final AtomicBoolean finished = new AtomicBoolean();

        private final AtomicBoolean permitAcquired = new AtomicBoolean();

        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final LinkedHashSet<String> activityIds = new LinkedHashSet<>();

        private String lastEventId;

        private long sequence;

        private SingleSubscriberPublisher<CopilotStudioEvent> sink;

        private StreamRun(
                String method,
                URI uri,
                byte[] body,
                CopilotStudioCursor cursor,
                RunCancellation cancellation,
                Duration timeout) {
            this.method = method;
            this.uri = uri;
            this.body = body;
            initialCursor = cursor;
            this.cancellation = cancellation;
            this.timeout = timeout;
            lastEventId = cursor.lastEventId();
            sequence = cursor.sequence();
        }

        private void start() {
            if (closed.get()) {
                fail(new IllegalStateException("CopilotStudioClient is closed."));
                return;
            }
            if (cancellation.isCancellationRequested()) {
                fail(new RunCancelledException());
                return;
            }
            if (!concurrency.tryAcquire()) {
                fail(new CopilotStudioException(
                        "Copilot Studio concurrent-request limit exceeded.",
                        null,
                        CopilotStudioException.Kind.LIMIT,
                        null,
                        "concurrency"));
                return;
            }
            permitAcquired.set(true);
            activeRuns.add(this);
            registration.set(RunCancellations.register(cancellation, this::cancel));
            tokenAsync(cancellation).whenComplete((token, tokenFailure) -> {
                if (tokenFailure != null) {
                    fail(tokenFailure);
                    return;
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + token.reveal())
                        .header("Accept", CopilotStudioProtocol.EVENT_STREAM_MEDIA_TYPE)
                        .header("User-Agent", "agent-framework-java/copilotstudio");
                if (initialCursor.lastEventId() != null) {
                    request.header("Last-Event-ID", initialCursor.lastEventId());
                }
                if ("POST".equals(method)) {
                    request.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                } else {
                    request.GET();
                }
                try {
                    httpClient
                            .sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                            .whenComplete((response, sendFailure) -> {
                                if (sendFailure != null) {
                                    fail(sendFailure);
                                    return;
                                }
                                if (response.statusCode() != 200) {
                                    closeQuietly(response.body());
                                    fail(new CopilotStudioException(
                                            "Copilot Studio returned HTTP " + response.statusCode() + ".",
                                            null,
                                            CopilotStudioException.Kind.SERVICE,
                                            response.statusCode(),
                                            "http_status"));
                                    return;
                                }
                                responseBody.set(response.body());
                                try {
                                    executor.execute(() -> read(response.body()));
                                } catch (RuntimeException failure) {
                                    fail(failure);
                                }
                            });
                } catch (RuntimeException failure) {
                    fail(failure);
                }
            });
        }

        private void read(InputStream input) {
            try (input) {
                BoundedLineReader reader =
                        new BoundedLineReader(input, options.limits().maxLineBytes());
                String eventType = null;
                String eventId = null;
                StringBuilder data = new StringBuilder();
                long responseBytes = 0;
                String line;
                while (!finished.get() && (line = reader.readLine()) != null) {
                    responseBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (!uri.getPath().endsWith("/subscribe")
                            && responseBytes > options.limits().maxResponseBytes()) {
                        throw new CopilotStudioException(
                                "Copilot Studio response exceeds the configured limit.",
                                null,
                                CopilotStudioException.Kind.LIMIT,
                                null,
                                "response_bytes");
                    }
                    if (line.isEmpty()) {
                        dispatch(eventType, eventId, data);
                        eventType = null;
                        eventId = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        continue;
                    } else if (line.startsWith("event:")) {
                        eventType = line.substring(6).strip();
                    } else if (line.startsWith("id:")) {
                        eventId = line.substring(3).strip();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).stripLeading());
                        if (data.toString().getBytes(StandardCharsets.UTF_8).length
                                > options.limits().maxEventBytes()) {
                            throw new CopilotStudioException(
                                    "Copilot Studio SSE event exceeds the configured limit.",
                                    null,
                                    CopilotStudioException.Kind.LIMIT,
                                    null,
                                    "event_bytes");
                        }
                    }
                }
                if (!data.isEmpty()) {
                    dispatch(eventType, eventId, data);
                }
                complete();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void dispatch(String eventType, String eventId, StringBuilder data) {
            if (data.isEmpty()) {
                return;
            }
            if (!"activity".equals(eventType)) {
                if ("error".equals(eventType)) {
                    throw protocol("Copilot Studio SSE reported an error event.", "sse_error");
                }
                return;
            }
            if (!acceptEventId(eventId)) {
                return;
            }
            CopilotStudioActivity activity = codec.parseActivity(data.toString().getBytes(StandardCharsets.UTF_8));
            if (activity.id() != null && !rememberActivity(activity.id())) {
                return;
            }
            lastEventId = eventId == null || eventId.isBlank() ? lastEventId : eventId;
            CopilotStudioCursor cursor = new CopilotStudioCursor(lastEventId, ++sequence);
            sink.emit(new CopilotStudioEvent(codec.classify(activity), activity, cursor));
        }

        private boolean acceptEventId(String eventId) {
            if (eventId == null || eventId.isBlank()) {
                return true;
            }
            if (eventId.equals(lastEventId)) {
                return false;
            }
            Long current = number(eventId);
            Long prior = number(lastEventId);
            return current == null || prior == null || current > prior;
        }

        private boolean rememberActivity(String activityId) {
            if (!activityIds.add(activityId)) {
                return false;
            }
            if (activityIds.size() > options.limits().maxRememberedActivityIds()) {
                String first = activityIds.iterator().next();
                activityIds.remove(first);
            }
            return true;
        }

        private void complete() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cleanup();
            sink.complete();
        }

        private void fail(Throwable failure) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cleanup();
            sink.fail(normalize(failure, "stream"));
        }

        private void cancel() {
            closeQuietly(responseBody.getAndSet(null));
            fail(new RunCancelledException());
        }

        private void cleanup() {
            activeRuns.remove(this);
            closeQuietly(responseBody.getAndSet(null));
            RunCancellationRegistration current = registration.getAndSet(null);
            if (current != null) {
                current.close();
            }
            if (permitAcquired.compareAndSet(true, false)) {
                concurrency.release();
            }
        }

        private Long number(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Cancellation and terminal state remain authoritative.
        }
    }
}
