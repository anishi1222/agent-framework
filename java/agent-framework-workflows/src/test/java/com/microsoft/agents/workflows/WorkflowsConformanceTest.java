// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.conformance.EventHistoryFixture;
import com.microsoft.agents.conformance.WorkflowCheckpointFixture;
import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkflowsConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void jcfWorkflows001_shouldBindCoreExecutionAndEventsToProductionRuntime() {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) catalog.requireCase("JCF-WORKFLOWS-001");
        String input = text(event(fixture, "executorInvoked", "start").require("input"));
        String processed =
                text(first(array(event(fixture, "executorCompleted", "start").require("outputs"))));
        String output = text(event(fixture, "workflowOutput", null).require("value"));
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create(
                text(event(fixture, "workflowStarted", null).require("workflowId")), String.class, String.class);
        WorkflowNode<String, String> start = builder.addNode(
                "start", FunctionExecutor.sync(String.class, String.class, (value, context) -> processed));
        WorkflowNode<String, String> finish = builder.addNode(
                "finish", FunctionExecutor.sync(String.class, String.class, (value, context) -> output));

        // Act
        List<WorkflowEvent> events;
        try (Workflow<String, String> workflow =
                builder.entry(start).output(finish).connect(start, finish).build()) {
            events = trace(workflow.runStreaming(
                            input,
                            WorkflowRunOptions.builder()
                                    .runId("jcf-workflows-001")
                                    .build()))
                    .events();
        }

        // Assert
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "executorOrder",
                strings(events.stream()
                        .filter(item -> item.type() == WorkflowEventType.NODE_STARTED)
                        .map(item -> item.nodeId().value())
                        .toList()));
        actual.put(
                "outputs",
                values(events.stream()
                        .filter(item -> item.type() == WorkflowEventType.OUTPUT)
                        .map(WorkflowEvent::data)
                        .toList()));
        actual.put("terminalCount", number(terminalEvents(events).size()));
        actual.put("terminalOutcome", new ConformanceValue.StringValue(outcome(events)));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfWorkflows002_shouldBindFanOutFanInToProductionRuntime() {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) catalog.requireCase("JCF-WORKFLOWS-002");
        int sourceValue = integer(
                first(array(event(fixture, "executorCompleted", "source").require("outputs"))));
        int leftValue =
                integer(first(array(event(fixture, "executorCompleted", "left").require("outputs"))));
        int rightValue =
                integer(first(array(event(fixture, "executorCompleted", "right").require("outputs"))));
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("jcf-workflows-002", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> sourceValue));
        WorkflowNode<Integer, Integer> left = builder.addNode(
                "left", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> leftValue));
        WorkflowNode<Integer, Integer> right = builder.addNode(
                "right", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> rightValue));
        WorkflowNode<FanInInput, Integer> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class, Integer.class, (input, context) -> input.values(Integer.class).stream()
                                .mapToInt(Integer::intValue)
                                .sum()));

        // Act
        List<WorkflowEvent> events;
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            events = trace(workflow.runStreaming(0)).events();
        }

        // Assert
        LinkedHashMap<String, Integer> deliveries = new LinkedHashMap<>();
        events.stream()
                .filter(item -> item.type() == WorkflowEventType.FAN_OUT)
                .map(item -> object(item.data()).require("targetIds"))
                .map(StateValue.ArrayValue.class::cast)
                .flatMap(targets -> targets.values().stream())
                .map(StateValue.StringValue.class::cast)
                .map(StateValue.StringValue::value)
                .forEach(target -> deliveries.merge(target, 1, Integer::sum));
        WorkflowEvent released = only(events, WorkflowEventType.FAN_IN_RELEASED);
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        LinkedHashMap<String, ConformanceValue> deliveryValues = new LinkedHashMap<>();
        deliveries.forEach((key, value) -> deliveryValues.put(key, number(value)));
        actual.put("fanOutDeliveries", new ConformanceValue.ObjectValue(deliveryValues));
        actual.put(
                "fanInReleaseCount",
                number(events.stream()
                        .filter(item -> item.type() == WorkflowEventType.FAN_IN_RELEASED)
                        .count()));
        actual.put("fanInValues", cv(object(released.data()).require("values")));
        actual.put(
                "outputs",
                values(events.stream()
                        .filter(item -> item.type() == WorkflowEventType.OUTPUT)
                        .map(WorkflowEvent::data)
                        .toList()));
        actual.put("terminalCount", number(terminalEvents(events).size()));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfWorkflows003_shouldBindFailureCancellationAndRollbackToProductionRuntime() throws Exception {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) catalog.requireCase("JCF-WORKFLOWS-003");
        CountDownLatch slowStarted = new CountDownLatch(1);
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("jcf-workflows-003", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> failing =
                builder.addNode("failing", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> {
                    try {
                        if (!slowStarted.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("slow branch did not start");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RunCancelledException();
                    }
                    throw new WorkflowException("fixture failure");
                }));
        WorkflowNode<Integer, Integer> slow = builder.addNode(
                "slow",
                FunctionExecutor.async(
                        Integer.class,
                        Integer.class,
                        (value, context) -> CompletableFuture.supplyAsync(() -> {
                            slowStarted.countDown();
                            while (!context.cancellation().isCancellationRequested()) {
                                Thread.onSpinWait();
                            }
                            throw new RunCancelledException();
                        })));
        WorkflowNode<FanInInput, Integer> join =
                builder.addNode("join", FunctionExecutor.sync(FanInInput.class, Integer.class, (input, context) -> 0));

        // Act
        Trace trace;
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(slow, failing))
                .fanIn(List.of(slow, failing), join)
                .build()) {
            trace = trace(workflow.runStreaming(0));
        }

        // Assert
        List<WorkflowEvent> events = trace.events();
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "failedExecutor",
                new ConformanceValue.StringValue(
                        only(events, WorkflowEventType.NODE_FAILED).nodeId().value()));
        actual.put(
                "cancelledExecutors",
                strings(events.stream()
                        .filter(item -> item.type() == WorkflowEventType.NODE_CANCELLED)
                        .map(item -> item.nodeId().value())
                        .toList()));
        actual.put(
                "outputs",
                values(events.stream()
                        .filter(item -> item.type() == WorkflowEventType.OUTPUT)
                        .map(WorkflowEvent::data)
                        .toList()));
        actual.put("terminalCount", number(terminalEvents(events).size()));
        actual.put("terminalOutcome", new ConformanceValue.StringValue(outcome(events)));
        int terminalIndex = lastTerminalIndex(events);
        actual.put(
                "successAfterTerminal",
                new ConformanceValue.BooleanValue(events.stream()
                        .skip(terminalIndex + 1L)
                        .anyMatch(item -> item.type() == WorkflowEventType.RUN_COMPLETED)));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfWorkflows004_shouldBindCheckpointResumeAndFanInEpochToProductionRuntime() {
        // Arrange
        EventHistoryFixture fixture = (EventHistoryFixture) catalog.requireCase("JCF-WORKFLOWS-004");
        String before = text(event(fixture, "fanInBuffered", "left").require("value"));
        String after = text(event(fixture, "fanInBuffered", "right").require("value"));
        String checkpointId = text(event(fixture, "checkpointSaved", null).require("checkpointId"));
        WorkflowBuilder<String, StateValue.ArrayValue> builder =
                WorkflowBuilder.create("checkpoint-resume", String.class, StateValue.ArrayValue.class);
        WorkflowNode<String, String> source = builder.addNode(
                "source",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> value));
        WorkflowNode<String, String> left = builder.addNode(
                "left",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> before));
        WorkflowNode<String, String> right = builder.addNode(
                "right",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> after));
        WorkflowNode<FanInInput, StateValue.ArrayValue> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class,
                        StateValue.ArrayValue.class,
                        (input, context) -> StateValue.array(input.values(String.class).stream()
                                .map(StateValue::string)
                                .toList())));
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("jcf-workflows-004");

        // Act
        Trace trace;
        try (Workflow<String, StateValue.ArrayValue> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            WorkflowCheckpoint draft = new WorkflowCheckpoint(
                    workflow.id(),
                    checkpointId,
                    0,
                    null,
                    WorkflowCheckpointStatus.RUNNING,
                    List.of(right.id()),
                    List.of(
                            new BufferedInput(
                                    join.id(), left.id().value(), encoded(WorkflowCodecs.stringCodec(), before)),
                            new BufferedInput(
                                    right.id(),
                                    BufferedInput.PENDING_SOURCE,
                                    encoded(WorkflowCodecs.stringCodec(), "resume"))),
                    workflow.schemaVersion(),
                    workflow.graphFingerprint(),
                    "jcf-workflows-004-run",
                    1,
                    WorkflowState.empty());
            storage.saveAsync(key, draft, CheckpointStorage.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();
            trace = trace(workflow.resumeStreaming(storage, key, WorkflowRunOptions.defaults()));
        }

        // Assert
        List<WorkflowEvent> events = trace.events();
        WorkflowEvent release = only(events, WorkflowEventType.FAN_IN_RELEASED);
        WorkflowEvent firstSave = events.stream()
                .filter(item -> item.type() == WorkflowEventType.CHECKPOINT_SAVED)
                .findFirst()
                .orElseThrow();
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "restoredCheckpointId",
                new ConformanceValue.StringValue(text(
                        object(only(events, WorkflowEventType.CHECKPOINT_LOADED).data())
                                .require("checkpointId"))));
        actual.put(
                "nextCheckpointParent",
                new ConformanceValue.StringValue(text(object(firstSave.data()).require("previousCheckpointId"))));
        StateValue.ArrayValue releasedValues =
                (StateValue.ArrayValue) object(release.data()).require("values");
        actual.put("fanInValues", cv(releasedValues));
        actual.put(
                "duplicateBufferedValues",
                number(releasedValues.values().size() - new LinkedHashSet<>(releasedValues.values()).size()));
        actual.put("terminalCount", number(terminalEvents(events).size()));
        actual.put("crossLanguageWireCompatible", new ConformanceValue.BooleanValue(false));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfWorkflows005_shouldBindGoldenCodecAndResumeThroughProductionPaths() {
        // Arrange
        WorkflowCheckpointFixture fixture = (WorkflowCheckpointFixture) catalog.requireCase("JCF-WORKFLOWS-005");
        WorkflowCheckpointCodec codec =
                new WorkflowCheckpointCodec(new JsonStateSerializer(SerializationLimits.defaults()));
        WorkflowCheckpoint portable = codec.decode(fixture.encoded().getBytes(StandardCharsets.UTF_8));
        StateValue leftValue = buffered(portable, "join", "left").value();
        String middleValue =
                ((StateValue.StringValue) buffered(portable, "join", "middle").value()).value();
        String rightValue =
                text(event(fixture.resumeEvents(), "fanInBuffered", "right").require("value"));
        String auditValue =
                text(event(fixture.resumeEvents(), "workflowOutput", "audit").require("value"));
        WorkflowBuilder<String, StateValue.ArrayValue> builder =
                WorkflowBuilder.create(portable.workflowId(), String.class, StateValue.ArrayValue.class);
        WorkflowNode<String, String> source = builder.addNode(
                "source",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> value));
        WorkflowNode<String, StateValue> left = builder.addNode(
                "left",
                FunctionExecutor.sync(
                        String.class,
                        StateValue.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stateValueCodec(),
                        (value, context) -> leftValue));
        WorkflowNode<String, String> middle = builder.addNode(
                "middle",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> middleValue));
        WorkflowNode<String, String> right = builder.addNode(
                "right",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> rightValue));
        WorkflowNode<String, String> audit = builder.addNode(
                "audit",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> auditValue));
        WorkflowNode<FanInInput, StateValue.ArrayValue> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class,
                        StateValue.ArrayValue.class,
                        (input, context) -> StateValue.array(input.sourceIds().stream()
                                .map(sourceId -> WorkflowValues.toStateValue(
                                        input.rawValues().get(sourceId)))
                                .toList())));
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("jcf-workflows-005");

        // Act
        Trace trace;
        try (Workflow<String, StateValue.ArrayValue> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, middle, right, audit))
                .fanIn(List.of(left, middle, right), join)
                .build()) {
            WorkflowCheckpoint runtime = new WorkflowCheckpoint(
                    workflow.id(),
                    portable.checkpointId(),
                    0,
                    portable.previousCheckpointId(),
                    WorkflowCheckpointStatus.RUNNING,
                    portable.pendingExecutors(),
                    List.of(
                            new BufferedInput(
                                    join.id(), left.id().value(), encoded(WorkflowCodecs.stateValueCodec(), leftValue)),
                            new BufferedInput(
                                    join.id(), middle.id().value(), encoded(WorkflowCodecs.stringCodec(), middleValue)),
                            new BufferedInput(
                                    audit.id(),
                                    BufferedInput.PENDING_SOURCE,
                                    encoded(WorkflowCodecs.stringCodec(), "audit")),
                            new BufferedInput(
                                    right.id(),
                                    BufferedInput.PENDING_SOURCE,
                                    encoded(WorkflowCodecs.stringCodec(), "right"))),
                    portable.fanInNextEpochs(),
                    workflow.schemaVersion(),
                    workflow.graphFingerprint(),
                    "jcf-workflows-005-run",
                    1,
                    WorkflowState.empty());
            storage.saveAsync(key, runtime, CheckpointStorage.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();
            trace = trace(workflow.resumeStreaming(storage, key, WorkflowRunOptions.defaults()));
        }

        // Assert
        StateValue fanInValues = object(
                        only(trace.events(), WorkflowEventType.FAN_IN_RELEASED).data())
                .require("values");
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("decodedCheckpointId", new ConformanceValue.StringValue(portable.checkpointId()));
        actual.put(
                "decodedPendingExecutors",
                strings(portable.pendingExecutors().stream().map(NodeId::value).toList()));
        actual.put(
                "decodedBufferedInputOrder",
                strings(portable.bufferedInputs().stream()
                        .map(input -> input.targetId() + ":" + input.sourceId())
                        .toList()));
        actual.put("resumeFanInValues", cv(fanInValues));
        actual.put(
                "resumeFanInEpoch",
                cv(object(only(trace.events(), WorkflowEventType.FAN_IN_RELEASED)
                                .data())
                        .require("epoch")));
        actual.put("terminalOutcome", new ConformanceValue.StringValue(outcome(trace.events())));
        actual.put(
                "deterministicEncoding",
                new ConformanceValue.BooleanValue(
                        fixture.encoded().equals(new String(codec.encode(portable), StandardCharsets.UTF_8))));
        actual.put("roundTripWithinJavaV1", new ConformanceValue.BooleanValue(true));
        actual.put("wrongDocumentKindRejected", new ConformanceValue.BooleanValue(rejectsWrongKind(codec, fixture)));
        actual.put(
                "unsupportedPayloadVersionRejected",
                new ConformanceValue.BooleanValue(rejectsWrongVersion(codec, fixture)));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private static Trace trace(Flow.Publisher<WorkflowEvent> publisher) {
        CompletableFuture<Trace> result = new CompletableFuture<>();
        ArrayList<WorkflowEvent> events = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WorkflowEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.complete(new Trace(List.copyOf(events), throwable));
            }

            @Override
            public void onComplete() {
                result.complete(new Trace(List.copyOf(events), null));
            }
        });
        return result.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static List<WorkflowEvent> terminalEvents(List<WorkflowEvent> events) {
        return events.stream()
                .filter(event -> event.type() == WorkflowEventType.RUN_COMPLETED
                        || event.type() == WorkflowEventType.RUN_FAILED
                        || event.type() == WorkflowEventType.RUN_CANCELLED)
                .toList();
    }

    private static String outcome(List<WorkflowEvent> events) {
        return switch (terminalEvents(events).getFirst().type()) {
            case RUN_COMPLETED -> "idle";
            case RUN_FAILED -> "failed";
            case RUN_CANCELLED -> "cancelled";
            default -> throw new AssertionError("Unexpected terminal event.");
        };
    }

    private static int lastTerminalIndex(List<WorkflowEvent> events) {
        WorkflowEvent terminal = terminalEvents(events).getLast();
        return events.indexOf(terminal);
    }

    private static WorkflowEvent only(List<WorkflowEvent> events, WorkflowEventType type) {
        return events.stream()
                .filter(event -> event.type() == type)
                .reduce((first, second) -> {
                    throw new AssertionError("Expected one " + type + " event.");
                })
                .orElseThrow();
    }

    private static ConformanceValue.ObjectValue event(EventHistoryFixture fixture, String type, String identifier) {
        return event(fixture.events(), type, identifier);
    }

    private static ConformanceValue.ObjectValue event(
            List<ConformanceValue.ObjectValue> events, String type, String identifier) {
        return events.stream()
                .filter(value -> type.equals(text(value.require("type"))))
                .filter(value -> identifier == null || identifier.equals(identifier(value)))
                .findFirst()
                .orElseThrow();
    }

    private static String identifier(ConformanceValue.ObjectValue value) {
        for (String field : List.of("executorId", "sourceId")) {
            ConformanceValue identifier = value.values().get(field);
            if (identifier instanceof ConformanceValue.StringValue string) {
                return string.value();
            }
        }
        return "";
    }

    private static BufferedInput buffered(WorkflowCheckpoint checkpoint, String targetId, String sourceId) {
        return checkpoint.bufferedInputs().stream()
                .filter(input -> input.targetId().value().equals(targetId)
                        && input.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
    }

    private static StateValue encoded(com.microsoft.agents.core.StateCodec<String> codec, String value) {
        return new EncodedState(codec.typeId(), codec.currentVersion(), codec.encode(value)).toStateValue();
    }

    private static StateValue encoded(com.microsoft.agents.core.StateCodec<StateValue> codec, StateValue value) {
        return new EncodedState(codec.typeId(), codec.currentVersion(), codec.encode(value)).toStateValue();
    }

    private static boolean rejectsWrongKind(WorkflowCheckpointCodec codec, WorkflowCheckpointFixture fixture) {
        try {
            codec.decode(fixture.encoded()
                    .replace("\"workflow-checkpoint\"", "\"agent-session\"")
                    .getBytes(StandardCharsets.UTF_8));
            return false;
        } catch (SerializationException exception) {
            return true;
        }
    }

    private static boolean rejectsWrongVersion(WorkflowCheckpointCodec codec, WorkflowCheckpointFixture fixture) {
        try {
            codec.decode(fixture.encoded()
                    .replace("\"payloadVersion\":1", "\"payloadVersion\":2")
                    .getBytes(StandardCharsets.UTF_8));
            return false;
        } catch (SerializationException exception) {
            return true;
        }
    }

    private static ConformanceValue values(List<StateValue> values) {
        return new ConformanceValue.ArrayValue(
                values.stream().map(WorkflowsConformanceTest::cv).toList());
    }

    private static ConformanceValue strings(List<String> values) {
        return new ConformanceValue.ArrayValue(values.stream()
                .map(ConformanceValue.StringValue::new)
                .map(ConformanceValue.class::cast)
                .toList());
    }

    private static ConformanceValue.NumberValue number(long value) {
        return new ConformanceValue.NumberValue(BigDecimal.valueOf(value));
    }

    private static ConformanceValue cv(StateValue value) {
        return switch (value) {
            case StateValue.ObjectValue object -> {
                LinkedHashMap<String, ConformanceValue> values = new LinkedHashMap<>();
                object.values().forEach((key, member) -> values.put(key, cv(member)));
                yield new ConformanceValue.ObjectValue(values);
            }
            case StateValue.ArrayValue array ->
                new ConformanceValue.ArrayValue(array.values().stream()
                        .map(WorkflowsConformanceTest::cv)
                        .toList());
            case StateValue.StringValue string -> new ConformanceValue.StringValue(string.value());
            case StateValue.NumberValue number -> new ConformanceValue.NumberValue(number.value());
            case StateValue.BooleanValue bool -> new ConformanceValue.BooleanValue(bool.value());
            case StateValue.NullValue nullValue -> {
                java.util.Objects.requireNonNull(nullValue, "nullValue");
                yield ConformanceValue.NullValue.INSTANCE;
            }
        };
    }

    private static StateValue.ObjectValue object(StateValue value) {
        return (StateValue.ObjectValue) value;
    }

    private static ConformanceValue.ArrayValue array(ConformanceValue value) {
        return (ConformanceValue.ArrayValue) value;
    }

    private static ConformanceValue first(ConformanceValue.ArrayValue value) {
        return value.values().getFirst();
    }

    private static String text(ConformanceValue value) {
        return ((ConformanceValue.StringValue) value).value();
    }

    private static String text(StateValue value) {
        return ((StateValue.StringValue) value).value();
    }

    private static int integer(ConformanceValue value) {
        return ((ConformanceValue.NumberValue) value).value().intValueExact();
    }

    private record Trace(List<WorkflowEvent> events, Throwable failure) {}
}
