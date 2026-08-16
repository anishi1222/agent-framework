// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the public provider-neutral contract for an executable agent.
 *
 * <p>When an implementation uses a caller-owned bounded executor, do not invoke a synchronous
 * {@code run} method from a task on that same executor while it is saturated. The blocked caller can
 * occupy the worker needed to start or complete the run. Use an asynchronous method, reserve executor
 * capacity, or call synchronously from a different thread.
 *
 * @param <T> optional structured response value type
 */
public interface Agent<T> extends AutoCloseable {
    /**
     * Returns immutable agent metadata.
     *
     * @return agent metadata
     */
    AgentMetadata metadata();

    /**
     * Returns the stable agent identifier.
     *
     * @return agent identifier
     */
    default String id() {
        return metadata().id();
    }

    /**
     * Returns the optional agent display name.
     *
     * @return display name, or {@code null}
     */
    default String name() {
        return metadata().name();
    }

    /**
     * Returns the optional agent description.
     *
     * @return description, or {@code null}
     */
    default String description() {
        return metadata().description();
    }

    /**
     * Starts the canonical finite execution path.
     *
     * @param messages ordered input messages
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return run handle
     */
    RunHandle<AgentResponse<T>> startRun(List<Message> messages, RunOptions options, RunCancellation cancellation);

