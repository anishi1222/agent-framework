// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ResponseAggregator;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts Copilot CLI sessions to the provider-neutral {@link ChatClient} contract.
 *
 * <p>When {@code ChatOptions.conversationId} is present, only the newest user message is sent. The
 * preceding framework history is not retransmitted because the external Copilot session already
 * owns it. Custom tools are configured through {@link GitHubCopilotSessionConfig}; an outer
 * framework tool loop is rejected to prevent duplicate execution.
 */
public final class GitHubCopilotChatClient implements ChatClient {
    private final GitHubCopilotClient client;

    private final GitHubCopilotSessionConfig defaults;

    private final boolean closeClient;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<RunCancellation> activeCancellations = ConcurrentHashMap.newKeySet();

    /**
     * Creates a caller-owned-client adapter.
     *
     * @param client caller-owned client
     * @param defaults default session configuration
     */
    public GitHubCopilotChatClient(GitHubCopilotClient client, GitHubCopilotSessionConfig defaults) {
        this(client, defaults, false);
    }

    /**
     * Creates an adapter with explicit client ownership.
     *
     * @param client client
     * @param defaults default session configuration
     * @param closeClient whether closing this adapter closes the client
     */
    public GitHubCopilotChatClient(
            GitHubCopilotClient client, GitHubCopilotSessionConfig defaults, boolean closeClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.closeClient = closeClient;
    }

