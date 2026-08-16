// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.BaseAgent;
import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.Experimental;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Exposes a typed {@link FunctionalWorkflow} through the provider-neutral {@link
 * com.microsoft.agents.agents.Agent} contract.
 *
 * <p>Application-provided mappers preserve arbitrary workflow input and output types without
 * introducing reflection or provider SDK types. A pending workflow information request is surfaced
 * as an informational {@code request_info} function call. Supplying a correlated
 * {@link FunctionResultContent} in a later run automatically performs response-only replay of the
 * pending invocation.
 *
 * <p>The workflow is caller-owned by default. The adapter retains only the latest process-local
 * continuation descriptor; durable cross-instance resume remains available through the underlying
 * workflow checkpoint APIs.
 *
 * @param <I> workflow input type
 * @param <O> workflow output and structured agent response type
 */
@Experimental("FUNCTIONAL_WORKFLOWS")
public final class FunctionalWorkflowAgent<I, O> extends BaseAgent<O> {
    /** Stable pseudo-function name used for workflow information requests. */
    public static final String REQUEST_INFO_FUNCTION = "request_info";

    private static final int MAX_BUFFERED_UPDATES = 256;

    private final FunctionalWorkflow<I, O> workflow;

    private final Function<List<Message>, I> inputMapper;

    private final Function<O, List<Message>> outputMapper;

    private final boolean closeWorkflow;

    private final Object continuationLock = new Object();

    private PendingContinuation pendingContinuation;

    /**
     * Creates a non-owning workflow agent.
     *
     * @param workflow caller-owned functional workflow
     * @param metadata agent identity and display metadata
     * @param inputMapper converts ordered agent messages to the workflow input
     * @param outputMapper converts a workflow output to ordered agent messages
     */
    public FunctionalWorkflowAgent(
            FunctionalWorkflow<I, O> workflow,
            AgentMetadata metadata,
            Function<List<Message>, ? extends I> inputMapper,
            Function<? super O, ? extends List<Message>> outputMapper) {
        this(workflow, metadata, inputMapper, outputMapper, false);
    }

    /**
     * Creates a workflow agent with explicit workflow ownership.
     *
     * @param workflow functional workflow
     * @param metadata agent identity and display metadata
     * @param inputMapper converts ordered agent messages to the workflow input
     * @param outputMapper converts a workflow output to ordered agent messages
     * @param closeWorkflow whether closing this agent closes the workflow
     */
    public FunctionalWorkflowAgent(
            FunctionalWorkflow<I, O> workflow,
            AgentMetadata metadata,
            Function<List<Message>, ? extends I> inputMapper,
            Function<? super O, ? extends List<Message>> outputMapper,
            boolean closeWorkflow) {
        super(metadata);
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(inputMapper, "inputMapper");
        Objects.requireNonNull(outputMapper, "outputMapper");
        this.inputMapper = messages -> inputMapper.apply(messages);
        this.outputMapper = output -> copyMessages(outputMapper.apply(output), "outputMapper");
        this.closeWorkflow = closeWorkflow;
    }

