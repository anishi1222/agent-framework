// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.HostingRunRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies, parses, dispatches, and replies to Telegram Bot API webhook updates.
 *
 * <p>The adapter supports only new {@code message} updates containing text plus numeric message,
 * chat, and user identifiers. Other valid update types are acknowledged explicitly as unsupported.
 * Principal keys are derived solely from configured bot identity and parsed numeric chat and user
 * identifiers.
 */
public final class TelegramWebhookAdapter implements AutoCloseable {
    /** Telegram's webhook secret-token header. */
    public static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final HostingDispatcher dispatcher;

    private final TelegramBotClient client;

    private final TelegramWebhookOptions options;

    private final TelegramUpdateCodec updateCodec;

    private final ScheduledThreadPoolExecutor scheduler;

    private final Set<Operation> active = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an opt-in Telegram webhook adapter.
     *
     * @param dispatcher generic hosting dispatcher
     * @param client outbound Telegram Bot API client
     * @param options strict adapter options
     */
    public TelegramWebhookAdapter(
            HostingDispatcher dispatcher, TelegramBotClient client, TelegramWebhookOptions options) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.client = Objects.requireNonNull(client, "client");
        this.options = Objects.requireNonNull(options, "options");
        updateCodec = new TelegramUpdateCodec(options);
        scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("agent-framework-telegram-webhook-timeouts")
                        .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * Handles one complete Telegram webhook request.
     *
     * @param request bounded HTTP-neutral webhook request
     * @return webhook response stage
     */
    public CompletionStage<TelegramWebhookResponse> handleAsync(TelegramWebhookRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return completed(rejected(503, null, TelegramWebhookErrorCode.CLOSED));
        }
        if (!"POST".equals(request.method())) {
            return completed(rejected(405, null, TelegramWebhookErrorCode.METHOD_NOT_ALLOWED));
        }
        byte[] body = request.body();
        if (body.length > options.maxUpdateBytes()) {
            return completed(rejected(413, null, TelegramWebhookErrorCode.PAYLOAD_TOO_LARGE));
        }
        if (!verifySecret(request)) {
            return completed(rejected(401, null, TelegramWebhookErrorCode.UNAUTHENTICATED));
        }
        if (!isJson(request)) {
            return completed(rejected(415, null, TelegramWebhookErrorCode.UNSUPPORTED_MEDIA_TYPE));
        }

        TelegramUpdateParseResult parsed;
        try {
            parsed = updateCodec.decode(body);
        } catch (TelegramUpdateException exception) {
            TelegramWebhookErrorCode code = exception.error() == TelegramUpdateError.MALFORMED
                    ? TelegramWebhookErrorCode.MALFORMED_UPDATE
                    : TelegramWebhookErrorCode.INVALID_UPDATE;
            return completed(rejected(code == TelegramWebhookErrorCode.MALFORMED_UPDATE ? 400 : 422, null, code));
        }
        if (!parsed.supported()) {
            return completed(new TelegramWebhookResponse(
                    204, TelegramWebhookDisposition.UNSUPPORTED, parsed.updateId(), null, null));
        }

