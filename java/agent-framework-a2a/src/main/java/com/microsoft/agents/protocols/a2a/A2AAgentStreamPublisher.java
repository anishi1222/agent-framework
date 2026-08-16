// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class A2AAgentStreamPublisher implements Flow.Publisher<AgentResponseUpdate> {
    private final Flow.Publisher<A2AStreamEvent> source;

    private final A2AAgentOptions options;

    private final A2AJsonCodec codec;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    A2AAgentStreamPublisher(Flow.Publisher<A2AStreamEvent> source, A2AAgentOptions options, A2AJsonCodec codec) {
        this.source = Objects.requireNonNull(source, "source");
        this.options = Objects.requireNonNull(options, "options");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super AgentResponseUpdate> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("A2A agent stream permits one subscriber."));
            return;
        }
        source.subscribe(new MappingSubscriber(subscriber));
    }

    private final class MappingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final Flow.Subscriber<? super AgentResponseUpdate> target;

        private final AtomicLong sequence = new AtomicLong();

        private Flow.Subscription subscription;

        private boolean terminal;

        private MappingSubscriber(Flow.Subscriber<? super AgentResponseUpdate> target) {
            this.target = target;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            target.onSubscribe(value);
        }

        @Override
        public void onNext(A2AStreamEvent event) {
            if (terminal) {
                return;
            }
            try {
                target.onNext(map(event, sequence.getAndIncrement()));
            } catch (Throwable failure) {
                terminal = true;
                subscription.cancel();
                target.onError(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            if (!terminal) {
                terminal = true;
                target.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!terminal) {
                terminal = true;
                target.onComplete();
            }
        }
    }

    private AgentResponseUpdate map(A2AStreamEvent event, long sequence) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                .sequence(sequence)
                .agentId(options.metadata().id());
        switch (event) {
            case Message message -> {
                com.microsoft.agents.core.Message mapped =
                        A2AContentConverter.toFrameworkMessage(message, options.outputModes(), codec);
                builder.contents(mapped.contents())
                        .role(mapped.role())
                        .messageId(mapped.messageId())
                        .responseId(message.messageId())
                        .finishReason(FinishReason.STOP)
                        .metadata(message.metadata());
            }
            case Task task -> mapTask(builder, task);
            case TaskArtifactUpdateEvent update -> {
                com.microsoft.agents.core.Message mapped =
                        A2AContentConverter.artifactToFrameworkMessage(update.artifact(), options.outputModes(), codec);
                LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(update.metadata());
                metadata.put("a2a.append", StateValue.bool(update.append()));
                metadata.put("a2a.lastChunk", StateValue.bool(update.lastChunk()));
                metadata.put(
                        "a2a.artifactId", StateValue.string(update.artifact().artifactId()));
                builder.contents(mapped.contents())
                        .role(com.microsoft.agents.core.Role.ASSISTANT)
                        .responseId(update.taskId())
                        .metadata(metadata);
            }
            case TaskStatusUpdateEvent update -> {
                ArrayList<Content> contents = new ArrayList<>();
                if (update.status().message() != null) {
                    contents.addAll(A2AContentConverter.toFrameworkMessage(
                                    update.status().message(), options.outputModes(), codec)
                            .contents());
                }
                builder.contents(contents)
                        .responseId(update.taskId())
                        .metadata(statusMetadata(
                                update.taskId(),
                                update.contextId(),
                                update.status().state(),
                                update.metadata()));
                if (update.status().timestamp() != null) {
                    builder.createdAt(update.status().timestamp());
                }
                applyBoundary(
                        builder,
                        update.taskId(),
                        update.contextId(),
                        update.status().state());
            }
        }
        return builder.build();
    }

    private void mapTask(AgentResponseUpdate.Builder builder, Task task) {
        List<com.microsoft.agents.core.Message> messages =
                A2AContentConverter.toFrameworkMessages(task, options.outputModes(), codec);
        ArrayList<Content> contents = new ArrayList<>();
        messages.forEach(message -> contents.addAll(message.contents()));
        builder.contents(contents)
                .role(com.microsoft.agents.core.Role.ASSISTANT)
                .responseId(task.id())
                .metadata(statusMetadata(
                        task.id(), task.contextId(), task.status().state(), task.metadata()));
        if (task.status().timestamp() != null) {
            builder.createdAt(task.status().timestamp());
        }
        applyBoundary(builder, task.id(), task.contextId(), task.status().state());
    }

    private static void applyBoundary(
            AgentResponseUpdate.Builder builder, String taskId, String contextId, TaskState state) {
        if (contextId == null) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_AGENT_RESPONSE,
                    "Remote task cannot be adapted as an Agent continuation without contextId.");
        }
        switch (state) {
            case TASK_STATE_COMPLETED ->
                builder.finishReason(FinishReason.STOP)
                        .continuationToken(new A2AContinuation(taskId, contextId, state).toStateValue());
            case TASK_STATE_FAILED, TASK_STATE_CANCELED, TASK_STATE_REJECTED ->
                throw new A2AProtocolException(
                        A2AErrorCode.INVALID_AGENT_RESPONSE, "Remote A2A stream ended in " + state + ".");
            case TASK_STATE_SUBMITTED, TASK_STATE_WORKING, TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED ->
                builder.continuationToken(new A2AContinuation(taskId, contextId, state).toStateValue());
            case TASK_STATE_UNSPECIFIED ->
                throw new A2AProtocolException(
                        A2AErrorCode.INVALID_AGENT_RESPONSE, "Remote A2A task state is unspecified.");
        }
    }

    private static Map<String, StateValue> statusMetadata(
            String taskId, String contextId, TaskState state, Map<String, StateValue> metadata) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>(metadata);
        values.put("a2a.taskId", StateValue.string(taskId));
        if (contextId != null) {
            values.put("a2a.contextId", StateValue.string(contextId));
        }
        values.put("a2a.taskState", StateValue.string(state.name()));
        return Map.copyOf(values);
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