    /**
     * Returns an input mapper that joins non-empty message text with newline separators.
     *
     * @return text workflow input mapper
     */
    public static Function<List<Message>, String> joinedTextInput() {
        return messages -> Objects.requireNonNull(messages, "messages").stream()
                .map(Message::text)
                .filter(text -> !text.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /**
     * Returns an output mapper that creates one assistant text message.
     *
     * @return text workflow output mapper
     */
    public static Function<String, List<Message>> assistantTextOutput() {
        return output -> List.of(Message.text(Role.ASSISTANT, Objects.requireNonNull(output, "output")));
    }

    /**
     * Returns the adapted functional workflow.
     *
     * @return workflow
     */
    public FunctionalWorkflow<I, O> workflow() {
        return workflow;
    }

    @Override
    protected CompletionStage<AgentResponse<O>> executeAsync(AgentRunContext context) {
        PreparedInvocation<I> invocation = prepareInvocation(context);
        RunHandle<FunctionalWorkflowRunResult<O>> run = invocation.resume()
                ? workflow.startResume(invocation.responses(), invocation.options(), context.cancellation())
                : workflow.startRun(invocation.input(), invocation.options(), context.cancellation());
        return run.resultAsync().thenApply(this::toResponse);
    }

    @Override
    protected StreamingExecution<O> executeStreaming(AgentRunContext context) {
        CompletableFuture<AgentResponse<O>> result = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> {
                    try {
                        PreparedInvocation<I> invocation = prepareInvocation(context);
                        Flow.Publisher<WorkflowEvent> events = invocation.resume()
                                ? workflow.resumeStreaming(
                                        invocation.responses(), invocation.options(), context.cancellation())
                                : workflow.runStreaming(
                                        invocation.input(), invocation.options(), context.cancellation());
                        subscribeToEvents(events, context, upstream, sinkReference.get(), result);
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                        sinkReference.get().fail(failure);
                    }
                },
                () -> {
                    context.cancellation().cancel();
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                },
                MAX_BUFFERED_UPDATES);
        sinkReference.set(sink);
        return new StreamingExecution<>(sink, result.minimalCompletionStage());
    }

    @Override
    protected void closeResources() {
        synchronized (continuationLock) {
            pendingContinuation = null;
        }
        if (closeWorkflow) {
            workflow.close();
        }
    }

    private PreparedInvocation<I> prepareInvocation(AgentRunContext context) {
        PendingContinuation current;
        synchronized (continuationLock) {
            current = pendingContinuation;
        }
        FunctionalWorkflowResponses responses = responsesFrom(context.inputMessages(), current);
        if (current != null && !responses.values().isEmpty()) {
            FunctionalWorkflowRunOptions options = FunctionalWorkflowRunOptions.builder()
                    .metadata(context.metadata())
                    .build();
            return PreparedInvocation.resume(responses, options);
        }

        synchronized (continuationLock) {
            if (pendingContinuation == current) {
                pendingContinuation = null;
            }
        }
        I input;
        try {
            input = inputMapper.apply(context.inputMessages());
        } catch (RuntimeException failure) {
            throw new AgentExecutionException("Functional workflow agent input mapping failed.", failure);
        }
        if (input == null) {
            throw new AgentExecutionException("Functional workflow agent input mapper returned null.");
        }
        FunctionalWorkflowRunOptions options = FunctionalWorkflowRunOptions.builder()
                .runId(context.runId())
                .metadata(context.metadata())
                .build();
        return PreparedInvocation.fresh(input, options);
    }

    private FunctionalWorkflowResponses responsesFrom(List<Message> messages, PendingContinuation continuation) {
        if (continuation == null) {
            return FunctionalWorkflowResponses.empty();
        }
        FunctionalWorkflowResponses.Builder responses = FunctionalWorkflowResponses.builder();
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (Message message : messages) {
            for (com.microsoft.agents.core.Content content : message.contents()) {
                if (!(content instanceof FunctionResultContent result)) {
                    continue;
                }
                FunctionalInputRequest request = continuation.requests().get(result.callId());
                if (request == null) {
                    continue;
                }
                if (result.error() != null) {
                    throw new AgentExecutionException(
                            "Functional workflow response '" + result.callId() + "' failed: " + result.error());
                }
                if (seen.putIfAbsent(result.callId(), Boolean.TRUE) != null) {
                    throw new AgentExecutionException(
                            "Functional workflow response '" + result.callId() + "' was supplied more than once.");
                }
                responses.putEncoded(
                        result.callId(),
                        new EncodedState(request.responseTypeId(), request.responseVersion(), result.result()));
            }
        }
        return responses.build();
    }

    private AgentResponse<O> toResponse(FunctionalWorkflowRunResult<O> result) {
        List<Message> messages = new ArrayList<>();
        O output = result.output().orElse(null);
        if (output != null) {
            messages.addAll(mapOutput(output));
        }
        if (result.status() == FunctionalWorkflowRunStatus.INPUT_REQUIRED) {
            messages.add(requestMessage(result.pendingRequests()));
            rememberPending(result.pendingRequests());
        } else {
            rememberPending(List.of());
        }
        return response(result.runId(), result.status(), output, List.copyOf(messages), result.pendingRequests());
    }

    private AgentResponse<O> response(
            String runId,
            FunctionalWorkflowRunStatus status,
            O output,
            List<Message> messages,
            List<FunctionalInputRequest> requests) {
        boolean inputRequired = status == FunctionalWorkflowRunStatus.INPUT_REQUIRED;
        AgentResponse.Builder<O> builder = AgentResponse.<O>builder()
                .messages(messages)
                .responseId(runId)
                .agentId(metadata().id())
                .finishReason(inputRequired ? FinishReason.TOOL_CALLS : FinishReason.STOP)
                .value(output)
                .metadata(responseMetadata(runId, status));
        if (inputRequired) {
            builder.continuationToken(continuationToken(runId, requests));
        }
        return builder.build();
    }

    private void subscribeToEvents(
            Flow.Publisher<WorkflowEvent> events,
            AgentRunContext context,
            AtomicReference<Flow.Subscription> upstream,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            CompletableFuture<AgentResponse<O>> result) {
        StreamingState<O> state = new StreamingState<>();
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                if (!upstream.compareAndSet(null, subscription)) {
                    subscription.cancel();
                } else {
                    subscription.request(Long.MAX_VALUE);
                }
            }

            @Override
            public void onNext(WorkflowEvent event) {
                if (state.done.get()) {
                    return;
                }
                try {
                    state.runId = event.runId();
                    if (event.type() == WorkflowEventType.OUTPUT) {
                        O output = decodeOutput(event);
                        state.output = output;
                        List<Message> outputMessages = mapOutput(output);
                        state.messages.addAll(outputMessages);
                        for (Message message : outputMessages) {
                            sink.emit(update(state.sequence.getAndIncrement(), message, null, null, context));
                        }
                    } else if (event.type() == WorkflowEventType.INPUT_REQUESTED) {
                        FunctionalInputRequest request = inputRequest(event);
                        state.requests.put(request.requestId(), request);
                        Message requestMessage = requestMessage(List.of(request));
                        state.messages.add(requestMessage);
                        sink.emit(update(state.sequence.getAndIncrement(), requestMessage, null, null, context));
                    }
                } catch (RuntimeException failure) {
                    failStreaming(state, failure, upstream, sink, result);
                }
            }

            @Override
            public void onError(Throwable failure) {
                if (state.done.compareAndSet(false, true)) {
                    result.completeExceptionally(failure);
                    sink.fail(failure);
                }
            }

            @Override
            public void onComplete() {
                if (!state.done.compareAndSet(false, true)) {
                    return;
                }
                List<FunctionalInputRequest> requests = List.copyOf(state.requests.values());
                FunctionalWorkflowRunStatus status = requests.isEmpty()
                        ? FunctionalWorkflowRunStatus.COMPLETED
                        : FunctionalWorkflowRunStatus.INPUT_REQUIRED;
                rememberPending(requests);
                String runId = state.runId == null ? context.runId() : state.runId;
                AgentResponse<O> response =
                        response(runId, status, state.output, List.copyOf(state.messages), requests);
                StateValue token = response.continuationToken();
                sink.emit(update(state.sequence.getAndIncrement(), null, response.finishReason(), token, context));
                result.complete(response);
                sink.complete();
            }
        });
    }

    private static <O> void failStreaming(
            StreamingState<O> state,
            RuntimeException failure,
            AtomicReference<Flow.Subscription> upstream,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            CompletableFuture<AgentResponse<O>> result) {
        if (!state.done.compareAndSet(false, true)) {
            return;
        }
        Flow.Subscription subscription = upstream.get();
        if (subscription != null) {
            subscription.cancel();
        }
        result.completeExceptionally(failure);
        sink.fail(failure);
    }

    private O decodeOutput(WorkflowEvent event) {
        if (!(event.data() instanceof StateValue.ObjectValue object)) {
            throw new AgentExecutionException("Functional workflow output event data must be an object.");
        }
        return workflow.decodeOutput(object.require("value"));
    }

    private static FunctionalInputRequest inputRequest(WorkflowEvent event) {
        if (!(event.data() instanceof StateValue.ObjectValue object)) {
            throw new AgentExecutionException("Functional workflow input-request event data must be an object.");
        }
        return new FunctionalInputRequest(
                string(object.require("requestId"), "requestId"),
                string(object.require("sourceId"), "sourceId"),
                object.require("requestData"),
                string(object.require("responseTypeId"), "responseTypeId"),
                integer(object.require("responseVersion"), "responseVersion"));
    }

    private List<Message> mapOutput(O output) {
        try {
            return outputMapper.apply(output);
        } catch (RuntimeException failure) {
            throw new AgentExecutionException("Functional workflow agent output mapping failed.", failure);
        }
    }

    private Message requestMessage(List<FunctionalInputRequest> requests) {
        List<FunctionCallContent> contents =
                requests.stream().map(this::requestContent).toList();
        Message.Builder builder = Message.builder(Role.ASSISTANT).contents(contents);
        if (metadata().name() != null) {
            builder.authorName(metadata().name());
        }
        return builder.build();
    }

    private FunctionCallContent requestContent(FunctionalInputRequest request) {
        return new FunctionCallContent(
                request.requestId(),
                REQUEST_INFO_FUNCTION,
                StateValue.object(Map.of(
                        "requestId",
                        StateValue.string(request.requestId()),
                        "sourceId",
                        StateValue.string(request.sourceId()),
                        "data",
                        request.data())),
                true,
                Map.of(
                        "functionalWorkflowInputRequest",
                        StateValue.bool(true),
                        "responseTypeId",
                        StateValue.string(request.responseTypeId()),
                        "responseVersion",
                        StateValue.integer(request.responseVersion())));
    }

    private AgentResponseUpdate update(
            long sequence,
            Message message,
            FinishReason finishReason,
            StateValue continuationToken,
            AgentRunContext context) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                .sequence(sequence)
                .agentId(metadata().id())
                .responseId(context.runId())
                .metadata(Map.of(
                        "functionalWorkflowId", StateValue.string(workflow.id()),
                        "agentRunId", StateValue.string(context.runId())));
        if (message != null) {
            builder.contents(message.contents()).role(message.role());
            if (message.authorName() != null) {
                builder.authorName(message.authorName());
            }
            if (message.messageId() != null) {
                builder.messageId(message.messageId());
            }
        }
        if (finishReason != null) {
            builder.finishReason(finishReason);
        }
        if (continuationToken != null) {
            builder.continuationToken(continuationToken);
        }
        return builder.build();
    }

    private void rememberPending(List<FunctionalInputRequest> requests) {
        synchronized (continuationLock) {
            pendingContinuation = requests.isEmpty() ? null : PendingContinuation.from(requests);
        }
    }

    private StateValue.ObjectValue continuationToken(String runId, List<FunctionalInputRequest> requests) {
        return StateValue.object(Map.of(
                "kind",
                StateValue.string("functionalWorkflow"),
                "workflowId",
                StateValue.string(workflow.id()),
                "runId",
                StateValue.string(runId),
                "requestIds",
                StateValue.array(requests.stream()
                        .map(FunctionalInputRequest::requestId)
                        .map(StateValue::string)
                        .toList())));
    }

    private Map<String, StateValue> responseMetadata(String runId, FunctionalWorkflowRunStatus status) {
        return Map.of(
                "functionalWorkflowId",
                StateValue.string(workflow.id()),
                "functionalWorkflowRunId",
                StateValue.string(runId),
                "functionalWorkflowStatus",
                StateValue.string(
                        status == FunctionalWorkflowRunStatus.INPUT_REQUIRED ? "inputRequired" : "completed"));
    }

    private static List<Message> copyMessages(List<Message> messages, String source) {
        if (messages == null) {
            throw new AgentExecutionException("Functional workflow agent " + source + " returned null.");
        }
        ArrayList<Message> copy = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message == null) {
                throw new AgentExecutionException("Functional workflow agent " + source + " returned a null message.");
            }
            copy.add(message);
        }
        return List.copyOf(copy);
    }

    private static String string(StateValue value, String name) {
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new AgentExecutionException("Functional workflow event member '" + name + "' must be a string.");
    }

    private static int integer(StateValue value, String name) {
        if (value instanceof StateValue.NumberValue number) {
            try {
                return number.value().intValueExact();
            } catch (ArithmeticException failure) {
                throw new AgentExecutionException(
                        "Functional workflow event member '" + name + "' must be an integer.", failure);
            }
        }
        throw new AgentExecutionException("Functional workflow event member '" + name + "' must be an integer.");
    }

    private record PreparedInvocation<I>(
            I input, FunctionalWorkflowResponses responses, FunctionalWorkflowRunOptions options, boolean resume) {
        private static <I> PreparedInvocation<I> fresh(I input, FunctionalWorkflowRunOptions options) {
            return new PreparedInvocation<>(
                    Objects.requireNonNull(input, "input"),
                    FunctionalWorkflowResponses.empty(),
                    Objects.requireNonNull(options, "options"),
                    false);
        }

        private static <I> PreparedInvocation<I> resume(
                FunctionalWorkflowResponses responses, FunctionalWorkflowRunOptions options) {
            return new PreparedInvocation<>(
                    null,
                    Objects.requireNonNull(responses, "responses"),
                    Objects.requireNonNull(options, "options"),
                    true);
        }
    }

    private record PendingContinuation(Map<String, FunctionalInputRequest> requests) {
        private PendingContinuation {
            requests = Map.copyOf(requests);
        }

        private static PendingContinuation from(List<FunctionalInputRequest> requests) {
            LinkedHashMap<String, FunctionalInputRequest> indexed = new LinkedHashMap<>();
            requests.stream()
                    .sorted(java.util.Comparator.comparing(FunctionalInputRequest::requestId))
                    .forEach(request -> indexed.put(request.requestId(), request));
            return new PendingContinuation(indexed);
        }
    }

    private static final class StreamingState<O> {
        private final AtomicBoolean done = new AtomicBoolean();

        private final AtomicLong sequence = new AtomicLong();

        private final ArrayList<Message> messages = new ArrayList<>();

        private final LinkedHashMap<String, FunctionalInputRequest> requests = new LinkedHashMap<>();

        private String runId;

        private O output;
    }
}