        Operation operation = new Operation(request, parsed.message().updateId());
        active.add(operation);
        if (closed.get()) {
            operation.reject(503, TelegramWebhookErrorCode.CLOSED);
            return operation.result();
        }
        operation.initialize();
        if (!operation.isDone()) {
            dispatch(operation, parsed.message());
        }
        return operation.result();
    }

    /** Cancels active webhook processing and releases the timeout scheduler. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Operation operation : Set.copyOf(active)) {
            operation.reject(503, TelegramWebhookErrorCode.CLOSED);
        }
        scheduler.shutdownNow();
    }

    private void dispatch(Operation operation, TelegramInboundMessage inbound) {
        HostingRequestContext context = requestContext(inbound, operation.cancellation);
        HostingRunRequest request = runRequest(inbound);
        try {
            CompletionStage<TelegramSendMessageResult> pipeline = options.streaming()
                    ? streaming(context, request, inbound.chatId(), operation)
                    : finite(context, request, inbound.chatId(), operation);
            pipeline.whenComplete((sent, failure) -> {
                if (failure == null && sent != null) {
                    operation.complete(sent);
                } else if (failure == null) {
                    operation.fail(new TelegramBotException(TelegramBotErrorCode.INVALID_RESPONSE, null, null));
                } else {
                    operation.fail(unwrap(failure));
                }
            });
        } catch (Throwable failure) {
            operation.fail(failure);
        }
    }

    private CompletionStage<TelegramSendMessageResult> finite(
            HostingRequestContext context, HostingRunRequest request, long chatId, Operation operation) {
        return dispatcher
                .runAsync(context, HostingRouteKind.AGENT, options.routeId(), request)
                .thenCompose(outcome -> send(
                        new TelegramSendMessageRequest(
                                chatId, TelegramResponseText.finite(outcome, options.maxOutboundTextLength())),
                        operation));
    }

    private CompletionStage<TelegramSendMessageResult> streaming(
            HostingRequestContext context, HostingRunRequest request, long chatId, Operation operation) {
        return dispatcher
                .startStreamingAsync(context, HostingRouteKind.AGENT, options.routeId(), request)
                .thenCompose(run -> collectStreaming(run, operation))
                .thenCompose(text -> send(new TelegramSendMessageRequest(chatId, text), operation));
    }

    private CompletionStage<TelegramSendMessageResult> send(TelegramSendMessageRequest request, Operation operation) {
        if (operation.isDone() || operation.cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        CompletionStage<TelegramSendMessageResult> stage = client.sendMessageAsync(request, operation.cancellation);
        return stage == null
                ? CompletableFuture.failedFuture(
                        new TelegramBotException(TelegramBotErrorCode.TRANSPORT_ERROR, null, null))
                : stage;
    }

    private CompletionStage<String> collectStreaming(HostingRun run, Operation operation) {
        try {
            StreamingCollector collector = new StreamingCollector(run, operation);
            run.events().subscribe(collector);
            return collector.result();
        } catch (RuntimeException failure) {
            run.cancel();
            return CompletableFuture.failedFuture(failure);
        }
    }

    private HostingRequestContext requestContext(TelegramInboundMessage inbound, DefaultRunCancellation cancellation) {
        String principalId = "telegram:bot:" + options.botId() + ":user:" + inbound.userId();
        String isolationId =
                "telegram:bot:" + options.botId() + ":chat:" + inbound.chatId() + ":user:" + inbound.userId();
        HostingPrincipal principal = new HostingPrincipal(
                principalId,
                isolationId,
                Map.of(
                        "telegram.botId", Long.toString(options.botId()),
                        "telegram.chatId", Long.toString(inbound.chatId()),
                        "telegram.userId", Long.toString(inbound.userId()),
                        "telegram.chatType", inbound.chatType()));
        Map<String, StateValue> metadata = Map.of(
                "telegram.updateId", StateValue.integer(inbound.updateId()),
                "telegram.messageId", StateValue.integer(inbound.messageId()),
                "telegram.botId", StateValue.integer(options.botId()),
                "telegram.chatId", StateValue.integer(inbound.chatId()),
                "telegram.userId", StateValue.integer(inbound.userId()),
                "telegram.chatType", StateValue.string(inbound.chatType()));
        return new HostingRequestContext(
                "telegram-bot-" + options.botId() + "-update-" + inbound.updateId(),
                "telegram-bot-" + options.botId() + "-chat-" + inbound.chatId() + "-message-" + inbound.messageId(),
                principal,
                Map.of(),
                metadata,
                cancellation);
    }

    private HostingRunRequest runRequest(TelegramInboundMessage inbound) {
        Map<String, StateValue> metadata = Map.of(
                "telegram.updateId", StateValue.integer(inbound.updateId()),
                "telegram.messageId", StateValue.integer(inbound.messageId()),
                "telegram.botId", StateValue.integer(options.botId()),
                "telegram.chatId", StateValue.integer(inbound.chatId()),
                "telegram.userId", StateValue.integer(inbound.userId()));
        Message message = Message.builder(Role.USER)
                .contents(List.of(new com.microsoft.agents.core.TextContent(inbound.text())))
                .messageId("telegram:" + options.botId() + ":" + inbound.chatId() + ":" + inbound.messageId())
                .metadata(metadata)
                .build();
        return new HostingRunRequest(List.of(message), null, RunOptions.empty(), metadata);
    }

    private boolean verifySecret(TelegramWebhookRequest request) {
        List<String> values = request.headerValues(SECRET_TOKEN_HEADER);
        if (values.size() != 1) {
            return false;
        }
        String supplied = values.getFirst();
        if (supplied == null || supplied.length() > 256) {
            return false;
        }
        byte[] expected = options.webhookSecretToken().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean isJson(TelegramWebhookRequest request) {
        List<String> values = request.headerValues("content-type");
        if (values.size() != 1) {
            return false;
        }
        String[] parts = values.getFirst().split(";", -1);
        if (!"application/json".equals(parts[0].trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean charsetSeen = false;
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim().toLowerCase(Locale.ROOT);
            if (parameter.isEmpty()) {
                return false;
            }
            if (parameter.equals("charset=utf-8") || parameter.equals("charset=\"utf-8\"")) {
                if (charsetSeen) {
                    return false;
                }
                charsetSeen = true;
            } else {
                return false;
            }
        }
        return true;
    }

    private static CompletableFuture<TelegramWebhookResponse> completed(TelegramWebhookResponse response) {
        return CompletableFuture.completedFuture(response);
    }

    private static TelegramWebhookResponse rejected(int status, Long updateId, TelegramWebhookErrorCode code) {
        return new TelegramWebhookResponse(
                status,
                code == TelegramWebhookErrorCode.CANCELLED
                        ? TelegramWebhookDisposition.CANCELLED
                        : TelegramWebhookDisposition.REJECTED,
                updateId,
                null,
                code);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class Operation {
        private final long updateId;

        private final com.microsoft.agents.core.RunCancellation requestCancellation;

        private final DefaultRunCancellation cancellation = new DefaultRunCancellation();

        private final CompletableFuture<TelegramWebhookResponse> response = new CompletableFuture<>();

        private final AtomicReference<RunCancellationRegistration> requestRegistration = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        private final AtomicBoolean terminal = new AtomicBoolean();

        private final AtomicBoolean cleaned = new AtomicBoolean();

        private Operation(TelegramWebhookRequest request, long updateId) {
            requestCancellation = request.cancellation();
            this.updateId = updateId;
        }

        private void initialize() {
            RunCancellationRegistration registration = RunCancellations.register(
                    requestCancellation, () -> reject(499, TelegramWebhookErrorCode.CANCELLED));
            requestRegistration.set(registration);
            if (terminal.get()) {
                registration.close();
                requestRegistration.compareAndSet(registration, null);
                return;
            }
            ScheduledFuture<?> timeoutTask;
            try {
                timeoutTask = scheduler.schedule(
                        () -> reject(504, TelegramWebhookErrorCode.TIMEOUT),
                        options.processingTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                reject(503, TelegramWebhookErrorCode.CLOSED);
                return;
            }
            timeout.set(timeoutTask);
            if (terminal.get() && timeout.compareAndSet(timeoutTask, null)) {
                timeoutTask.cancel(false);
            }
        }

        private CompletionStage<TelegramWebhookResponse> result() {
            return response.minimalCompletionStage();
        }

        private boolean isDone() {
            return terminal.get();
        }

        private void complete(TelegramSendMessageResult result) {
            Objects.requireNonNull(result, "result");
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            response.complete(new TelegramWebhookResponse(
                    200, TelegramWebhookDisposition.PROCESSED, updateId, result.messageId(), null));
            cleanup();
        }

        private void reject(int status, TelegramWebhookErrorCode code) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancellation.cancel();
            response.complete(rejected(status, updateId, code));
            cleanup();
        }

        private void fail(Throwable failure) {
            if (isDone()) {
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof TelegramDispatchException dispatchFailure) {
                HostingError error = dispatchFailure.error();
                reject(error == null ? 500 : error.code().httpStatus(), TelegramWebhookErrorCode.DISPATCH_FAILED);
            } else if (cause instanceof HostingException hostingFailure) {
                reject(hostingFailure.error().code().httpStatus(), TelegramWebhookErrorCode.DISPATCH_FAILED);
            } else if (cause instanceof TelegramBotException botFailure) {
                if (botFailure.code() == TelegramBotErrorCode.TIMEOUT) {
                    reject(504, TelegramWebhookErrorCode.TIMEOUT);
                } else if (botFailure.code() == TelegramBotErrorCode.CANCELLED) {
                    reject(499, TelegramWebhookErrorCode.CANCELLED);
                } else {
                    reject(502, TelegramWebhookErrorCode.OUTBOUND_FAILED);
                }
            } else if (cause instanceof RunCancelledException) {
                reject(499, TelegramWebhookErrorCode.CANCELLED);
            } else {
                reject(500, TelegramWebhookErrorCode.DISPATCH_FAILED);
            }
        }

        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            RunCancellationRegistration registration = requestRegistration.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
            ScheduledFuture<?> timeoutTask = timeout.getAndSet(null);
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            active.remove(this);
        }
    }

    private final class StreamingCollector implements Flow.Subscriber<HostingEvent> {
        private final HostingRun run;

        private final Operation operation;

        private final TelegramResponseText.BoundedText text =
                new TelegramResponseText.BoundedText(options.maxOutboundTextLength());

        private final AtomicInteger events = new AtomicInteger();

        private final CompletableFuture<String> result = new CompletableFuture<>();

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>();

        private final AtomicBoolean terminal = new AtomicBoolean();

        private StreamingCollector(HostingRun run, Operation operation) {
            this.run = Objects.requireNonNull(run, "run");
            this.operation = Objects.requireNonNull(operation, "operation");
            cancellationRegistration.set(RunCancellations.register(operation.cancellation, this::cancel));
        }

        private CompletionStage<String> result() {
            result.whenComplete((ignored, failure) -> {
                RunCancellationRegistration registration = cancellationRegistration.getAndSet(null);
                if (registration != null) {
                    registration.close();
                }
            });
            return result.minimalCompletionStage();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            Objects.requireNonNull(value, "value");
            if (!subscription.compareAndSet(null, value)) {
                value.cancel();
                return;
            }
            if (terminal.get() || operation.cancellation.isCancellationRequested()) {
                value.cancel();
                cancel();
                return;
            }
            value.request(1);
        }

        @Override
        public void onNext(HostingEvent item) {
            if (terminal.get()) {
                return;
            }
            if (events.incrementAndGet() > options.maxStreamingEvents()) {
                run.cancel();
                fail(new TelegramDispatchException(HostingError.of(
                        HostingErrorCode.OVERFLOW, "Telegram streaming aggregation exceeded maxStreamingEvents.")));
                return;
            }
            try {
                TelegramResponseText.appendStreaming(text, item);
            } catch (RuntimeException failure) {
                run.cancel();
                fail(failure);
                return;
            }
            Flow.Subscription current = subscription.get();
            if (current != null) {
                current.request(1);
            }
        }

        @Override
        public void onError(Throwable failure) {
            fail(failure);
        }

        @Override
        public void onComplete() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            run.terminalAsync().whenComplete((outcome, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(unwrap(failure));
                } else if (outcome == null || outcome.status() != HostingOutcomeStatus.COMPLETED) {
                    result.completeExceptionally(
                            new TelegramDispatchException(outcome == null ? null : outcome.error()));
                } else {
                    result.complete(text.finish());
                }
            });
        }

        private void cancel() {
            run.cancel();
            fail(new RunCancelledException());
        }

        private void fail(Throwable failure) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            Flow.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
            result.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        }
    }
}