    /**
     * Returns the configured client.
     *
     * @return client
     */
    public GitHubCopilotClient client() {
        return client;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        validate(request, cancellation);
        if (closed.get()) {
            return CompletableFuture.failedFuture(failure("client_closed"));
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        activeCancellations.add(cancellation);
        GitHubCopilotSessionConfig config = effectiveConfig(request, false);
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<GitHubCopilotSession> sessionRef = new AtomicReference<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            GitHubCopilotSession session = sessionRef.get();
            if (session != null) {
                session.abortAsync();
                session.close();
            }
            finish(cancellation, registration, result, null, new RunCancelledException());
        }));
        openSession(request, config).whenComplete((session, openFailure) -> {
            if (openFailure != null) {
                if (finished.compareAndSet(false, true)) {
                    finish(cancellation, registration, result, null, openFailure);
                }
                return;
            }
            sessionRef.set(session);
            if (finished.get() || cancellation.isCancellationRequested() || closed.get()) {
                session.abortAsync();
                session.close();
                if (finished.compareAndSet(false, true)) {
                    finish(cancellation, registration, result, null, new RunCancelledException());
                }
                return;
            }
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            AutoCloseable listener = session.addListener(
                    event -> {
                        ChatResponseUpdate update = update(event, false);
                        if (update != null) {
                            updates.add(update);
                        }
                    },
                    eventFailure -> {
                        if (finished.compareAndSet(false, true)) {
                            session.abortAsync();
                            session.close();
                            finish(cancellation, registration, result, null, eventFailure);
                        }
                    });
            session.sendAndWaitAsync(prompt(request)).whenComplete((last, sendFailure) -> {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                closeQuietly(listener);
                if (sendFailure != null) {
                    session.close();
                    finish(cancellation, registration, result, null, sendFailure);
                    return;
                }
                updates.add(terminal(session.sessionId(), last));
                ChatResponse response;
                try {
                    response = ResponseAggregator.aggregateChat(updates);
                } catch (RuntimeException mappingFailure) {
                    session.close();
                    finish(cancellation, registration, result, null, mappingFailure);
                    return;
                }
                session.close();
                finish(cancellation, registration, result, response, null);
            });
        });
        return result.minimalCompletionStage();
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        validate(request, cancellation);
        StreamingRun run = new StreamingRun(request, cancellation);
        SingleSubscriberPublisher<ChatResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                run::start,
                run::cancel,
                client.options().limits().maxBufferedEvents(),
                ignored -> failure("stream_buffer_overflow"));
        run.sink = publisher;
        return publisher;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeCancellations.forEach(RunCancellation::cancel);
        activeCancellations.clear();
        if (closeClient) {
            client.close();
        }
    }

    private CompletionStage<GitHubCopilotSession> openSession(
            ChatClientRequest request, GitHubCopilotSessionConfig config) {
        String conversationId = request.options().conversationId();
        return conversationId == null
                ? client.createSessionAsync(config)
                : client.resumeSessionAsync(conversationId, config);
    }

    private GitHubCopilotSessionConfig effectiveConfig(ChatClientRequest request, boolean streaming) {
        GitHubCopilotSessionConfig.Builder builder = copy(defaults).streaming(streaming);
        if (request.options().model() != null) {
            builder.model(request.options().model());
        }
        if (request.options().instructions() != null && defaults.systemMessage() == null) {
            builder.systemMessage(new GitHubCopilotSystemMessage(
                    GitHubCopilotSystemMessage.Mode.APPEND, request.options().instructions()));
        }
        return builder.build();
    }

    private static GitHubCopilotSessionConfig.Builder copy(GitHubCopilotSessionConfig source) {
        GitHubCopilotSessionConfig.Builder builder = GitHubCopilotSessionConfig.builder()
                .streaming(source.streaming())
                .permissionHandler(source.permissionHandler())
                .userInputHandler(source.userInputHandler())
                .enableSessionStore(source.enableSessionStore());
        if (source.model() != null) {
            builder.model(source.model());
        }
        if (source.reasoningEffort() != null) {
            builder.reasoningEffort(source.reasoningEffort());
        }
        if (source.systemMessage() != null) {
            builder.systemMessage(source.systemMessage());
        }
        source.tools().forEach(builder::tool);
        source.allowedBuiltInTools().forEach(builder::allowBuiltInTool);
        source.excludedTools().forEach(builder::excludeTool);
        source.mcpServers().forEach(builder::mcpServer);
        source.customAgents().forEach(builder::customAgent);
        source.skillDirectories().forEach(builder::skillDirectory);
        source.disabledSkills().forEach(builder::disableSkill);
        source.hooks().forEach(builder::hook);
        if (source.infiniteSession() != null) {
            builder.infiniteSession(source.infiniteSession());
        }
        if (source.provider() != null) {
            builder.provider(source.provider());
        }
        return builder;
    }

    private static void validate(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (request.messages().isEmpty()) {
            throw new IllegalArgumentException("request.messages must not be empty.");
        }
        if (request.options().structuredOutput() != null) {
            throw new IllegalArgumentException("GitHub Copilot sessions do not support ChatOptions.structuredOutput.");
        }
        if (!request.tools().isEmpty()) {
            throw new IllegalArgumentException("Framework tool declarations are not retransmitted; "
                    + "configure Copilot custom tools on the session.");
        }
        if (request.messages().stream()
                .noneMatch(message ->
                        message.role() == Role.USER && !message.text().isBlank())) {
            throw new IllegalArgumentException("request must contain a non-blank user message.");
        }
    }

    private static String prompt(ChatClientRequest request) {
        if (request.options().conversationId() != null) {
            for (int index = request.messages().size() - 1; index >= 0; index--) {
                Message message = request.messages().get(index);
                if (message.role() == Role.USER && !message.text().isBlank()) {
                    return message.text();
                }
            }
        }
        return request.messages().stream()
                .filter(message -> !message.text().isBlank())
                .map(message -> message.role().value() + ": " + message.text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static ChatResponseUpdate update(GitHubCopilotEvent event, boolean streaming) {
        List<? extends Content> contents;
        switch (event.type()) {
            case ASSISTANT_MESSAGE_DELTA -> {
                if (!streaming || event.text() == null) {
                    return null;
                }
                contents = List.of(new TextContent(event.text()));
            }
            case ASSISTANT_MESSAGE -> {
                if (streaming || event.text() == null) {
                    return null;
                }
                contents = List.of(new TextContent(event.text()));
            }
            case TOOL_EXECUTION_START ->
                contents = List.of(new FunctionCallContent(
                        require(event.toolCallId(), "toolCallId"),
                        require(event.toolName(), "toolName"),
                        event.arguments() == null ? StateValue.object(java.util.Map.of()) : event.arguments()));
            case TOOL_EXECUTION_COMPLETE ->
                contents = List.of(new FunctionResultContent(
                        require(event.toolCallId(), "toolCallId"),
                        event.result() == null ? StateValue.nullValue() : event.result(),
                        List.of(),
                        Boolean.FALSE.equals(event.success()) ? event.text() : null,
                        java.util.Map.of()));
            case USAGE -> contents = List.of();
            case ERROR ->
                contents = List.of(new ErrorContent(
                        event.text() == null ? "GitHub Copilot session error." : event.text(), "github_copilot", null));
            default -> {
                return null;
            }
        }
        return new ChatResponseUpdate(
                event.sequence(),
                List.copyOf(contents),
                event.type() == GitHubCopilotEventType.TOOL_EXECUTION_COMPLETE ? Role.TOOL : Role.ASSISTANT,
                null,
                event.messageId(),
                event.messageId(),
                event.sessionId(),
                event.model(),
                event.timestamp(),
                null,
                event.usage(),
                null,
                java.util.Map.of("upstreamType", StateValue.string(event.upstreamType())));
    }

    private static ChatResponseUpdate terminal(String sessionId, GitHubCopilotEvent last) {
        return new ChatResponseUpdate(
                last == null ? 0 : last.sequence() + 1,
                List.of(),
                Role.ASSISTANT,
                null,
                last == null ? null : last.messageId(),
                last == null ? null : last.messageId(),
                sessionId,
                last == null ? null : last.model(),
                last == null ? null : last.timestamp(),
                FinishReason.STOP,
                null,
                null,
                java.util.Map.of());
    }

    private void finish(
            RunCancellation cancellation,
            AtomicReference<RunCancellationRegistration> registration,
            CompletableFuture<ChatResponse> result,
            ChatResponse response,
            Throwable failure) {
        activeCancellations.remove(cancellation);
        RunCancellationRegistration current = registration.getAndSet(null);
        if (current != null) {
            current.close();
        }
        if (failure == null) {
            result.complete(response);
        } else {
            result.completeExceptionally(GitHubCopilotClient.normalize(failure, "request"));
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Listener removal is best effort after the request has reached a terminal state.
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new GitHubCopilotProviderException(
                    "Copilot event omitted " + name + ".", null, "protocol", "missing_" + name);
        }
        return value;
    }

    private static GitHubCopilotProviderException failure(String code) {
        return new GitHubCopilotProviderException("GitHub Copilot chat operation failed.", null, "request", code);
    }

    private final class StreamingRun {
        private final ChatClientRequest request;

        private final RunCancellation cancellation;

        private final AtomicBoolean finished = new AtomicBoolean();

        private final AtomicReference<GitHubCopilotSession> session = new AtomicReference<>();

        private final AtomicReference<AutoCloseable> listener = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private SingleSubscriberPublisher<ChatResponseUpdate> sink;

        private StreamingRun(ChatClientRequest request, RunCancellation cancellation) {
            this.request = request;
            this.cancellation = cancellation;
        }

        private void start() {
            if (closed.get()) {
                fail(failure("client_closed"));
                return;
            }
            if (cancellation.isCancellationRequested()) {
                fail(new RunCancelledException());
                return;
            }
            activeCancellations.add(cancellation);
            registration.set(RunCancellations.register(cancellation, this::cancel));
            GitHubCopilotSessionConfig config = effectiveConfig(request, true);
            openSession(request, config).whenComplete((opened, openFailure) -> {
                if (openFailure != null) {
                    fail(openFailure);
                    return;
                }
                session.set(opened);
                if (finished.get() || cancellation.isCancellationRequested() || closed.get()) {
                    opened.abortAsync();
                    opened.close();
                    if (!finished.get()) {
                        fail(new RunCancelledException());
                    }
                    return;
                }
                listener.set(opened.addListener(this::onEvent, this::fail));
                opened.sendAsync(prompt(request)).whenComplete((ignored, sendFailure) -> {
                    if (sendFailure != null) {
                        fail(sendFailure);
                    }
                });
            });
        }

        private void onEvent(GitHubCopilotEvent event) {
            if (finished.get()) {
                return;
            }
            if (event.type() == GitHubCopilotEventType.IDLE) {
                sink.emit(terminal(event.sessionId(), event));
                complete();
                return;
            }
            ChatResponseUpdate mapped = update(event, true);
            if (mapped != null) {
                sink.emit(mapped);
            }
            if (event.type() == GitHubCopilotEventType.ERROR) {
                fail(new GitHubCopilotProviderException(
                        event.text() == null ? "GitHub Copilot session error." : event.text(),
                        null,
                        "service",
                        "session_error"));
            }
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
            sink.fail(GitHubCopilotClient.normalize(failure, "stream"));
        }

        private void cancel() {
            GitHubCopilotSession current = session.get();
            if (current != null) {
                current.abortAsync();
            }
            fail(new RunCancelledException());
        }

        private void cleanup() {
            activeCancellations.remove(cancellation);
            RunCancellationRegistration currentRegistration = registration.getAndSet(null);
            if (currentRegistration != null) {
                currentRegistration.close();
            }
            AutoCloseable currentListener = listener.getAndSet(null);
            if (currentListener != null) {
                closeQuietly(currentListener);
            }
            GitHubCopilotSession currentSession = session.getAndSet(null);
            if (currentSession != null) {
                currentSession.close();
            }
        }
    }
}
