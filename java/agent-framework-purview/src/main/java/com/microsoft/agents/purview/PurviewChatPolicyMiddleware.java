// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import com.microsoft.agents.agents.ChatMiddleware;
import com.microsoft.agents.agents.ChatMiddlewareContext;
import com.microsoft.agents.agents.ChatMiddlewareNext;
import com.microsoft.agents.agents.ChatStreamingMiddlewareNext;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Enforces Purview ingress and finite egress policy checks at the chat-client boundary. */
public final class PurviewChatPolicyMiddleware implements ChatMiddleware, AutoCloseable {
    private final PurviewSettings settings;
    private final PurviewClient client;
    private final PurviewPolicyEvaluator evaluator;
    private final boolean ownsResources;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates chat middleware that owns its Purview client and evaluator. */
    public PurviewChatPolicyMiddleware(PurviewSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        client = new PurviewClient(settings);
        evaluator = new PurviewPolicyEvaluator(client, settings);
        ownsResources = true;
    }

    /** Creates chat middleware over a caller-owned evaluator. */
    public PurviewChatPolicyMiddleware(PurviewSettings settings, PurviewPolicyEvaluator evaluator) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
        client = null;
        ownsResources = false;
    }

    @Override
    public CompletionStage<ChatResponse> invokeAsync(ChatMiddlewareContext context, ChatMiddlewareNext next) {
        ensureOpen();
        String conversation = context.request().options().conversationId();
        return evaluateSafely(
                        context.request().messages(),
                        PurviewActivity.UPLOAD_TEXT,
                        conversation,
                        null,
                        context.cancellation())
                .thenCompose(pre -> {
                    if (pre != null && pre.decision().blocked()) {
                        return CompletableFuture.completedStage(blocked(settings.blockedPromptMessage(), conversation));
                    }
                    CompletionStage<ChatResponse> upstream;
                    try {
                        upstream = next.invokeAsync(context);
                    } catch (RuntimeException failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    String resolvedUser = pre == null ? null : pre.userId();
                    return upstream.thenCompose(response -> evaluateSafely(
                                    response.messages(),
                                    PurviewActivity.DOWNLOAD_TEXT,
                                    response.conversationId() == null ? conversation : response.conversationId(),
                                    resolvedUser,
                                    context.cancellation())
                            .thenApply(post -> post != null && post.decision().blocked()
                                    ? blocked(settings.blockedResponseMessage(), response.conversationId())
                                    : response));
                });
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            ChatMiddlewareContext context, ChatStreamingMiddlewareNext next) {
        ensureOpen();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<ChatResponseUpdate>> sinkRef = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        SingleSubscriberPublisher<ChatResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> evaluateSafely(
                                context.request().messages(),
                                PurviewActivity.UPLOAD_TEXT,
                                context.request().options().conversationId(),
                                null,
                                context.cancellation())
                        .whenComplete((pre, failure) -> {
                            if (failure != null) {
                                if (finished.compareAndSet(false, true)) {
                                    sinkRef.get().fail(unwrap(failure));
                                }
                                return;
                            }
                            if (pre != null && pre.decision().blocked()) {
                                sinkRef.get()
                                        .emit(ChatResponseUpdate.builder()
                                                .sequence(0)
                                                .role(Role.SYSTEM)
                                                .contents(List.of(new TextContent(settings.blockedPromptMessage())))
                                                .conversationId(context.request()
                                                        .options()
                                                        .conversationId())
                                                .finishReason(FinishReason.CONTENT_FILTER)
                                                .metadata(Map.of("purview.blocked", StateValue.bool(true)))
                                                .build());
                                if (finished.compareAndSet(false, true)) {
                                    sinkRef.get().complete();
                                }
                                return;
                            }
                            Flow.Publisher<ChatResponseUpdate> publisher;
                            try {
                                publisher = next.invokeStreaming(context);
                            } catch (RuntimeException startFailure) {
                                if (finished.compareAndSet(false, true)) {
                                    sinkRef.get().fail(startFailure);
                                }
                                return;
                            }
                            publisher.subscribe(new Flow.Subscriber<>() {
                                @Override
                                public void onSubscribe(Flow.Subscription subscription) {
                                    if (!upstream.compareAndSet(null, subscription)) {
                                        subscription.cancel();
                                        return;
                                    }
                                    subscription.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(ChatResponseUpdate item) {
                                    sinkRef.get().emit(item);
                                }

                                @Override
                                public void onError(Throwable throwable) {
                                    if (finished.compareAndSet(false, true)) {
                                        sinkRef.get().fail(throwable);
                                    }
                                }

                                @Override
                                public void onComplete() {
                                    if (finished.compareAndSet(false, true)) {
                                        sinkRef.get().complete();
                                    }
                                }
                            });
                        }),
                () -> {
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    context.cancellation().cancel();
                },
                256);
        sinkRef.set(sink);
        return sink;
    }

    /** Closes only resources created by this middleware. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || !ownsResources) {
            return;
        }
        try {
            evaluator.close();
        } finally {
            client.close();
        }
    }

    private CompletionStage<PurviewEvaluationOutcome> evaluateSafely(
            List<Message> messages,
            PurviewActivity activity,
            String conversationId,
            String userId,
            com.microsoft.agents.core.RunCancellation cancellation) {
        CompletionStage<PurviewEvaluationOutcome> stage;
        try {
            stage = evaluator.evaluateAsync(messages, activity, conversationId, userId, cancellation);
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        return stage.handle((outcome, failure) -> {
            if (failure == null) {
                return outcome;
            }
            Throwable cause = unwrap(failure);
            boolean paymentOpen = cause instanceof PurviewException purview
                    && purview.kind() == PurviewException.Kind.PAYMENT_REQUIRED
                    && settings.paymentRequiredMode() == PurviewFailureMode.FAIL_OPEN;
            if (paymentOpen || settings.failureMode() == PurviewFailureMode.FAIL_OPEN) {
                return null;
            }
            throw new java.util.concurrent.CompletionException(cause);
        });
    }

    private static ChatResponse blocked(String message, String conversationId) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.SYSTEM, message)))
                .conversationId(conversationId)
                .finishReason(FinishReason.CONTENT_FILTER)
                .metadata(Map.of("purview.blocked", StateValue.bool(true)))
                .build();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PurviewChatPolicyMiddleware is closed.");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
