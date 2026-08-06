// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.conformance.SnapshotFixture;
import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SessionsConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void jcfSessions001_shouldBindVersionOneEnvelopeToProductionCodec() {
        // Arrange
        SnapshotFixture fixture = (SnapshotFixture) catalog.requireCase("JCF-SESSIONS-001");
        ConformanceValue.ObjectValue payload =
                (ConformanceValue.ObjectValue) fixture.envelope().require("payload");
        String sessionId = string(payload, "sessionId");
        ConformanceValue.ArrayValue messages = (ConformanceValue.ArrayValue) payload.require("messages");
        String text = string(
                (ConformanceValue.ObjectValue) ((ConformanceValue.ArrayValue) ((ConformanceValue.ObjectValue)
                                        messages.values().getFirst())
                                .require("contents"))
                        .values()
                        .getFirst(),
                "text");
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
                sessionId,
                List.of(com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.USER, text)),
                new AgentSessionStateBag(Map.of("turn", StateValue.integer(1))));
        AgentSessionCodec codec = codec();

        // Act
        byte[] encoded = codec.encode(snapshot);
        AgentSessionSnapshot decoded = codec.decode(encoded);

        // Assert
        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.sessionId()).isEqualTo(string(fixture.expected(), "observableSessionId"));
        assertThat(decoded.messages().getFirst().text()).isEqualTo(string(fixture.expected(), "observableMessageText"));
    }

    @Test
    void jcfSessions002_shouldBindCreateOnlyAndCasConflictWithoutLastWriterWins() {
        // Arrange
        SnapshotFixture fixture = (SnapshotFixture) catalog.requireCase("JCF-SESSIONS-002");
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("session-cas");
        AgentSessionSnapshot first = snapshot("session-cas", "first");

        // Act
        VersionedSnapshot<AgentSessionSnapshot> created = store.saveAsync(key, first, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(created.revision()).isEqualTo(number(fixture.expected(), "loadedRevision"));
        assertThatThrownBy(() -> store.saveAsync(key, snapshot("session-cas", "stale"), SessionStore.CREATE_ONLY)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(StorageConflictException.class);
        assertThat(store.loadAsync(key)
                        .toCompletableFuture()
                        .join()
                        .orElseThrow()
                        .snapshot()
                        .state()
                        .get("value"))
                .contains(StateValue.string("first"));
    }

    @Test
    void jcfSessions003_shouldBindDetachedLoadAndSaveCopies() {
        // Arrange
        SnapshotFixture fixture = (SnapshotFixture) catalog.requireCase("JCF-SESSIONS-003");
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("session-detached");
        AgentSessionSnapshot original = new AgentSessionSnapshot(
                key.value(),
                List.of(),
                new AgentSessionStateBag(Map.of("values", StateValue.array(List.of(StateValue.string("original"))))));

        // Act
        store.saveAsync(key, original, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        AgentSessionSnapshot first =
                store.loadAsync(key).toCompletableFuture().join().orElseThrow().snapshot();
        AgentSessionSnapshot second =
                store.loadAsync(key).toCompletableFuture().join().orElseThrow().snapshot();

        // Assert
        assertThat(first).isNotSameAs(original).isNotSameAs(second);
        assertThat(first.state()).isNotSameAs(second.state());
        assertThat(((StateValue.ArrayValue) first.state().get("values").orElseThrow()).values())
                .containsExactly(StateValue.string("original"));
        assertThat(((ConformanceValue.BooleanValue) fixture.expected().require("storedStateDetached")).value())
                .isTrue();
    }

    @Test
    void sessionStoreSpi_shouldReportValidationAndCasFailuresThroughReturnedStages() {
        // Arrange
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("session-async-contract");
        AgentSessionSnapshot snapshot = snapshot(key.value(), "first");
        store.saveAsync(key, snapshot, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Act
        CompletionStage<?> invalidKey = store.loadAsync(null);
        CompletionStage<?> invalidRevision = store.saveAsync(key, snapshot, 0);
        CompletionStage<?> conflict = store.saveAsync(key, snapshot, SessionStore.CREATE_ONLY);

        // Assert
        assertExceptionalStage(invalidKey, ValidationException.class);
        assertExceptionalStage(invalidRevision, ValidationException.class);
        assertExceptionalStage(conflict, StorageConflictException.class);
    }

    private static void assertExceptionalStage(
            CompletionStage<?> stage, Class<? extends RuntimeException> failureType) {
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(failureType);
    }

    private static AgentSessionSnapshot snapshot(String sessionId, String value) {
        return new AgentSessionSnapshot(
                sessionId, List.of(), new AgentSessionStateBag(Map.of("value", StateValue.string(value))));
    }

    private static AgentSessionCodec codec() {
        return new AgentSessionCodec(new JsonStateSerializer(
                SerializationLimits.defaults(),
                Map.of(DocumentKind.AGENT_SESSION, Set.of(1), DocumentKind.WORKFLOW_CHECKPOINT, Set.of(1))));
    }

    private static String string(ConformanceValue.ObjectValue object, String name) {
        return ((ConformanceValue.StringValue) object.require(name)).value();
    }

    private static long number(ConformanceValue.ObjectValue object, String name) {
        return ((ConformanceValue.NumberValue) object.require(name)).value().longValueExact();
    }
}
