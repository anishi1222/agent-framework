// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.conformance.EventHistoryFixture;
import com.microsoft.agents.conformance.WorkflowCheckpointFixture;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
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

    @Test
    void jcfCore006_shouldBindProviderNeutralSchemaAndBoundedStructuredDecoding() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-CORE-006");
        ConformanceValue.ObjectValue configured = object(fixture.input().require("options"));
        StructuredOutputOptions structuredOutput = new StructuredOutputOptions(
                string(configured.require("name")),
                string(configured.require("description")),
                (StateValue.ObjectValue) state(configured.require("schema")),
                bool(configured.require("strict")));
        List<Message> messages = array(fixture.input().require("assistantMessages")).values().stream()
                .map(CoreConformanceTest::object)
                .map(message -> Message.text(Role.of(string(message.require("role"))), string(message.require("text"))))
                .toList();
        AgentResponse<Void> source = AgentResponse.<Void>builder()
                .messages(messages)
                .responseId("response-006")
                .agentId("agent-006")
                .createdAt(Instant.parse("2026-08-12T00:00:00Z"))
                .finishReason(FinishReason.STOP)
                .usage(UsageDetails.of(UsageDetails.INPUT_TOKENS, 7))
                .continuationToken(StateValue.string("continuation-006"))
                .metadata(Map.of("source", StateValue.string("conformance")))
                .updateSequences(List.of(3L, 4L))
                .build();

        // Act
        AgentResponse<StateValue> decoded = StructuredOutputs.decode(source, StructuredOutputDecoder.stateValue());
        AgentResponse<StateValue> noText = StructuredOutputs.decode(
                AgentResponse.builder()
                        .messages(List.of(Message.text(Role.USER, "hello")))
                        .build(),
                StructuredOutputDecoder.stateValue());
        boolean malformedRejected;
        try {
            StructuredOutputs.decode(
                    AgentResponse.builder()
                            .messages(List.of(Message.text(
                                    Role.ASSISTANT, string(fixture.input().require("malformedText")))))
                            .build(),
                    StructuredOutputDecoder.stateValue());
            malformedRejected = false;
        } catch (StructuredOutputException expected) {
            malformedRejected = true;
        }
        boolean boundedJsonEnforced;
        try {
            StructuredOutputs.parseJson(
                    string(fixture.input().require("oversizedText")),
                    new SerializationLimits(
                            integer(fixture.input().require("maxDocumentBytes")).longValueExact(),
                            128,
                            1_000,
                            100,
                            100));
            boundedJsonEnforced = false;
        } catch (SerializationException expected) {
            boundedJsonEnforced = true;
        }
        boolean providerTypesInPublicApi = Arrays.stream(StructuredOutputOptions.class.getRecordComponents())
                .anyMatch(component -> component.getType().getName().startsWith("com.microsoft.agents.providers."));

        // Assert
        StateValue expectedDecoded = state(fixture.expected().require("decoded"));
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("decoded", conformance(decoded.value()));
        actual.put(
                "schemaPreserved",
                new ConformanceValue.BooleanValue(
                        structuredOutput.schema().equals(state(configured.require("schema")))));
        actual.put(
                "lastNonEmptyAssistantText",
                new ConformanceValue.BooleanValue(decoded.value().equals(expectedDecoded)));
        actual.put(
                "responseMetadataPreserved",
                new ConformanceValue.BooleanValue(decoded.responseId().equals(source.responseId())
                        && decoded.agentId().equals(source.agentId())
                        && decoded.createdAt().equals(source.createdAt())
                        && decoded.finishReason().equals(source.finishReason())
                        && decoded.usage().equals(source.usage())
                        && decoded.continuationToken().equals(source.continuationToken())
                        && decoded.metadata().equals(source.metadata())
                        && decoded.updateSequences().equals(source.updateSequences())));
        actual.put("noAssistantTextProducesNull", new ConformanceValue.BooleanValue(noText.value() == null));
        actual.put("malformedJsonRejected", new ConformanceValue.BooleanValue(malformedRejected));
        actual.put("boundedJsonEnforced", new ConformanceValue.BooleanValue(boundedJsonEnforced));
        actual.put("providerTypesInPublicApi", new ConformanceValue.BooleanValue(providerTypesInPublicApi));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfCore007_shouldBindProviderNeutralEmbeddingBatchContract() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-CORE-007");
        ArrayList<String> callerValues =
                new ArrayList<>(strings(array(fixture.input().require("values"))));
        List<String> callerSnapshot = List.copyOf(callerValues);
        ConformanceValue.ObjectValue configured = object(fixture.input().require("options"));
        EmbeddingGenerationOptions options = EmbeddingGenerationOptions.builder()
                .model(string(configured.require("model")))
                .dimensions(integer(configured.require("dimensions")).intValueExact())
                .metadata(stateObject(object(configured.require("metadata"))))
                .build();
        List<FloatEmbeddingVector> vectors = array(fixture.input().require("vectors")).values().stream()
                .map(CoreConformanceTest::array)
                .map(value -> new FloatEmbeddingVector(value.values().stream()
                        .map(CoreConformanceTest::decimal)
                        .map(BigDecimal::doubleValue)
                        .toList()))
                .toList();
        EmbeddingClient<String, FloatEmbeddingVector, EmbeddingGenerationOptions> client =
                (values, suppliedOptions, cancellation) -> {
                    if (cancellation.isCancellationRequested()) {
                        return java.util.concurrent.CompletableFuture.failedFuture(new RunCancelledException());
                    }
                    List<Embedding<FloatEmbeddingVector>> embeddings = java.util.stream.IntStream.range(
                                    0, values.size())
                            .mapToObj(index -> new Embedding<>(
                                    vectors.get(index),
                                    suppliedOptions.model(),
                                    null,
                                    Map.of("source", StateValue.string(values.get(index)))))
                            .toList();
                    return java.util.concurrent.CompletableFuture.completedFuture(new GeneratedEmbeddings<>(
                            embeddings, suppliedOptions, UsageDetails.of(UsageDetails.INPUT_TOKENS, values.size())));
                };

        // Act
        GeneratedEmbeddings<FloatEmbeddingVector, EmbeddingGenerationOptions> generated = client.generateAsync(
                        callerValues, options)
                .toCompletableFuture()
                .join();
        callerValues.set(0, "mutated");
        boolean invalidDimensionRejected;
        try {
            EmbeddingGenerationOptions.builder().dimensions(0).build();
            invalidDimensionRejected = false;
        } catch (ValidationException expected) {
            invalidDimensionRejected = true;
        }
        boolean nonFiniteVectorRejected;
        try {
            new FloatEmbeddingVector(List.of(Double.POSITIVE_INFINITY));
            nonFiniteVectorRejected = false;
        } catch (ValidationException expected) {
            nonFiniteVectorRejected = true;
        }
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        cancellation.cancel();
        boolean cancelledRunRejected;
        try {
            client.generateAsync(callerSnapshot, options, cancellation)
                    .toCompletableFuture()
                    .join();
            cancelledRunRejected = false;
        } catch (CompletionException expected) {
            cancelledRunRejected = expected.getCause() instanceof RunCancelledException;
        }
        boolean providerTypesInPublicApi = Arrays.stream(EmbeddingClient.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(type -> type.getName().startsWith("com.microsoft.agents.providers."));

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("count", new ConformanceValue.NumberValue(BigDecimal.valueOf(generated.size())));
        actual.put(
                "dimensions",
                new ConformanceValue.ArrayValue(generated.embeddings().stream()
                        .map(Embedding::dimensions)
                        .map(BigDecimal::valueOf)
                        .map(ConformanceValue.NumberValue::new)
                        .map(ConformanceValue.class::cast)
                        .toList()));
        actual.put("model", new ConformanceValue.StringValue(generated.get(0).model()));
        actual.put(
                "inputTokens",
                new ConformanceValue.NumberValue(
                        new BigDecimal(generated.usage().inputTokens().orElseThrow())));
        actual.put(
                "orderPreserved",
                new ConformanceValue.BooleanValue(generated.embeddings().stream()
                        .map(embedding ->
                                ((StateValue.StringValue) embedding.metadata().get("source")).value())
                        .toList()
                        .equals(callerSnapshot)));
        actual.put(
                "callerCollectionsUnchanged",
                new ConformanceValue.BooleanValue(
                        generated.options().metadata().equals(stateObject(object(configured.require("metadata"))))
                                && callerSnapshot.equals(
                                        strings(array(fixture.input().require("values"))))));
        actual.put("invalidDimensionRejected", new ConformanceValue.BooleanValue(invalidDimensionRejected));
        actual.put("nonFiniteVectorRejected", new ConformanceValue.BooleanValue(nonFiniteVectorRejected));
        actual.put("cancelledRunRejected", new ConformanceValue.BooleanValue(cancelledRunRejected));
        actual.put("providerTypesInPublicApi", new ConformanceValue.BooleanValue(providerTypesInPublicApi));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfTelemetry001_shouldBindRuntimeFeatureStageMetadataAndWarningDeduplication() {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-TELEMETRY-001");
        List<String> configuredIds = array(fixture.input().require("experimentalFeatureIds")).values().stream()
                .map(CoreConformanceTest::string)
                .toList();
        List<Class<?>> annotations = List.of(Experimental.class, ReleaseCandidate.class);
        List<ElementType> requiredTargets = List.of(
                ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE);
        FeatureStages.clearWarningsForTesting();
        ArrayList<String> warnings = new ArrayList<>();

        // Act
        boolean runtimeRetention = annotations.stream()
                .allMatch(
                        annotation -> annotation.getAnnotation(Retention.class).value() == RetentionPolicy.RUNTIME);
        boolean lifecycleTargetsPresent = annotations.stream().allMatch(annotation -> {
            List<ElementType> targets =
                    Arrays.asList(annotation.getAnnotation(Target.class).value());
            return targets.containsAll(requiredTargets);
        });
        boolean featureIdsMatch = Arrays.stream(ExperimentalFeature.values())
                .map(ExperimentalFeature::id)
                .toList()
                .equals(configuredIds);
        FeatureStageMetadata metadata =
                FeatureStages.describe(ConformanceExperimentalBase.class).orElseThrow();
        boolean stageMetadataDiscoverable = metadata.stage() == FeatureStage.EXPERIMENTAL
                && metadata.featureId().equals("FUNCTIONAL_WORKFLOWS");
        boolean inheritedTypeMetadata = FeatureStages.describe(ConformanceExperimentalChild.class)
                .map(metadataValue -> metadataValue.equals(metadata))
                .orElse(false);
        boolean firstWarning = FeatureStages.warnOnce(ConformanceExperimentalBase.class, warnings::add);
        boolean secondWarning = FeatureStages.warnOnce(ConformanceExperimentalSibling.class, warnings::add);
        boolean warningDeduplicated = firstWarning && !secondWarning && warnings.size() == 1;
        boolean conflictingStagesRejected;
        try {
            FeatureStages.describe(ConformanceConflictingStages.class);
            conflictingStagesRejected = false;
        } catch (ValidationException expected) {
            conflictingStagesRejected = true;
        }

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("runtimeRetention", new ConformanceValue.BooleanValue(runtimeRetention));
        actual.put("lifecycleTargetsPresent", new ConformanceValue.BooleanValue(lifecycleTargetsPresent));
        actual.put("featureIdsMatch", new ConformanceValue.BooleanValue(featureIdsMatch));
        actual.put("stageMetadataDiscoverable", new ConformanceValue.BooleanValue(stageMetadataDiscoverable));
        actual.put("inheritedTypeMetadata", new ConformanceValue.BooleanValue(inheritedTypeMetadata));
        actual.put("warningDeduplicated", new ConformanceValue.BooleanValue(warningDeduplicated));
        actual.put("conflictingStagesRejected", new ConformanceValue.BooleanValue(conflictingStagesRejected));
        actual.put(
                "releaseCandidateInventoryEmpty",
                new ConformanceValue.BooleanValue(ReleaseCandidateFeature.values().length == 0));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfTelemetry002_shouldBindLiveFeatureMaskAndDestinationScopedUserAgent() throws Exception {
        // Arrange
        BehaviorFixture fixture = behavior("JCF-TELEMETRY-002");
        int registryVersion =
                integer(fixture.input().require("registryVersion")).intValueExact();
        int width = integer(fixture.input().require("width")).intValueExact();
        List<Integer> marks = array(fixture.input().require("marks")).values().stream()
                .map(CoreConformanceTest::integer)
                .map(BigInteger::intValueExact)
                .toList();
        List<Integer> invalidIndexes = array(fixture.input().require("invalidIndexes")).values().stream()
                .map(CoreConformanceTest::integer)
                .map(BigInteger::intValueExact)
                .toList();
        String version = string(fixture.input().require("frameworkVersion"));
        List<String> prefixes = strings(array(fixture.input().require("prefixes")));
        String existingUserAgent = string(fixture.input().require("existingUserAgent"));
        String staleUserAgent = string(fixture.input().require("staleUserAgent"));
        URI approvedOrigin = URI.create(string(fixture.input().require("approvedOrigin")));
        URI deniedOrigin = URI.create(string(fixture.input().require("deniedOrigin")));
        List<String> approvedSuffixes = strings(array(fixture.input().require("approvedOriginSuffixes")));
        FeatureUsageRegistry registry = new FeatureUsageRegistry(registryVersion, true);

        // Act
        marks.forEach(registry::markUsed);
        String featureToken = registry.token().orElseThrow();
        String prefixedUserAgent = UserAgentUtil.frameworkUserAgent(version, prefixes);
        String prependedUserAgent = prefixedUserAgent + " " + existingUserAgent;
        String refreshedUserAgent = UserAgentUtil.applyFeatureToken(staleUserAgent, registry);
        String approvedOriginUserAgent =
                UserAgentUtil.stampFeatureToken(staleUserAgent, approvedOrigin, approvedSuffixes, registry);
        String deniedOriginUserAgent =
                UserAgentUtil.stampFeatureToken(refreshedUserAgent, deniedOrigin, approvedSuffixes, registry);
        FeatureUsageRegistry disabledRegistry = new FeatureUsageRegistry(registryVersion, false);
        String disabledUserAgent = UserAgentUtil.applyFeatureToken(staleUserAgent, disabledRegistry);
        boolean invalidIndexesRejected = invalidIndexes.stream().allMatch(index -> {
            try {
                registry.markUsed(index);
                return false;
            } catch (IllegalArgumentException expected) {
                return true;
            }
        });
        FeatureUsageRegistry liveRegistry = new FeatureUsageRegistry(registryVersion, true);
        liveRegistry.markUsed(0);
        String firstLiveUserAgent = UserAgentUtil.applyFeatureToken(prefixedUserAgent, liveRegistry);
        liveRegistry.markUsed(2);
        String secondLiveUserAgent = UserAgentUtil.applyFeatureToken(firstLiveUserAgent, liveRegistry);
        FeatureUsageRegistry concurrentRegistry = new FeatureUsageRegistry(registryVersion, true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.invokeAll(java.util.stream.IntStream.range(0, width)
                    .mapToObj(index -> (java.util.concurrent.Callable<Void>) () -> {
                        concurrentRegistry.markUsed(index);
                        return null;
                    })
                    .toList());
        }
        String concurrentFeatureToken = concurrentRegistry.token().orElseThrow();

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("featureToken", new ConformanceValue.StringValue(featureToken));
        actual.put("concurrentFeatureToken", new ConformanceValue.StringValue(concurrentFeatureToken));
        actual.put("prefixedUserAgent", new ConformanceValue.StringValue(prefixedUserAgent));
        actual.put("prependedUserAgent", new ConformanceValue.StringValue(prependedUserAgent));
        actual.put("refreshedUserAgent", new ConformanceValue.StringValue(refreshedUserAgent));
        actual.put("approvedOriginUserAgent", new ConformanceValue.StringValue(approvedOriginUserAgent));
        actual.put("deniedOriginUserAgent", new ConformanceValue.StringValue(deniedOriginUserAgent));
        actual.put("disabledUserAgent", new ConformanceValue.StringValue(disabledUserAgent));
        actual.put("duplicateMarksDeduplicated", new ConformanceValue.BooleanValue(featureToken.endsWith("5")));
        actual.put("invalidIndexesRejected", new ConformanceValue.BooleanValue(invalidIndexesRejected));
        actual.put(
                "featureTokenLiveAtRequestTime",
                new ConformanceValue.BooleanValue(
                        firstLiveUserAgent.endsWith("(feat=v1.1)") && secondLiveUserAgent.endsWith("(feat=v1.5)")));
        actual.put(
                "approvedOriginRequired",
                new ConformanceValue.BooleanValue(
                        approvedOriginUserAgent.contains("(feat=") && !deniedOriginUserAgent.contains("(feat=")));
        actual.put(
                "baseUserAgentPreservedWhenMaskDisabled",
                new ConformanceValue.BooleanValue(
                        disabledUserAgent.equals(UserAgentUtil.removeFeatureToken(staleUserAgent))));
        actual.put(
                "threadSafe",
                new ConformanceValue.BooleanValue(
                        concurrentFeatureToken.equals("v1.ffffffffffffffffffffffffffffffff")));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Experimental("FUNCTIONAL_WORKFLOWS")
    private static class ConformanceExperimentalBase {}

    private static final class ConformanceExperimentalChild extends ConformanceExperimentalBase {}

    @Experimental("FUNCTIONAL_WORKFLOWS")
    private static final class ConformanceExperimentalSibling {}

    @Experimental("CONFLICT")
    @ReleaseCandidate("CONFLICT")
    private static final class ConformanceConflictingStages {}

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

    private static ConformanceValue conformance(StateValue value) {
        return switch (value) {
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, ConformanceValue> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> values.put(key, conformance(item)));
                yield new ConformanceValue.ObjectValue(values);
            }
            case StateValue.ArrayValue array ->
                new ConformanceValue.ArrayValue(array.values().stream()
                        .map(CoreConformanceTest::conformance)
                        .toList());
            case StateValue.StringValue string -> new ConformanceValue.StringValue(string.value());
            case StateValue.NumberValue number -> new ConformanceValue.NumberValue(number.value());
            case StateValue.BooleanValue bool -> new ConformanceValue.BooleanValue(bool.value());
            case StateValue.NullValue _ -> ConformanceValue.NullValue.INSTANCE;
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
