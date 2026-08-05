// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.conformance.EventHistoryFixture;
import com.microsoft.agents.conformance.WorkflowCheckpointFixture;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoreConformanceTest {
    private static final String CORE_002_RESOURCE = "conformance/v1/core/jcf-core-002-response-aggregation.json";

    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void jcfCore001_shouldBindMessagesAndAllDeclaredContentToProductionTypes() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-CORE-001");
        ContentStateCodec codec = new ContentStateCodec();

        // Act
        List<Message> messages = array(fixture.input().require("messages")).values().stream()
                .map(CoreConformanceTest::message)
                .toList();
        List<String> contentKinds = messages.stream()
                .flatMap(message -> message.contents().stream())
                .map(Content::kind)
                .toList();
        List<Content> serializedRoundTrips = messages.stream()
                .flatMap(message -> message.contents().stream())
                .map(content -> codec.decode(codec.encode(content), codec.currentVersion()))
                .toList();

        // Assert
        assertThat(messages)
                .extracting(message -> message.role().value())
                .containsExactlyElementsOf(strings(array(fixture.expected().require("roles"))));
        assertThat(contentKinds)
                .containsExactlyElementsOf(strings(array(fixture.expected().require("contentKinds"))));
        assertThat(messages)
                .extracting(Message::text)
                .containsExactlyElementsOf(strings(array(fixture.expected().require("messageTexts"))));
        assertThat(messages.stream()
                        .filter(message -> message.role().equals(Role.ASSISTANT))
                        .findFirst()
                        .orElseThrow()
                        .text())
                .isEqualTo(string(fixture.expected().require("assistantText")));
        assertThat(serializedRoundTrips)
                .containsExactlyElementsOf(messages.stream()
                        .flatMap(message -> message.contents().stream())
                        .toList());

        FunctionCallContent call = messages.stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionCallContent.class::isInstance)
                .map(FunctionCallContent.class::cast)
                .findFirst()
                .orElseThrow();
        long results = messages.stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionResultContent.class::isInstance)
                .map(FunctionResultContent.class::cast)
                .filter(result -> result.callId().equals(call.callId()))
                .count();
        ConformanceValue.ObjectValue expectedPair = object(
                array(fixture.expected().require("callResultPairs")).values().getFirst());
        assertThat(call.callId()).isEqualTo(string(expectedPair.require("callId")));
        assertThat(results)
                .isEqualTo(integer(expectedPair.require("resultCount")).longValueExact());
    }

    @Test
    void jcfCore002_shouldBindExactSequentialAggregationToProductionAggregator() throws IOException {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-CORE-002");
        JsonNode raw = readRawCore002();

        // Act
        ArrayList<AgentResponseUpdate> updates = new ArrayList<>();
        for (JsonNode update : raw.path("input").path("updates")) {
            AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                    .sequence(update.path("sequence").longValue());
            if (update.has("role")) {
                builder.role(Role.of(update.path("role").textValue()));
            }
            if (update.has("contents")) {
                ArrayList<Content> contents = new ArrayList<>();
                for (JsonNode content : update.path("contents")) {
                    contents.add(new TextContent(content.path("text").textValue()));
                }
                builder.contents(contents);
            }
            if (update.has("usage")) {
                builder.usage(new UsageDetails(stateObject(update.path("usage"))));
            }
            if (update.has("finishReason")) {
                builder.finishReason(FinishReason.of(update.path("finishReason").textValue()));
            }
            updates.add(builder.build());
        }
        AgentResponse<Void> response = ResponseAggregator.aggregateAgent(updates);

        // Assert
        assertThat(response.messages())
                .hasSize(integer(fixture.expected().require("messageCount")).intValueExact());
        assertThat(response.messages().getFirst().role().value())
                .isEqualTo(string(fixture.expected().require("role")));
        assertThat(response.text()).isEqualTo(string(fixture.expected().require("text")));
        assertThat(response.finishReason().value())
                .isEqualTo(string(fixture.expected().require("finishReason")));
        assertThat(response.updateSequences())
                .containsExactlyElementsOf(integers(array(fixture.expected().require("updateSequences"))));
        ConformanceValue.ObjectValue expectedUsage = object(fixture.expected().require("usage"));
        LinkedHashMap<String, StateValue> expectedUsageValues = new LinkedHashMap<>();
        expectedUsage
                .values()
                .forEach((key, value) -> expectedUsageValues.put(key, StateValue.integer(integer(value))));
        assertThat(response.usage().values()).isEqualTo(expectedUsageValues);
        assertThat(response.usage().values())
                .doesNotContainKeys(strings(array(fixture.expected().require("droppedUsageKeys")))
                        .toArray(String[]::new));
    }

    @Test
    void jcfCore003_shouldBindRunOptionsToDefensiveProductionValue() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-CORE-003");
        ConformanceValue.ObjectValue options = object(fixture.input().require("options"));
        LinkedHashMap<String, StateValue> callerMetadata =
                new LinkedHashMap<>(stateObject(object(options.require("metadata"))));
        Map<String, StateValue> callerMetadataSnapshot = Map.copyOf(callerMetadata);

        // Act
        RunOptions runOptions = RunOptions.builder()
                .maxIterations(integer(options.require("maxIterations")).intValueExact())
                .maxFunctionCalls(integer(options.require("maxFunctionCalls")).intValueExact())
                .metadata(callerMetadata)
                .build();
        boolean callerOptionsUnchanged = callerMetadata.equals(callerMetadataSnapshot);
        callerMetadata.put("mutated", StateValue.bool(true));

        // Assert
        ConformanceValue.ObjectValue effectiveOptions =
                object(fixture.expected().require("effectiveOptions"));
        assertThat(runOptions.maxIterations())
                .isEqualTo(integer(effectiveOptions.require("maxIterations")).intValueExact());
        assertThat(runOptions.maxFunctionCalls())
                .isEqualTo(integer(effectiveOptions.require("maxFunctionCalls")).intValueExact());
        assertThat(runOptions.metadata()).isEqualTo(stateObject(object(effectiveOptions.require("metadata"))));
        assertThat(callerOptionsUnchanged).isEqualTo(bool(fixture.expected().require("callerOptionsUnchanged")));
    }

    @Test
    void jcfCore004_shouldBindChatOptionsAndNormalizedFinishReason() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-CORE-004");
        ConformanceValue.ObjectValue options = object(fixture.input().require("options"));

        // Act
        ChatOptions chatOptions = ChatOptions.builder()
                .temperature(decimal(options.require("temperature")).doubleValue())
                .maxTokens(integer(options.require("maxTokens")).intValueExact())
                .toolChoice(ToolChoice.fromValue(string(options.require("toolChoice"))))
                .build();
        FinishReason finishReason = FinishReason.of(string(fixture.input().require("providerFinishReason")));

        // Assert
        ConformanceValue.ObjectValue normalized = object(fixture.expected().require("normalized"));
        assertThat(chatOptions.temperature())
                .isEqualTo(decimal(normalized.require("temperature")).doubleValue());
        assertThat(chatOptions.maxTokens())
                .isEqualTo(integer(normalized.require("maxTokens")).intValueExact());
        assertThat(chatOptions.toolChoice().value()).isEqualTo(string(normalized.require("toolChoice")));
        assertThat(finishReason.value()).isEqualTo(string(fixture.expected().require("finishReason")));
    }

    @Test
    void workflowCheckpointV1_shouldUseCoreSerializerAfterSemanticSetArraysAreSorted() {
        // Arrange
        WorkflowCheckpointFixture fixture = (WorkflowCheckpointFixture) catalog.requireCase("JCF-WORKFLOWS-005");
        ConformanceValue.ObjectValue envelope = fixture.envelope();
        ConformanceValue.ObjectValue payload = object(envelope.require("payload"));
        LinkedHashMap<String, StateValue> payloadValues = new LinkedHashMap<>(stateObject(payload));
        List<StateValue> pendingExecutors = array(payload.require("pendingExecutors")).values().stream()
                .map(CoreConformanceTest::string)
                .sorted()
                .map(StateValue::string)
                .map(StateValue.class::cast)
                .toList();
        List<ConformanceValue> bufferedInputs =
                new ArrayList<>(array(payload.require("bufferedInputs")).values());
        bufferedInputs.sort(Comparator.comparing(
                        (ConformanceValue value) -> string(object(value).require("targetId")))
                .thenComparing(value -> string(object(value).require("sourceId"))));
        payloadValues.put("pendingExecutors", StateValue.array(pendingExecutors));
        payloadValues.put(
                "bufferedInputs",
                StateValue.array(
                        bufferedInputs.stream().map(CoreConformanceTest::state).toList()));
        JsonStateSerializer serializer = new JsonStateSerializer(SerializationLimits.defaults());

        // Act
        String encoded = new String(
                serializer.write(StateEnvelope.of(
                        DocumentKind.WORKFLOW_CHECKPOINT,
                        integer(envelope.require("payloadVersion")).intValueExact(),
                        StateValue.object(payloadValues))),
                StandardCharsets.UTF_8);

        // Assert
        assertThat(encoded).isEqualTo(fixture.encoded());
    }

    @Test
    void jcfCore005_shouldBindCancellationToExactlyOneTerminalProductionResult() {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) catalog.requireCase("JCF-CORE-005");
        RunHandleSource<String> source = new RunHandleSource<>();
        AtomicInteger terminals = new AtomicInteger();
        ArrayList<String> updates = new ArrayList<>();
        source.handle().resultAsync().whenComplete((ignored, failure) -> terminals.incrementAndGet());
        updates.add("partial");

        // Act
        boolean first = source.handle().cancel();
        boolean second = source.handle().cancel();
        if (!source.cancellation().isCancellationRequested()) {
            updates.add("late");
        }
        boolean lateSuccess = source.tryComplete("success");

        // Assert
        ConformanceValue.ArrayValue expectedReturns = array(fixture.expected().require("cancelReturnValues"));
        assertThat(List.of(first, second))
                .containsExactlyElementsOf(expectedReturns.values().stream()
                        .map(CoreConformanceTest::bool)
                        .toList());
        assertThat(terminals)
                .hasValue(integer(fixture.expected().require("terminalCount")).intValueExact());
        assertThat(lateSuccess).isEqualTo(bool(fixture.expected().require("successAfterTerminal")));
        assertThat(updates).containsExactly("partial");
        assertThatThrownBy(() ->
                        source.handle().resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
    }

    private BehaviorFixture behavior(String caseId) {
        return (BehaviorFixture) catalog.requireCase(caseId);
    }

    private static Message message(ConformanceValue value) {
        ConformanceValue.ObjectValue object = object(value);
        Role role = Role.of(string(object.require("role")));
        List<Content> contents = array(object.require("contents")).values().stream()
                .map(CoreConformanceTest::content)
                .toList();
        return new Message(role, contents);
    }

    private static Content content(ConformanceValue value) {
        ConformanceValue.ObjectValue object = object(value);
        return switch (string(object.require("kind"))) {
            case "text" -> new TextContent(string(object.require("text")));
            case "data" -> DataContent.fromDataUri(string(object.require("uri")));
            case "reasoning" -> new ReasoningContent(string(object.require("id")), string(object.require("text")));
            case "functionCall" ->
                new FunctionCallContent(
                        string(object.require("callId")),
                        string(object.require("name")),
                        state(object.require("arguments")));
            case "functionResult" ->
                new FunctionResultContent(string(object.require("callId")), state(object.require("result")));
            default -> throw new AssertionError("Unsupported JCF-CORE-001 content kind");
        };
    }

    private static StateValue state(ConformanceValue value) {
        return switch (value) {
            case ConformanceValue.ObjectValue object -> StateValue.object(stateObject(object));
            case ConformanceValue.ArrayValue array ->
                StateValue.array(
                        array.values().stream().map(CoreConformanceTest::state).toList());
            case ConformanceValue.StringValue string -> StateValue.string(string.value());
            case ConformanceValue.NumberValue number -> StateValue.number(number.value());
            case ConformanceValue.BooleanValue bool -> StateValue.bool(bool.value());
            case ConformanceValue.NullValue nullValue -> {
                assertThat(nullValue).isSameAs(ConformanceValue.NullValue.INSTANCE);
                yield StateValue.nullValue();
            }
        };
    }

    private static Map<String, StateValue> stateObject(ConformanceValue.ObjectValue object) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        object.values().forEach((key, value) -> values.put(key, state(value)));
        return values;
    }

    private static Map<String, StateValue> stateObject(JsonNode object) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        object.properties().forEach(entry -> values.put(entry.getKey(), state(entry.getValue())));
        return values;
    }

    private static StateValue state(JsonNode value) {
        if (value.isObject()) {
            return StateValue.object(stateObject(value));
        }
        if (value.isArray()) {
            ArrayList<StateValue> values = new ArrayList<>();
            value.forEach(item -> values.add(state(item)));
            return StateValue.array(values);
        }
        if (value.isTextual()) {
            return StateValue.string(value.textValue());
        }
        if (value.isIntegralNumber()) {
            return StateValue.integer(value.bigIntegerValue());
        }
        if (value.isFloatingPointNumber()) {
            return StateValue.number(new BigDecimal(value.asText()));
        }
        if (value.isBoolean()) {
            return StateValue.bool(value.booleanValue());
        }
        if (value.isNull()) {
            return StateValue.nullValue();
        }
        throw new AssertionError("Unsupported JSON node " + value.getNodeType());
    }

    private static JsonNode readRawCore002() throws IOException {
        InputStream input = CoreConformanceTest.class.getClassLoader().getResourceAsStream(CORE_002_RESOURCE);
        if (input == null) {
            throw new AssertionError("Missing " + CORE_002_RESOURCE);
        }
        try (input) {
            return new ObjectMapper().readTree(input);
        }
    }

    private static ConformanceValue.ObjectValue object(ConformanceValue value) {
        return (ConformanceValue.ObjectValue) value;
    }

    private static ConformanceValue.ArrayValue array(ConformanceValue value) {
        return (ConformanceValue.ArrayValue) value;
    }

    private static String string(ConformanceValue value) {
        return ((ConformanceValue.StringValue) value).value();
    }

    private static boolean bool(ConformanceValue value) {
        return ((ConformanceValue.BooleanValue) value).value();
    }

    private static BigDecimal decimal(ConformanceValue value) {
        return ((ConformanceValue.NumberValue) value).value();
    }

    private static BigInteger integer(ConformanceValue value) {
        return decimal(value).toBigIntegerExact();
    }

    private static List<String> strings(ConformanceValue.ArrayValue values) {
        return values.values().stream().map(CoreConformanceTest::string).toList();
    }

    private static List<Long> integers(ConformanceValue.ArrayValue values) {
        return values.values().stream()
                .map(CoreConformanceTest::integer)
                .map(BigInteger::longValueExact)
                .toList();
    }
}
