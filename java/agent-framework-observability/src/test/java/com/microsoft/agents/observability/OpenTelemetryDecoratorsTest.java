// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.agents.FunctionMiddlewareContext;
import com.microsoft.agents.agents.MiddlewareMetadata;
import com.microsoft.agents.agents.context.Compactions;
import com.microsoft.agents.agents.context.SummarizationCompactionStrategy;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolInvocationContext;
import com.microsoft.agents.tools.ToolInvocationInterceptContext;
import com.microsoft.agents.workflows.AgentExecutor;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryDecoratorsTest {
    private InMemorySpanExporter spans;

    private InMemoryMetricReader metrics;

    private SdkTracerProvider tracerProvider;

    private SdkMeterProvider meterProvider;

    private OpenTelemetrySdk openTelemetry;

    @BeforeEach
    void setUp() {
        spans = InMemorySpanExporter.create();
        metrics = InMemoryMetricReader.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build();
        meterProvider = SdkMeterProvider.builder().registerMetricReader(metrics).build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        meterProvider.close();
    }

    @Test
    void chatRecordsGenAiUsageDetailAttributes() {
        // Arrange
        UsageDetails usage = UsageDetails.builder()
                .inputTokens(12)
                .outputTokens(7)
                .integer(UsageDetails.CACHE_CREATION_INPUT_TOKENS, java.math.BigInteger.valueOf(3))
                .integer(UsageDetails.CACHE_READ_INPUT_TOKENS, java.math.BigInteger.valueOf(5))
                .integer(UsageDetails.REASONING_OUTPUT_TOKENS, java.math.BigInteger.valueOf(11))
                .build();
        ChatResponse response = ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "output")))
                .model("gpt-5.4")
                .usage(usage)
                .build();
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new ImmediateChatClient(response), telemetry);
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "prompt")),
                ChatOptions.builder().model("gpt-5.4").build());

        // Act
        client.completeAsync(request).toCompletableFuture().join();

        // Assert
        SpanData span = onlySpan();
        assertThat(longAttribute(span, GenAiAttributes.USAGE_INPUT_TOKENS)).isEqualTo(12);
        assertThat(longAttribute(span, GenAiAttributes.USAGE_OUTPUT_TOKENS)).isEqualTo(7);
        assertThat(longAttribute(span, GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS))
                .isEqualTo(3);
        assertThat(longAttribute(span, GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS))
                .isEqualTo(5);
        assertThat(longAttribute(span, GenAiAttributes.USAGE_REASONING_OUTPUT_TOKENS))
                .isEqualTo(11);
    }

    @Test
    void chatOmitsGenAiUsageDetailAttributesWhenProviderDoesNotReportThem() {
        // Arrange
        UsageDetails usage =
                UsageDetails.builder().inputTokens(12).outputTokens(7).build();
        ChatResponse response = ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "output")))
                .model("gpt-5.4")
                .usage(usage)
                .build();
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new ImmediateChatClient(response), telemetry);
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "prompt")),
                ChatOptions.builder().model("gpt-5.4").build());

        // Act
        client.completeAsync(request).toCompletableFuture().join();

        // Assert
        SpanData span = onlySpan();
        assertThat(longAttribute(span, GenAiAttributes.USAGE_CACHE_CREATION_INPUT_TOKENS))
                .isNull();
        assertThat(longAttribute(span, GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS))
                .isNull();
        assertThat(longAttribute(span, GenAiAttributes.USAGE_REASONING_OUTPUT_TOKENS))
                .isNull();
    }

    @Test
    void chatDefaultsRecordConventionsWithoutSensitivePayloads() {
        // Arrange
        UsageDetails usage =
                UsageDetails.builder().inputTokens(12).outputTokens(7).build();
        ChatResponse response = ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "secret output")))
                .responseId("response-secret")
                .conversationId("conversation-secret")
                .model("gpt-5.4")
                .finishReason(com.microsoft.agents.core.FinishReason.STOP)
                .usage(usage)
                .build();
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new ImmediateChatClient(response), telemetry);
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "secret prompt")),
                ChatOptions.builder()
                        .model("gpt-5.4")
                        .conversationId("conversation-secret")
                        .build());

        // Act
        client.completeAsync(request).toCompletableFuture().join();

        // Assert
        SpanData span = onlySpan();
        assertThat(span.getName()).isEqualTo("chat gpt-5.4");
        assertThat(attribute(span, GenAiAttributes.OPERATION_NAME)).isEqualTo("chat");
        assertThat(attribute(span, GenAiAttributes.PROVIDER_NAME)).isEqualTo("openai");
        assertThat(attribute(span, GenAiAttributes.REQUEST_MODEL)).isEqualTo("gpt-5.4");
        assertThat(longAttribute(span, GenAiAttributes.USAGE_INPUT_TOKENS)).isEqualTo(12);
        assertThat(attribute(span, GenAiAttributes.INPUT_MESSAGES)).isNull();
        assertThat(attribute(span, GenAiAttributes.OUTPUT_MESSAGES)).isNull();
        assertThat(attribute(span, GenAiAttributes.RESPONSE_ID)).isNull();
        assertThat(span.toString()).doesNotContain("secret prompt", "secret output", "response-secret");
        assertThat(metrics.collectAllMetrics())
                .extracting(metric -> metric.getName())
                .contains("gen_ai.client.operation.duration", "gen_ai.client.token.usage");
    }

    @Test
    void contentOptInRedactsCredentialsConfiguredKeysControlsAndOversizeValues() {
        // Arrange
        TelemetryContentPolicy contentPolicy = TelemetryContentPolicy.builder()
                .captureContent(true)
                .redactedKeys(Set.of("custom"))
                .maxValueCharacters(32)
                .build();
        AgentFrameworkTelemetry telemetry = telemetry()
                .providerName("openai")
                .identifierPolicy(IdentifierPolicy.PLAIN)
                .contentPolicy(contentPolicy)
                .build();
        Message input = Message.builder(Role.ASSISTANT)
                .contents(List.of(new FunctionCallContent(
                        "call-id",
                        "lookup",
                        StateValue.object(Map.of(
                                "api_key", StateValue.string("credential-value"),
                                "custom", StateValue.string("custom-value"),
                                "note", StateValue.string("line-one\n" + "x".repeat(100)))))))
                .build();
        ChatResponse response = ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "ok")))
                .build();
        OpenTelemetryChatClient client = new OpenTelemetryChatClient(new ImmediateChatClient(response), telemetry);

        // Act
        client.completeAsync(new ChatClientRequest(List.of(input), ChatOptions.empty()))
                .toCompletableFuture()
                .join();

        // Assert
        String captured = attribute(onlySpan(), GenAiAttributes.INPUT_MESSAGES);
        assertThat(captured)
                .contains("[REDACTED]", "...[truncated]", "line-one?")
                .doesNotContain("credential-value", "custom-value", "\n");
    }

    @Test
    void hashedIdentifiersRemainDistinctBeyondPlainTextCaptureLimit() {
        // Arrange
        AgentFrameworkTelemetry telemetry = telemetry()
                .identifierPolicy(IdentifierPolicy.HASH)
                .contentPolicy(
                        TelemetryContentPolicy.builder().maxValueCharacters(1).build())
                .build();
        TelemetrySanitizer sanitizer = new TelemetrySanitizer(telemetry);

        // Act
        String first = sanitizer.identifier("same-prefix-first");
        String second = sanitizer.identifier("same-prefix-second");

        // Assert
        assertThat(first).hasSize(64).isNotEqualTo(second);
    }

    @Test
    void nestedChatDecoratorsEmitOneSpan() {
        // Arrange
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        ChatClient base = new ImmediateChatClient(ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "ok")))
                .build());
        ChatClient nested = new OpenTelemetryChatClient(new OpenTelemetryChatClient(base, telemetry), telemetry);

        // Act
        nested.completeAsync(new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void summarizationInternalChatIsSuppressed() {
        // Arrange
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        ChatClient observedSummarizer = new OpenTelemetryChatClient(
                new ImmediateChatClient(ChatResponse.builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "summary")))
                        .build()),
                telemetry);
        List<Message> history = List.of(
                Message.text(Role.USER, "one"),
                Message.text(Role.ASSISTANT, "one answer"),
                Message.text(Role.USER, "two"),
                Message.text(Role.ASSISTANT, "two answer"),
                Message.text(Role.USER, "recent"));

        // Act
        Compactions.compactAsync(new SummarizationCompactionStrategy(observedSummarizer, 3, 1, 1_000), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(spans.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void agentContextParentsChatAcrossVirtualThreadAndConcurrentRunsDoNotLeak() {
        // Arrange
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        ChatClient chat = new OpenTelemetryChatClient(
                new ImmediateChatClient(ChatResponse.builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "answer")))
                        .model("gpt-5.4")
                        .build()),
                telemetry);
        try (VirtualChatAgent base = new VirtualChatAgent(chat);
                OpenTelemetryAgent<Void> agent = new OpenTelemetryAgent<>(base, telemetry)) {
            // Act
            CompletionStage<AgentResponse<Void>> first = agent.runAsync("one");
            CompletionStage<AgentResponse<Void>> second = agent.runAsync("two");
            CompletableFuture.allOf(first.toCompletableFuture(), second.toCompletableFuture())
                    .join();

            // Assert
            List<SpanData> agentSpans = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().startsWith("invoke_agent"))
                    .toList();
            List<SpanData> chatSpans = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().startsWith("chat"))
                    .toList();
            assertThat(agentSpans).hasSize(2);
            assertThat(chatSpans).hasSize(2);
            assertThat(chatSpans)
                    .extracting(span -> span.getParentSpanContext().getSpanId())
                    .containsExactlyInAnyOrderElementsOf(agentSpans.stream()
                            .map(span -> span.getSpanContext().getSpanId())
                            .toList());
            assertThat(io.opentelemetry.api.trace.Span.current()
                            .getSpanContext()
                            .isValid())
                    .isFalse();
        }
    }

    @Test
    void functionMiddlewareOmitsPayloadByDefaultAndOptInAlwaysRedactsCredentials() {
        // Arrange
        FunctionTool tool = mock(FunctionTool.class);
        when(tool.name()).thenReturn("lookup");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ToolInvocationContext invocation = new ToolInvocationContext(
                    "run", "call", new InvocationId("invocation"), new DefaultRunCancellation(), executor, Map.of());
            StateValue.ObjectValue arguments = StateValue.object(
                    Map.of("token", StateValue.string("credential"), "city", StateValue.string("Paris")));
            FunctionMiddlewareContext context = new FunctionMiddlewareContext(
                    null, new ToolInvocationInterceptContext(tool, invocation, arguments), new MiddlewareMetadata());
            OpenTelemetryFunctionMiddleware defaults =
                    new OpenTelemetryFunctionMiddleware(telemetry().build());

            // Act
            defaults.invokeAsync(
                            context,
                            ignored -> CompletableFuture.completedFuture(
                                    StateValue.object(Map.of("secret", StateValue.string("result-secret")))))
                    .toCompletableFuture()
                    .join();

            // Assert
            SpanData defaultSpan = onlySpan();
            assertThat(attribute(defaultSpan, GenAiAttributes.TOOL_CALL_ARGUMENTS))
                    .isNull();
            assertThat(attribute(defaultSpan, GenAiAttributes.TOOL_CALL_RESULT)).isNull();
            spans.reset();

            // Arrange opt-in
            AgentFrameworkTelemetry optedIn = telemetry()
                    .contentPolicy(TelemetryContentPolicy.builder()
                            .captureContent(true)
                            .maxValueCharacters(64)
                            .build())
                    .build();

            // Act opt-in
            new OpenTelemetryFunctionMiddleware(optedIn)
                    .invokeAsync(
                            context,
                            ignored -> CompletableFuture.completedFuture(
                                    StateValue.object(Map.of("secret", StateValue.string("result-secret")))))
                    .toCompletableFuture()
                    .join();

            // Assert opt-in
            SpanData optedInSpan = onlySpan();
            assertThat(attribute(optedInSpan, GenAiAttributes.TOOL_CALL_ARGUMENTS))
                    .contains("Paris", "[REDACTED]")
                    .doesNotContain("credential");
            assertThat(attribute(optedInSpan, GenAiAttributes.TOOL_CALL_RESULT))
                    .contains("[REDACTED]")
                    .doesNotContain("result-secret");
        } finally {
            executor.close();
        }
    }

    @Test
    void workflowFacadeRecordsStableNameRunAndMetrics() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("echo-workflow", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("echo", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));
        Workflow<String, String> workflow = builder.entry(node).output(node).build();
        AgentFrameworkTelemetry telemetry =
                telemetry().identifierPolicy(IdentifierPolicy.HASH).build();
        try (OpenTelemetryWorkflow<String, String> observed = new OpenTelemetryWorkflow<>(workflow, telemetry, true)) {
            // Act
            observed.runAsync("hello").toCompletableFuture().join();

            // Assert
            SpanData span = onlySpan();
            assertThat(span.getName()).isEqualTo("invoke_workflow echo-workflow");
            assertThat(attribute(span, GenAiAttributes.OPERATION_NAME)).isEqualTo("invoke_workflow");
            assertThat(attribute(span, GenAiAttributes.WORKFLOW_NAME)).isEqualTo("echo-workflow");
            assertThat(attribute(span, GenAiAttributes.RUN_ID)).hasSize(64);
            assertThat(attribute(span, "agent_framework.workflow.input")).isNull();
            assertThat(metrics.collectAllMetrics())
                    .extracting(metric -> metric.getName())
                    .contains("gen_ai.invoke_workflow.duration");
        }
    }

    @Test
    void workflowStreamingRecordsLifecycleEventsAndStartsOnSubscription() throws Exception {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("stream-workflow", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("echo", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));
        Workflow<String, String> workflow = builder.entry(node).output(node).build();
        CountDownLatch terminal = new CountDownLatch(1);
        try (OpenTelemetryWorkflow<String, String> observed =
                new OpenTelemetryWorkflow<>(workflow, telemetry().build(), true)) {
            Flow.Publisher<com.microsoft.agents.workflows.WorkflowEvent> publisher = observed.runStreaming("hello");
            assertThat(spans.getFinishedSpanItems()).isEmpty();

            // Act
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(com.microsoft.agents.workflows.WorkflowEvent item) {}

                @Override
                public void onError(Throwable throwable) {
                    terminal.countDown();
                }

                @Override
                public void onComplete() {
                    terminal.countDown();
                }
            });
            assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();

            // Assert
            SpanData span = onlySpan();
            assertThat(span.getEvents())
                    .extracting(event -> event.getName())
                    .anyMatch(name -> name.startsWith("agent_framework.workflow."));
        }
    }

    @Test
    void metricDimensionsUseExplicitLowCardinalitySetsAndExcludeIdentifiers() {
        // Arrange
        AgentFrameworkTelemetry telemetry = telemetry()
                .providerName("openai")
                .identifierPolicy(IdentifierPolicy.PLAIN)
                .build();
        Attributes spanAttributes = Attributes.builder()
                .put(GenAiAttributes.REQUEST_MODEL, "gpt-5.4")
                .put(GenAiAttributes.RESPONSE_ID, "response")
                .put(GenAiAttributes.CONVERSATION_ID, "conversation")
                .put(GenAiAttributes.RUN_ID, "run")
                .put(GenAiAttributes.AGENT_ID, "agent")
                .put(GenAiAttributes.TOOL_CALL_ID, "call")
                .put(GenAiAttributes.INVOCATION_ID, "invocation")
                .build();
        TelemetryOperation operation = TelemetryOperation.start(
                telemetry,
                "chat gpt-5.4",
                SpanKind.CLIENT,
                "chat",
                "openai",
                TelemetryContext.CHAT_ACTIVE,
                spanAttributes);

        // Act
        telemetry
                .metrics()
                .recordUsage(
                        UsageDetails.builder().inputTokens(3).outputTokens(5).build(), operation.metricAttributes());
        operation.success();
        TelemetryOperation agentOperation = TelemetryOperation.start(
                telemetry,
                "invoke_agent Agent",
                SpanKind.INTERNAL,
                "invoke_agent",
                null,
                TelemetryContext.AGENT_ACTIVE,
                Attributes.builder()
                        .put(GenAiAttributes.AGENT_NAME, "Agent")
                        .put(GenAiAttributes.AGENT_ID, "agent-id")
                        .put(GenAiAttributes.RUN_ID, "agent-run")
                        .build());
        agentOperation.success();
        TelemetryOperation workflowOperation = TelemetryOperation.start(
                telemetry,
                "invoke_workflow Workflow",
                SpanKind.INTERNAL,
                "invoke_workflow",
                null,
                TelemetryContext.WORKFLOW_ACTIVE,
                Attributes.builder()
                        .put(GenAiAttributes.WORKFLOW_NAME, "Workflow")
                        .put("agent_framework.workflow.resumed", true)
                        .put(GenAiAttributes.RUN_ID, "workflow-run")
                        .build());
        workflowOperation.success();
        TelemetryOperation toolOperation = TelemetryOperation.start(
                telemetry,
                "execute_tool lookup",
                SpanKind.INTERNAL,
                "execute_tool",
                null,
                TelemetryContext.TOOL_ACTIVE,
                Attributes.builder()
                        .put(GenAiAttributes.TOOL_NAME, "lookup")
                        .put(GenAiAttributes.TOOL_TYPE, "function")
                        .put(GenAiAttributes.TOOL_CALL_ID, "call")
                        .put(GenAiAttributes.INVOCATION_ID, "invocation")
                        .build());
        toolOperation.success();

        // Assert
        assertThat(metricAttributeKeys("gen_ai.client.operation.duration"))
                .containsOnly(Set.of(
                        GenAiAttributes.OPERATION_NAME, GenAiAttributes.PROVIDER_NAME, GenAiAttributes.REQUEST_MODEL));
        assertThat(metricAttributeKeys("gen_ai.client.token.usage"))
                .allSatisfy(keys -> assertThat(keys)
                        .containsExactlyInAnyOrder(
                                GenAiAttributes.OPERATION_NAME,
                                GenAiAttributes.PROVIDER_NAME,
                                GenAiAttributes.REQUEST_MODEL,
                                GenAiAttributes.TOKEN_TYPE));
        assertThat(metricAttributeKeys("gen_ai.client.operation.duration"))
                .allSatisfy(keys ->
                        assertThat(keys).doesNotContainAnyElementsOf(TelemetryMetrics.IDENTIFIER_ATTRIBUTE_KEYS));
        assertThat(metricAttributeKeys("gen_ai.client.token.usage"))
                .allSatisfy(keys ->
                        assertThat(keys).doesNotContainAnyElementsOf(TelemetryMetrics.IDENTIFIER_ATTRIBUTE_KEYS));
        assertThat(metricAttributeKeys("gen_ai.invoke_agent.duration"))
                .containsOnly(Set.of(GenAiAttributes.OPERATION_NAME, GenAiAttributes.AGENT_NAME));
        assertThat(metricAttributeKeys("gen_ai.invoke_workflow.duration"))
                .containsOnly(Set.of(
                        GenAiAttributes.OPERATION_NAME,
                        GenAiAttributes.WORKFLOW_NAME,
                        "agent_framework.workflow.resumed"));
        assertThat(metricAttributeKeys("gen_ai.execute_tool.duration"))
                .containsOnly(
                        Set.of(GenAiAttributes.OPERATION_NAME, GenAiAttributes.TOOL_NAME, GenAiAttributes.TOOL_TYPE));
        assertThat(metrics.collectAllMetrics())
                .flatExtracting(metric -> metric.getHistogramData().getPoints())
                .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet())
                        .extracting(AttributeKey::getKey)
                        .doesNotContainAnyElementsOf(TelemetryMetrics.IDENTIFIER_ATTRIBUTE_KEYS));
    }

    @Test
    void concurrentWorkflowRunsWithSharedCancellationKeepDistinctAgentParents() throws Exception {
        // Arrange
        AgentFrameworkTelemetry telemetry = telemetry().providerName("openai").build();
        BarrierAgent baseAgent = new BarrierAgent(2);
        OpenTelemetryAgent<Void> observedAgent = new OpenTelemetryAgent<>(baseAgent, telemetry);
        Workflow<Message, Message> workflow = agentWorkflow("agent-workflow", observedAgent);
        DefaultRunCancellation sharedCancellation = new DefaultRunCancellation();
        com.microsoft.agents.workflows.WorkflowValueEncoder defaultEncoder =
                com.microsoft.agents.workflows.WorkflowValueEncoder.defaultEncoder();
        com.microsoft.agents.workflows.WorkflowRunOptions workflowOptions =
                com.microsoft.agents.workflows.WorkflowRunOptions.builder()
                        .valueEncoder(value -> value instanceof Message message
                                ? StateValue.string(message.text())
                                : defaultEncoder.encode(value))
                        .build();
        try (OpenTelemetryWorkflow<Message, Message> observed =
                new OpenTelemetryWorkflow<>(workflow, telemetry, true)) {
            // Act
            RunHandle<com.microsoft.agents.workflows.WorkflowRunResult<Message>> first =
                    observed.startRun(Message.text(Role.USER, "one"), workflowOptions, sharedCancellation);
            RunHandle<com.microsoft.agents.workflows.WorkflowRunResult<Message>> second =
                    observed.startRun(Message.text(Role.USER, "two"), workflowOptions, sharedCancellation);
            assertThat(baseAgent.started.await(5, TimeUnit.SECONDS))
                    .as("remaining agent starts: %s", baseAgent.started.getCount())
                    .isTrue();
            baseAgent.release.countDown();
            CompletableFuture.allOf(
                            first.resultAsync().toCompletableFuture(),
                            second.resultAsync().toCompletableFuture())
                    .join();

            // Assert
            List<SpanData> workflowSpans = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().startsWith("invoke_workflow"))
                    .toList();
            List<SpanData> agentSpans = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().startsWith("invoke_agent"))
                    .toList();
            assertThat(workflowSpans).hasSize(2);
            assertThat(agentSpans).hasSize(2);
            assertThat(agentSpans)
                    .extracting(span -> span.getParentSpanContext().getSpanId())
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(workflowSpans.stream()
                            .map(span -> span.getSpanContext().getSpanId())
                            .toList());
            assertThat(telemetry.contextRegistry().size()).isZero();
        }
    }

    private static Workflow<Message, Message> agentWorkflow(String id, Agent<Void> observedAgent) {
        WorkflowBuilder<Message, Message> builder = WorkflowBuilder.create(id, Message.class, Message.class);
        WorkflowNode<Message, Message> node = builder.addNode("agent", new AgentExecutor(observedAgent));
        return builder.entry(node).output(node).build();
    }

    private AgentFrameworkTelemetry.Builder telemetry() {
        return AgentFrameworkTelemetry.builder(openTelemetry).instrumentationVersion("test");
    }

    private SpanData onlySpan() {
        for (int attempt = 0; attempt < 100 && spans.getFinishedSpanItems().isEmpty(); attempt++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        return spans.getFinishedSpanItems().getFirst();
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static Long longAttribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.longKey(key));
    }

    private List<Set<String>> metricAttributeKeys(String metricName) {
        return metrics.collectAllMetrics().stream()
                .filter(metric -> metricName.equals(metric.getName()))
                .flatMap(metric -> metric.getHistogramData().getPoints().stream())
                .map(point -> point.getAttributes().asMap().keySet().stream()
                        .map(AttributeKey::getKey)
                        .collect(Collectors.toUnmodifiableSet()))
                .toList();
    }

    private static final class ImmediateChatClient implements ChatClient {
        private final ChatResponse response;

        private ImmediateChatClient(ChatResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean emitted = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count > 0 && emitted.compareAndSet(false, true)) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class VirtualChatAgent implements Agent<Void> {
        private final AgentMetadata metadata = new AgentMetadata("virtual-agent", "Virtual Agent", "test agent");

        private final ChatClient chatClient;

        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private VirtualChatAgent(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            CompletableFuture.runAsync(
                    () -> {
                        AgentRunContext runContext = new AgentRunContext(
                                java.util.UUID.randomUUID().toString(),
                                metadata,
                                Instant.now(),
                                messages,
                                options,
                                source.cancellation(),
                                options.metadata());
                        ChatClientRequest request = new ChatClientRequest(
                                messages,
                                ChatOptions.builder().model("gpt-5.4").build(),
                                List.of(),
                                com.microsoft.agents.tools.ToolMode.NONE,
                                runContext);
                        chatClient.completeAsync(request, source.cancellation()).whenComplete((response, failure) -> {
                            if (failure != null) {
                                source.tryFail(failure);
                            } else {
                                source.tryComplete(AgentResponse.<Void>builder()
                                        .messages(response.messages())
                                        .responseId(response.responseId())
                                        .finishReason(response.finishReason())
                                        .usage(response.usage())
                                        .build());
                            }
                        });
                    },
                    executor);
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onError(new UnsupportedOperationException());
                    }

                    @Override
                    public void cancel() {}
                });
            };
        }

        @Override
        public void close() {
            executor.close();
        }
    }

    private static final class BarrierAgent implements Agent<Void> {
        private final CountDownLatch started;

        private final CountDownLatch release = new CountDownLatch(1);

        private BarrierAgent(int expectedRuns) {
            started = new CountDownLatch(expectedRuns);
        }

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("barrier-agent", "Barrier Agent", null);
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            started.countDown();
            CompletableFuture.runAsync(() -> {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        source.tryFail(new IllegalStateException("release timed out"));
                        return;
                    }
                    source.tryComplete(AgentResponse.<Void>builder()
                            .messages(List.of(messages.getLast()))
                            .build());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    source.tryFail(exception);
                }
            });
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            throw new UnsupportedOperationException();
        }
    }
}
