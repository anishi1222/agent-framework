// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandles;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the shared execution surface for a reusable orchestration pattern.
 *
 * <p>Finite asynchronous, synchronous, explicitly cancellable, and streaming views derive from one
 * pattern execution core. Synchronous callers must not block a saturated caller-owned participant
 * executor that is also needed to dispatch the run.
 *
 * @param <O> terminal output type
 */
public interface Orchestration<O> extends AutoCloseable {
    /**
     * Returns the stable orchestration identifier.
     *
     * @return orchestration identifier
     */
    String id();

    /**
     * Returns the stable orchestration pattern.
     *
     * @return owning pattern
     */
    OrchestrationPattern pattern();

    /**
     * Returns participants in deterministic declaration order.
     *
     * @return immutable participant list
     */
    List<OrchestrationParticipant> participants();

    /**
     * Starts the canonical finite execution path.
     *
     * @param messages ordered input messages
     * @param options orchestration options
     * @param cancellation caller-owned cancellation
     * @return run handle
     */
    RunHandle<OrchestrationResult<O>> startRun(
            List<Message> messages, OrchestrationRunOptions options, RunCancellation cancellation);

    /**
     * Starts the canonical finite continuation path.
     *
     * <p>The continuation is consumed exactly once when validation succeeds. It can be resumed only
     * by the process-local orchestration instance that created it.
     *
     * @param continuation one-time continuation descriptor
     * @param input typed resume input
     * @param options resume phase options
     * @param cancellation caller-owned cancellation
     * @return run handle for the continued logical run
     */
    RunHandle<OrchestrationResult<O>> startResume(
            OrchestrationContinuation continuation,
            OrchestrationResumeInput input,
            OrchestrationRunOptions options,
            RunCancellation cancellation);

    /**
     * Starts the canonical cold streaming execution path.
     *
     * @param messages ordered input messages
     * @param options orchestration options
     * @param cancellation caller-owned cancellation
     * @return bounded single-subscriber event publisher
     */
    Flow.Publisher<OrchestrationEvent> runStreaming(
            List<Message> messages, OrchestrationRunOptions options, RunCancellation cancellation);

    /**
     * Starts the canonical cold streaming continuation path.
     *
     * <p>The continuation is not consumed until the publisher receives its first subscriber.
     *
     * @param continuation one-time continuation descriptor
     * @param input typed resume input
     * @param options resume phase options
     * @param cancellation caller-owned cancellation
     * @return bounded single-subscriber event publisher
     */
    Flow.Publisher<OrchestrationEvent> resumeStreaming(
            OrchestrationContinuation continuation,
            OrchestrationResumeInput input,
            OrchestrationRunOptions options,
            RunCancellation cancellation);

    /**
     * Runs text asynchronously with default options.
     *
     * @param input user input
     * @return terminal result stage
     */
    default CompletionStage<OrchestrationResult<O>> runAsync(String input) {
        return runAsync(List.of(Message.text(Role.USER, OrchestrationValidation.requireText(input, "input"))));
    }

    /**
     * Runs text asynchronously with explicit options.
     *
     * @param input user input
     * @param options run options
     * @return terminal result stage
     */
    default CompletionStage<OrchestrationResult<O>> runAsync(String input, OrchestrationRunOptions options) {
        return runAsync(List.of(Message.text(Role.USER, OrchestrationValidation.requireText(input, "input"))), options);
    }

    /**
     * Runs ordered messages asynchronously with default options.
     *
     * @param messages ordered input
     * @return terminal result stage
     */
    default CompletionStage<OrchestrationResult<O>> runAsync(List<Message> messages) {
        return runAsync(messages, OrchestrationRunOptions.defaults());
    }

    /**
     * Runs ordered messages asynchronously.
     *
     * @param messages ordered input
     * @param options run options
     * @return terminal result stage
     */
    default CompletionStage<OrchestrationResult<O>> runAsync(List<Message> messages, OrchestrationRunOptions options) {
        return startRun(messages, options).resultAsync();
    }

    /**
     * Runs ordered messages asynchronously with caller-owned cancellation.
     *
     * @param messages ordered input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return terminal result stage
     */
    default CompletionStage<OrchestrationResult<O>> runAsync(
            List<Message> messages, OrchestrationRunOptions options, RunCancellation cancellation) {
        return startRun(messages, options, cancellation).resultAsync();
    }

    /**
     * Resumes asynchronously with default options.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @return continued result stage
     */
    default CompletionStage<OrchestrationResult<O>> resumeAsync(
            OrchestrationContinuation continuation, OrchestrationResumeInput input) {
        return resumeAsync(continuation, input, OrchestrationRunOptions.defaults());
    }

    /**
     * Resumes asynchronously with explicit options.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @param options resume phase options
     * @return continued result stage
     */
    default CompletionStage<OrchestrationResult<O>> resumeAsync(
            OrchestrationContinuation continuation, OrchestrationResumeInput input, OrchestrationRunOptions options) {
        return startResume(continuation, input, options).resultAsync();
    }

    /**
     * Resumes asynchronously with caller-owned cancellation.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @param options resume phase options
     * @param cancellation caller-owned cancellation
     * @return continued result stage
     */
    default CompletionStage<OrchestrationResult<O>> resumeAsync(
            OrchestrationContinuation continuation,
            OrchestrationResumeInput input,
            OrchestrationRunOptions options,
            RunCancellation cancellation) {
        return startResume(continuation, input, options, cancellation).resultAsync();
    }

