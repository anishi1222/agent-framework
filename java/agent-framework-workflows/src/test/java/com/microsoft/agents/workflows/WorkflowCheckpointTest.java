// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.SerializationDocumentKind;
import com.microsoft.agents.conformance.SerializationRejectionCase;
import com.microsoft.agents.conformance.SerializationRejectionCorpus;
import com.microsoft.agents.conformance.SerializationRejectionCorpusLoader;
import com.microsoft.agents.conformance.SerializationRejectionReason;
import com.microsoft.agents.conformance.WorkflowCheckpointFixture;
import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.UnsupportedStorageCapabilityException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.InvocationRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowCheckpointTest {
    private static final ConformanceFixtureCatalog CATALOG = new ConformanceFixtureLoader().loadDefault();

    private static final SerializationRejectionCorpus REJECTIONS =
            new SerializationRejectionCorpusLoader().loadDefault();

    @Test
    void codec_shouldBindJcfWorkflows005ToProductionGoldenPath() {
        // Arrange
        WorkflowCheckpointFixture fixture = (WorkflowCheckpointFixture) CATALOG.requireCase("JCF-WORKFLOWS-005");
        WorkflowCheckpointCodec codec = codec(SerializationLimits.defaults());

        // Act
        WorkflowCheckpoint checkpoint = codec.decode(fixture.encoded().getBytes(StandardCharsets.UTF_8));
        String encoded = new String(codec.encode(checkpoint), StandardCharsets.UTF_8);

        // Assert
        assertThat(encoded).isEqualTo(fixture.encoded());
        assertThat(checkpoint.checkpointId()).isEqualTo("checkpoint-001");
        assertThat(checkpoint.pendingExecutors()).extracting(NodeId::value).containsExactly("audit", "right");
        assertThat(checkpoint.bufferedInputs())
                .extracting(input -> input.targetId() + ":" + input.sourceId())
                .containsExactly("join:left", "join:middle", "join0:early");
        assertThat(checkpoint.fanInNextEpochs()).containsExactlyEntriesOf(java.util.Map.of(new NodeId("join"), 4L));
        assertThat(checkpoint.isRuntimeCheckpoint()).isFalse();
    }

    @Test
    void codec_shouldRejectWrongKindAndUnsupportedVersionThroughCoreSerializer() {
        // Arrange
        WorkflowCheckpointFixture fixture = (WorkflowCheckpointFixture) CATALOG.requireCase("JCF-WORKFLOWS-005");
        WorkflowCheckpointCodec codec = codec(SerializationLimits.defaults());
        byte[] wrongKind = fixture.encoded()
                .replace("\"workflow-checkpoint\"", "\"agent-session\"")
                .getBytes(StandardCharsets.UTF_8);
        byte[] wrongVersion = fixture.encoded()
                .replace("\"payloadVersion\":1", "\"payloadVersion\":2")
                .getBytes(StandardCharsets.UTF_8);

        // Act / Assert
        assertThatThrownBy(() -> codec.decode(wrongKind))
                .isInstanceOf(SerializationException.class)
                .extracting(failure -> ((SerializationException) failure).error())
                .isEqualTo(SerializationError.WRONG_DOCUMENT_KIND);
        assertThatThrownBy(() -> codec.decode(wrongVersion))
                .isInstanceOf(SerializationException.class)
                .extracting(failure -> ((SerializationException) failure).error())
                .isEqualTo(SerializationError.UNSUPPORTED_PAYLOAD_VERSION);
    }

    static Stream<Arguments> workflowRejections() {
        return REJECTIONS.cases().stream()
                .filter(rejection -> rejection.documentKind() == SerializationDocumentKind.WORKFLOW_CHECKPOINT)
                .map(rejection -> Arguments.of(rejection.caseId(), rejection));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workflowRejections")
    void codec_shouldBindWorkflowRejectionCorpusToProductionReader(
            String caseId, SerializationRejectionCase rejection) {
        // Arrange
        byte[] raw = REJECTIONS.readRaw(rejection);
        com.microsoft.agents.conformance.SerializationLimits limits = rejection.limits();
        WorkflowCheckpointCodec codec = codec(new SerializationLimits(
                limits.maxDocumentBytes(),
                limits.maxNestingDepth(),
                limits.maxStringLength(),
                limits.maxNumericTokenLength(),
                limits.maxCollectionEntries()));

        // Act / Assert
        assertThatThrownBy(() -> codec.decode(raw))
                .withFailMessage("Corpus case %s was accepted.", caseId)
                .isInstanceOfSatisfying(
                        SerializationException.class,
                        failure -> assertThat(portableReason(failure.error())).isEqualTo(rejection.reason()));
    }

    @Test
    void store_shouldEnforceCasDetachedRevisionAndDeleteSemantics() {
        // Arrange
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("run");
        WorkflowCheckpoint draft = portableDraft("checkpoint-1");

        // Act
        VersionedSnapshot<WorkflowCheckpoint> created = storage.saveAsync(key, draft, CheckpointStorage.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        VersionedSnapshot<WorkflowCheckpoint> replaced = storage.saveAsync(
                        key, portableDraft("checkpoint-2"), created.revision())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(created.revision()).isPositive();
        assertThat(created.snapshot().revision()).isEqualTo(created.revision());
        assertThat(replaced.revision()).isGreaterThan(created.revision());
        assertThatThrownBy(() -> storage.saveAsync(key, portableDraft("stale"), created.revision())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
        storage.deleteAsync(key, replaced.revision()).toCompletableFuture().join();
        assertThat(storage.loadAsync(key).toCompletableFuture().join()).isEmpty();
    }

    @Test
    void commitAsync_shouldFailBeforeEffectsWhenCapabilityIsUnsupported() {
        // Arrange
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage(false);
        CheckpointKey key = new CheckpointKey("atomic");
        InvocationRecord record =
                new InvocationRecord(new InvocationId("invocation-1"), "run", "call", "tool", "digest");
        CheckpointCommit commit = new CheckpointCommit(
                key,
                portableDraft("checkpoint"),
                new InvocationLedgerDelta(List.of(new LedgerEntryMutation(record, 0))));

        // Act / Assert
        assertThatThrownBy(() -> storage.commitAsync(commit, CheckpointStorage.CREATE_ONLY)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(UnsupportedStorageCapabilityException.class);
        assertThat(storage.loadAsync(key).toCompletableFuture().join()).isEmpty();
        assertThat(storage.loadLedgerAsync(record.invocationId())
                        .toCompletableFuture()
                        .join())
                .isEmpty();
    }

    @Test
    void commitAsync_shouldAtomicallyWriteCheckpointAndLedgerWhenAdvertised() {
        // Arrange
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage(true);
        CheckpointKey key = new CheckpointKey("atomic");
        InvocationRecord record =
                new InvocationRecord(new InvocationId("invocation-1"), "run", "call", "tool", "digest");
        CheckpointCommit commit = new CheckpointCommit(
                key,
                portableDraft("checkpoint"),
                new InvocationLedgerDelta(List.of(new LedgerEntryMutation(record, 0))));

        // Act
        VersionedSnapshot<WorkflowCheckpoint> stored = storage.commitAsync(commit, CheckpointStorage.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(storage.capabilities()).containsExactly(StorageCapability.ATOMIC_CHECKPOINT_AND_LEDGER);
        assertThat(storage.loadAsync(key).toCompletableFuture().join()).contains(stored);
        assertThat(storage.loadLedgerAsync(record.invocationId())
                        .toCompletableFuture()
                        .join())
                .get()
                .extracting(snapshot -> snapshot.snapshot().invocationId())
                .isEqualTo(record.invocationId());
    }

    @Test
    void resume_shouldRestoreFanInEpochWithoutReplayingCompletedNodes() {
        // Arrange
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger leftCalls = new AtomicInteger();
        AtomicInteger rightCalls = new AtomicInteger();
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("resume-fan-in", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source",
                FunctionExecutor.sync(
                        Integer.class,
                        Integer.class,
                        WorkflowCodecs.integerCodec(),
                        WorkflowCodecs.integerCodec(),
                        (value, context) -> {
                            sourceCalls.incrementAndGet();
                            return value;
                        }));
        WorkflowNode<Integer, Integer> left = builder.addNode(
                "left",
                FunctionExecutor.sync(
                        Integer.class,
                        Integer.class,
                        WorkflowCodecs.integerCodec(),
                        WorkflowCodecs.integerCodec(),
                        (value, context) -> {
                            leftCalls.incrementAndGet();
                            return value + 1;
                        }));
        WorkflowNode<Integer, Integer> right = builder.addNode(
                "right",
                FunctionExecutor.sync(
                        Integer.class,
                        Integer.class,
                        WorkflowCodecs.integerCodec(),
                        WorkflowCodecs.integerCodec(),
                        (value, context) -> {
                            rightCalls.incrementAndGet();
                            return value + 2;
                        }));
        WorkflowNode<FanInInput, Integer> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class, Integer.class, (input, context) -> input.values(Integer.class).stream()
                                .mapToInt(Integer::intValue)
                                .sum()));
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("resume-key");

        // Act
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            EncodedState leftValue =
                    new EncodedState(WorkflowCodecs.integerCodec().typeId(), 1, StateValue.integer(2));
            EncodedState rightInput =
                    new EncodedState(WorkflowCodecs.integerCodec().typeId(), 1, StateValue.integer(1));
            WorkflowCheckpoint draft = new WorkflowCheckpoint(
                    workflow.id(),
                    "checkpoint-001",
                    0,
                    null,
                    WorkflowCheckpointStatus.RUNNING,
                    List.of(right.id()),
                    List.of(
                            new BufferedInput(join.id(), left.id().value(), leftValue.toStateValue()),
                            new BufferedInput(right.id(), BufferedInput.PENDING_SOURCE, rightInput.toStateValue())),
                    java.util.Map.of(join.id(), 2L),
                    workflow.schemaVersion(),
                    workflow.graphFingerprint(),
                    "resume-run",
                    2,
                    WorkflowState.empty());
            storage.saveAsync(key, draft, CheckpointStorage.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();
            List<WorkflowEvent> events = collect(workflow.resumeStreaming(storage, key, WorkflowRunOptions.defaults()));

            // Assert
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.OUTPUT)
                    .singleElement()
                    .extracting(WorkflowEvent::data)
                    .isEqualTo(StateValue.integer(5));
            assertThat(sourceCalls).hasValue(0);
            assertThat(leftCalls).hasValue(0);
            assertThat(rightCalls).hasValue(1);
            assertThat(events)
                    .filteredOn(event -> event.type() == WorkflowEventType.FAN_IN_RELEASED)
                    .singleElement()
                    .satisfies(event -> assertThat(
                                    ((StateValue.NumberValue) ((StateValue.ObjectValue) event.data()).require("epoch"))
                                            .value()
                                            .longValueExact())
                            .isEqualTo(2));
            WorkflowEvent firstSaved = events.stream()
                    .filter(event -> event.type() == WorkflowEventType.CHECKPOINT_SAVED)
                    .findFirst()
                    .orElseThrow();
            assertThat(((StateValue.ObjectValue) firstSaved.data()).require("previousCheckpointId"))
                    .isEqualTo(StateValue.string("checkpoint-001"));
            VersionedSnapshot<WorkflowCheckpoint> latest =
                    storage.loadAsync(key).toCompletableFuture().join().orElseThrow();
            assertThat(latest.snapshot().previousCheckpointId()).isEqualTo("resume-run-checkpoint-3");
            assertThat(latest.snapshot().fanInNextEpochs()).containsEntry(join.id(), 3L);
            assertThat(latest.revision()).isGreaterThan(1);
        }
    }

    @Test
    void resume_shouldRejectFingerprintAndSchemaMismatch() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("identity", String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "node",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> value));
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("identity-key");

        // Act / Assert
        try (Workflow<String, String> workflow =
                builder.entry(node).output(node).build()) {
            WorkflowCheckpoint mismatch = new WorkflowCheckpoint(
                    workflow.id(),
                    "checkpoint",
                    0,
                    null,
                    WorkflowCheckpointStatus.RUNNING,
                    List.of(node.id()),
                    List.of(new BufferedInput(
                            node.id(),
                            BufferedInput.PENDING_SOURCE,
                            new EncodedState(WorkflowCodecs.stringCodec().typeId(), 1, StateValue.string("input"))
                                    .toStateValue())),
                    workflow.schemaVersion(),
                    "wrong-fingerprint",
                    "run",
                    0,
                    WorkflowState.empty());
            storage.saveAsync(key, mismatch, CheckpointStorage.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();
            assertThatThrownBy(() -> workflow.resumeAsync(storage, key, WorkflowRunOptions.defaults())
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(WorkflowCheckpointException.class)
                    .hasMessageContaining("fingerprint");
        }
    }

    @Test
    void resume_shouldRestoreMultiplePendingInvocationsForOneExecutorInStableOrder() {
        // Arrange
        AtomicInteger sinkCalls = new AtomicInteger();
        WorkflowBuilder<String, String> builder =
                WorkflowBuilder.create("multiple-pending", String.class, String.class);
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
                        (value, context) -> value));
        WorkflowNode<String, String> right = builder.addNode(
                "right",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> value));
        WorkflowNode<String, String> sink = builder.addNode(
                "sink",
                FunctionExecutor.sync(
                        String.class,
                        String.class,
                        WorkflowCodecs.stringCodec(),
                        WorkflowCodecs.stringCodec(),
                        (value, context) -> {
                            sinkCalls.incrementAndGet();
                            return value;
                        }));
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        CheckpointKey key = new CheckpointKey("multiple-pending-key");

        // Act
        try (Workflow<String, String> workflow = builder.entry(source)
                .output(sink)
                .fanOut(source, List.of(left, right))
                .connect(left, sink)
                .connect(right, sink)
                .build()) {
            WorkflowCheckpoint draft = new WorkflowCheckpoint(
                    workflow.id(),
                    "checkpoint",
                    0,
                    null,
                    WorkflowCheckpointStatus.RUNNING,
                    List.of(sink.id()),
                    List.of(
                            new BufferedInput(sink.id(), "$pending:correlation-a", encodedString("a")),
                            new BufferedInput(sink.id(), "$pending:correlation-b", encodedString("b"))),
                    workflow.schemaVersion(),
                    workflow.graphFingerprint(),
                    "multiple-pending-run",
                    2,
                    WorkflowState.empty());
            storage.saveAsync(key, draft, CheckpointStorage.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();
            WorkflowRunResult<String> result = workflow.resumeAsync(storage, key, WorkflowRunOptions.defaults())
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(sinkCalls).hasValue(2);
            assertThat(result.output()).isEqualTo("b");
        }
    }

    private static WorkflowCheckpointCodec codec(SerializationLimits limits) {
        return new WorkflowCheckpointCodec(new JsonStateSerializer(limits));
    }

    private static WorkflowCheckpoint portableDraft(String checkpointId) {
        return new WorkflowCheckpoint(
                "workflow",
                checkpointId,
                0,
                null,
                WorkflowCheckpointStatus.RUNNING,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                WorkflowState.empty());
    }

    private static StateValue encodedString(String value) {
        return new EncodedState(
                        WorkflowCodecs.stringCodec().typeId(),
                        WorkflowCodecs.stringCodec().currentVersion(),
                        WorkflowCodecs.stringCodec().encode(value))
                .toStateValue();
    }

    private static SerializationRejectionReason portableReason(SerializationError error) {
        return switch (error) {
            case DUPLICATE_KEY -> SerializationRejectionReason.DUPLICATE_KEY;
            case DOCUMENT_BYTES -> SerializationRejectionReason.DOCUMENT_BYTES;
            case NESTING_DEPTH -> SerializationRejectionReason.NESTING_DEPTH;
            case STRING_LENGTH -> SerializationRejectionReason.STRING_LENGTH;
            case NUMERIC_TOKEN_LENGTH -> SerializationRejectionReason.NUMERIC_TOKEN_LENGTH;
            case COLLECTION_ENTRIES -> SerializationRejectionReason.COLLECTION_ENTRIES;
            case NON_FINITE_NUMBER -> SerializationRejectionReason.NON_FINITE_NUMBER;
            case WRONG_DOCUMENT_KIND -> SerializationRejectionReason.WRONG_DOCUMENT_KIND;
            case UNSUPPORTED_PAYLOAD_VERSION -> SerializationRejectionReason.UNSUPPORTED_PAYLOAD_VERSION;
            case TRAILING_CONTENT, MALFORMED_DOCUMENT, UNKNOWN_TYPE_ID, DUPLICATE_CODEC, CODEC_MIGRATION ->
                throw new AssertionError("Unexpected workflow rejection category " + error);
        };
    }

    private static List<WorkflowEvent> collect(Flow.Publisher<WorkflowEvent> publisher) {
        CompletableFuture<List<WorkflowEvent>> result = new CompletableFuture<>();
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
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(events));
            }
        });
        return result.orTimeout(5, TimeUnit.SECONDS).join();
    }
}
