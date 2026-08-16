// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import com.microsoft.agents.agents.AgentMiddleware;
import com.microsoft.agents.agents.AgentMiddlewareContext;
import com.microsoft.agents.agents.AgentMiddlewareNext;
import com.microsoft.agents.agents.AgentStreamingMiddlewareNext;
import com.microsoft.agents.agents.AgentStreamingResult;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
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

/**
 * Enforces Purview policies before and after finite agent execution.
 *
 * <p>Streaming runs enforce the prompt before subscription but do not post-evaluate partial output;
 * applications requiring egress enforcement must use finite runs or buffer the full stream at a
 * trusted application boundary.
 *
 * @param <T> structured agent result type
 */
public final class PurviewPolicyMiddleware<T> implements AgentMiddleware<T>, AutoCloseable {
    private final PurviewSettings settings;
    private final PurviewClient client;
    private final PurviewPolicyEvaluator evaluator;
    private final boolean ownsResources;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates middleware that owns its Purview client and evaluator. */
    public PurviewPolicyMiddleware(PurviewSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        client = new PurviewClient(settings);
        evaluator = new PurviewPolicyEvaluator(client, settings);
        ownsResources = true;
    }

    /** Creates middleware over a caller-owned evaluator. */
    public PurviewPolicyMiddleware(PurviewSettings settings, PurviewPolicyEvaluator evaluator) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
        client = null;
        ownsResources = false;
    }

    @Override
    public CompletionStage<AgentResponse<T>> invokeAsync(
            AgentMiddlewareContext<T> context, AgentMiddlewareNext<T> next) {
        ensureOpen();
        String sessionId = sessionId(context);
        String explicitUser = explicitUser(context);
        return evaluateSafely(
                        context.runContext().inputMessages(),
                        PurviewActivity.UPLOAD_TEXT,
                        sessionId,
                        explicitUser,
                        context.runContext().cancellation())
                .thenCompose(pre -> {
                    if (pre != null && pre.decision().blocked()) {
                        return CompletableFuture.completedStage(blocked(context, settings.blockedPromptMessage()));
                    }
                    CompletionStage<AgentResponse<T>> upstream;
                    try {
                        upstream = next.invokeAsync(context);
                    } catch (RuntimeException failure) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    String resolvedUser = pre == null ? explicitUser : pre.userId();
                    return upstream.thenCompose(response -> evaluateSafely(
                                    response.messages(),
                                    PurviewActivity.DOWNLOAD_TEXT,
                                    sessionId,
                                    resolvedUser,
                                    context.runContext().cancellation())
                            .thenApply(post -> post != null && post.decision().blocked()
                                    ? blocked(context, settings.blockedResponseMessage())
                                    : response));
                });
    }

    @Override
    public AgentStreamingResult<T> invokeStreaming(
            AgentMiddlewareContext<T> context, AgentStreamingMiddlewareNext<T> next) {
        ensureOpen();
        CompletableFuture<AgentResponse<T>> terminal = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> upstreamSubscription = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkRef = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> evaluateSafely(
                                context.runContext().inputMessages(),
                                PurviewActivity.UPLOAD_TEXT,
                                sessionId(context),
                                explicitUser(context),
                                context.runContext().cancellation())
                        .whenComplete((pre, failure) -> {
                            if (failure != null) {
                                fail(sinkRef.get(), terminal, finished, unwrap(failure));
                                return;
                            }
                            if (pre != null && pre.decision().blocked()) {
                                AgentResponse<T> blocked = blocked(context, settings.blockedPromptMessage());
                                sinkRef.get()
                                        .emit(AgentResponseUpdate.builder()
                                                .sequence(0)
                                                .role(Role.SYSTEM)
                                                .agentId(context.agent().id())
                                                .contents(List.of(new TextContent(settings.blockedPromptMessage())))
                                                .finishReason(FinishReason.CONTENT_FILTER)
                                                .metadata(Map.of("purview.blocked", StateValue.bool(true)))
                                                .build());
                                if (finished.compareAndSet(false, true)) {
                                    terminal.complete(blocked);
                                    sinkRef.get().complete();
                                }
                                return;
                            }
                            AgentStreamingResult<T> upstream;
                            try {
                                upstream = next.invokeStreaming(context);
                            } catch (RuntimeException startFailure) {
                                fail(sinkRef.get(), terminal, finished, startFailure);
                                return;
                            }
                            upstream.resultAsync().whenComplete((response, resultFailure) -> {
                                if (resultFailure != null) {
                                    fail(sinkRef.get(), terminal, finished, unwrap(resultFailure));
                                } else {
                                    terminal.complete(response);
                                }
                            });
                            upstream.updates().subscribe(new Flow.Subscriber<>() {
                                @Override
                                public void onSubscribe(Flow.Subscription subscription) {
                                    if (!upstreamSubscription.compareAndSet(null, subscription)) {
                                        subscription.cancel();
                                        return;
                                    }
                                    subscription.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(AgentResponseUpdate item) {
                                    sinkRef.get().emit(item);
                                }

                                @Override
                                public void onError(Throwable throwable) {
                                    fail(sinkRef.get(), terminal, finished, throwable);
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
                    Flow.Subscription subscription = upstreamSubscription.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    context.runContext().cancellation().cancel();
                },
                256);
        sinkRef.set(sink);
        return new AgentStreamingResult<>(sink, terminal.minimalCompletionStage());
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
            String sessionId,
            String explicitUser,
            com.microsoft.agents.core.RunCancellation cancellation) {
        CompletionStage<PurviewEvaluationOutcome> evaluation;
        try {
            evaluation = evaluator.evaluateAsync(messages, activity, sessionId, explicitUser, cancellation);
        } catch (RuntimeException failure) {
            evaluation = CompletableFuture.failedFuture(failure);
        }
        return evaluation.handle((outcome, failure) -> {
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

    private AgentResponse<T> blocked(AgentMiddlewareContext<T> context, String message) {
        return AgentResponse.<T>builder()
                .messages(List.of(Message.text(Role.SYSTEM, message)))
                .agentId(context.agent().id())
                .finishReason(FinishReason.CONTENT_FILTER)
                .metadata(Map.of("purview.blocked", StateValue.bool(true)))
                .build();
    }

    private static String sessionId(AgentMiddlewareContext<?> context) {
        if (context.runContext().session() != null) {
            return context.runContext().session().sessionId();
        }
        StateValue value = context.runContext().metadata().get("conversationId");
        return value instanceof StateValue.StringValue string
                ? string.value()
                : context.runContext().runId();
    }

    private static String explicitUser(AgentMiddlewareContext<?> context) {
        StateValue value = context.runContext().metadata().get("purview.userId");
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PurviewPolicyMiddleware is closed.");
        }
    }

    private static void fail(
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            CompletableFuture<?> terminal,
            AtomicBoolean finished,
            Throwable failure) {
        terminal.completeExceptionally(failure);
        if (finished.compareAndSet(false, true)) {
            sink.fail(failure);
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