    /**
     * Runs text synchronously with default options.
     *
     * @param input user input
     * @return terminal result
     */
    default OrchestrationResult<O> run(String input) {
        return run(List.of(Message.text(Role.USER, OrchestrationValidation.requireText(input, "input"))));
    }

    /**
     * Runs text synchronously with explicit options.
     *
     * @param input user input
     * @param options run options
     * @return terminal result
     */
    default OrchestrationResult<O> run(String input, OrchestrationRunOptions options) {
        return run(List.of(Message.text(Role.USER, OrchestrationValidation.requireText(input, "input"))), options);
    }

    /**
     * Runs ordered messages synchronously with default options.
     *
     * @param messages ordered input
     * @return terminal result
     */
    default OrchestrationResult<O> run(List<Message> messages) {
        return run(messages, OrchestrationRunOptions.defaults());
    }

    /**
     * Runs ordered messages synchronously.
     *
     * @param messages ordered input
     * @param options run options
     * @return terminal result
     */
    default OrchestrationResult<O> run(List<Message> messages, OrchestrationRunOptions options) {
        return RunHandles.await(startRun(messages, options), "Orchestration run");
    }

    /**
     * Resumes synchronously with default options.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @return continued result
     */
    default OrchestrationResult<O> resume(OrchestrationContinuation continuation, OrchestrationResumeInput input) {
        return resume(continuation, input, OrchestrationRunOptions.defaults());
    }

    /**
     * Resumes synchronously with explicit options.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @param options resume phase options
     * @return continued result
     */
    default OrchestrationResult<O> resume(
            OrchestrationContinuation continuation, OrchestrationResumeInput input, OrchestrationRunOptions options) {
        return RunHandles.await(startResume(continuation, input, options), "Orchestration resume");
    }

    /**
     * Starts a finite run with framework-owned cancellation.
     *
     * @param messages ordered input
     * @param options run options
     * @return run handle
     */
    default RunHandle<OrchestrationResult<O>> startRun(List<Message> messages, OrchestrationRunOptions options) {
        return startRun(messages, options, new DefaultRunCancellation());
    }

    /**
     * Starts a continuation with framework-owned cancellation.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @param options resume phase options
     * @return run handle
     */
    default RunHandle<OrchestrationResult<O>> startResume(
            OrchestrationContinuation continuation, OrchestrationResumeInput input, OrchestrationRunOptions options) {
        return startResume(continuation, input, options, new DefaultRunCancellation());
    }

    /**
     * Starts a continuation with default options and framework-owned cancellation.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @return run handle
     */
    default RunHandle<OrchestrationResult<O>> startResume(
            OrchestrationContinuation continuation, OrchestrationResumeInput input) {
        return startResume(continuation, input, OrchestrationRunOptions.defaults());
    }

    /**
     * Starts text with framework-owned cancellation.
     *
     * @param input user input
     * @param options run options
     * @return run handle
     */
    default RunHandle<OrchestrationResult<O>> startRun(String input, OrchestrationRunOptions options) {
        return startRun(List.of(Message.text(Role.USER, OrchestrationValidation.requireText(input, "input"))), options);
    }

    /**
     * Starts a finite run with default options.
     *
     * @param messages ordered input
     * @return run handle
     */
    default RunHandle<OrchestrationResult<O>> startRun(List<Message> messages) {
        return startRun(messages, OrchestrationRunOptions.defaults());
    }

    /**
     * Streams text with default options.
     *
     * @param input user input
     * @return event publisher
     */
    default Flow.Publisher<OrchestrationEvent> runStreaming(String input) {
        return runStreaming(List.of(Message.text(Role.USER, OrchestrationValidation.requireText(input, "input"))));
    }

    /**
     * Streams ordered messages with default options.
     *
     * @param messages ordered input
     * @return event publisher
     */
    default Flow.Publisher<OrchestrationEvent> runStreaming(List<Message> messages) {
        return runStreaming(messages, OrchestrationRunOptions.defaults());
    }

    /**
     * Streams ordered messages with framework-owned cancellation.
     *
     * @param messages ordered input
     * @param options run options
     * @return event publisher
     */
    default Flow.Publisher<OrchestrationEvent> runStreaming(List<Message> messages, OrchestrationRunOptions options) {
        return runStreaming(messages, options, new DefaultRunCancellation());
    }

    /**
     * Streams a continuation with default options.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @return event publisher
     */
    default Flow.Publisher<OrchestrationEvent> resumeStreaming(
            OrchestrationContinuation continuation, OrchestrationResumeInput input) {
        return resumeStreaming(continuation, input, OrchestrationRunOptions.defaults());
    }

    /**
     * Streams a continuation with framework-owned cancellation.
     *
     * @param continuation one-time continuation
     * @param input typed resume input
     * @param options resume phase options
     * @return event publisher
     */
    default Flow.Publisher<OrchestrationEvent> resumeStreaming(
            OrchestrationContinuation continuation, OrchestrationResumeInput input, OrchestrationRunOptions options) {
        return resumeStreaming(continuation, input, options, new DefaultRunCancellation());
    }

    /**
     * Releases runtime resources owned by the orchestration.
     *
     * <p>Participants and caller-provided executors remain caller-owned.
     */
    @Override
    void close();
}