    /**
     * Starts the canonical streaming execution path.
     *
     * @param messages ordered input messages
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return cold, single-subscriber update publisher
     */
    Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation);

    /**
     * Runs text asynchronously.
     *
     * @param input user text
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(String input) {
        return runAsync(userMessage(input));
    }

    /**
     * Runs text asynchronously with options.
     *
     * @param input user text
     * @param options run options
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(String input, RunOptions options) {
        return runAsync(userMessage(input), options);
    }

    /**
     * Runs text asynchronously with caller-owned cancellation.
     *
     * @param input user text
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(String input, RunOptions options, RunCancellation cancellation) {
        return runAsync(userMessage(input), options, cancellation);
    }

    /**
     * Runs one message asynchronously.
     *
     * @param message input message
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(Message message) {
        return runAsync(List.of(requireMessage(message)), RunOptions.empty());
    }

    /**
     * Runs one message asynchronously with options.
     *
     * @param message input message
     * @param options run options
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(Message message, RunOptions options) {
        return runAsync(List.of(requireMessage(message)), options);
    }

    /**
     * Runs one message asynchronously with caller-owned cancellation.
     *
     * @param message input message
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(
            Message message, RunOptions options, RunCancellation cancellation) {
        return runAsync(List.of(requireMessage(message)), options, cancellation);
    }

    /**
     * Runs ordered messages asynchronously.
     *
     * @param messages ordered input messages
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(List<Message> messages) {
        return runAsync(messages, RunOptions.empty());
    }

    /**
     * Runs ordered messages asynchronously with options.
     *
     * @param messages ordered input messages
     * @param options run options
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(List<Message> messages, RunOptions options) {
        return startRun(messages, options).resultAsync();
    }

    /**
     * Runs ordered messages asynchronously with caller-owned cancellation.
     *
     * @param messages ordered input messages
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> runAsync(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return startRun(messages, options, cancellation).resultAsync();
    }

    /**
     * Streams text.
     *
     * @param input user text
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(String input) {
        return runStreaming(userMessage(input));
    }

    /**
     * Streams text with options.
     *
     * @param input user text
     * @param options run options
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(String input, RunOptions options) {
        return runStreaming(userMessage(input), options);
    }

    /**
     * Streams text with caller-owned cancellation.
     *
     * @param input user text
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(
            String input, RunOptions options, RunCancellation cancellation) {
        return runStreaming(userMessage(input), options, cancellation);
    }

    /**
     * Streams one message.
     *
     * @param message input message
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(Message message) {
        return runStreaming(List.of(requireMessage(message)), RunOptions.empty());
    }

    /**
     * Streams one message with options.
     *
     * @param message input message
     * @param options run options
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(Message message, RunOptions options) {
        return runStreaming(List.of(requireMessage(message)), options);
    }

    /**
     * Streams one message with caller-owned cancellation.
     *
     * @param message input message
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(
            Message message, RunOptions options, RunCancellation cancellation) {
        return runStreaming(List.of(requireMessage(message)), options, cancellation);
    }

    /**
     * Streams ordered messages.
     *
     * @param messages ordered input messages
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(List<Message> messages) {
        return runStreaming(messages, RunOptions.empty());
    }

    /**
     * Streams ordered messages with options.
     *
     * @param messages ordered input messages
     * @param options run options
     * @return update publisher
     */
    default Flow.Publisher<AgentResponseUpdate> runStreaming(List<Message> messages, RunOptions options) {
        return runStreaming(messages, options, new DefaultRunCancellation());
    }

    /**
     * Runs text synchronously.
     *
     * @param input user text
     * @return terminal response
     */
    default AgentResponse<T> run(String input) {
        return run(userMessage(input));
    }

    /**
     * Runs text synchronously with options.
     *
     * @param input user text
     * @param options run options
     * @return terminal response
     */
    default AgentResponse<T> run(String input, RunOptions options) {
        return run(userMessage(input), options);
    }

    /**
     * Runs one message synchronously.
     *
     * @param message input message
     * @return terminal response
     */
    default AgentResponse<T> run(Message message) {
        return run(List.of(requireMessage(message)), RunOptions.empty());
    }

    /**
     * Runs one message synchronously with options.
     *
     * @param message input message
     * @param options run options
     * @return terminal response
     */
    default AgentResponse<T> run(Message message, RunOptions options) {
        return run(List.of(requireMessage(message)), options);
    }

    /**
     * Runs ordered messages synchronously.
     *
     * @param messages ordered input messages
     * @return terminal response
     */
    default AgentResponse<T> run(List<Message> messages) {
        return run(messages, RunOptions.empty());
    }

    /**
     * Runs ordered messages synchronously with options.
     *
     * @param messages ordered input messages
     * @param options run options
     * @return terminal response
     */
    default AgentResponse<T> run(List<Message> messages, RunOptions options) {
        return RunHandles.await(startRun(messages, options), "Agent run");
    }

    /**
     * Starts text as an explicitly cancellable run.
     *
     * @param input user text
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(String input) {
        return startRun(userMessage(input));
    }

    /**
     * Starts text as an explicitly cancellable run with options.
     *
     * @param input user text
     * @param options run options
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(String input, RunOptions options) {
        return startRun(userMessage(input), options);
    }

    /**
     * Starts text with caller-owned cancellation.
     *
     * @param input user text
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(String input, RunOptions options, RunCancellation cancellation) {
        return startRun(userMessage(input), options, cancellation);
    }

    /**
     * Starts one message as an explicitly cancellable run.
     *
     * @param message input message
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(Message message) {
        return startRun(List.of(requireMessage(message)), RunOptions.empty());
    }

    /**
     * Starts one message as an explicitly cancellable run with options.
     *
     * @param message input message
     * @param options run options
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(Message message, RunOptions options) {
        return startRun(List.of(requireMessage(message)), options);
    }

    /**
     * Starts one message with caller-owned cancellation.
     *
     * @param message input message
     * @param options run options
     * @param cancellation caller-owned cancellation signal
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(Message message, RunOptions options, RunCancellation cancellation) {
        return startRun(List.of(requireMessage(message)), options, cancellation);
    }

    /**
     * Starts ordered messages as an explicitly cancellable run.
     *
     * @param messages ordered input messages
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(List<Message> messages) {
        return startRun(messages, RunOptions.empty());
    }

    /**
     * Starts ordered messages as an explicitly cancellable run with options.
     *
     * @param messages ordered input messages
     * @param options run options
     * @return run handle
     */
    default RunHandle<AgentResponse<T>> startRun(List<Message> messages, RunOptions options) {
        return startRun(messages, options, new DefaultRunCancellation());
    }

    /**
     * Releases resources owned by an implementation.
     *
     * <p>The interface owns no resources by default.
     */
    @Override
    default void close() {}

    private static Message userMessage(String input) {
        return Message.text(Role.USER, AgentValidation.requireNonBlank(input, "input"));
    }

    private static Message requireMessage(Message message) {
        return AgentValidation.requireNonNull(message, "message");
    }
}
