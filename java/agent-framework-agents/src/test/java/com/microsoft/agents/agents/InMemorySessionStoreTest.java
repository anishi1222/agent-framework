// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {
    private final InMemorySessionStore store = new InMemorySessionStore();

    @Test
    void saveAsync_shouldCreateReplaceAndRejectStaleWritesWithoutLostUpdate() {
        // Arrange
        SessionKey key = new SessionKey("session-cas");
        AgentSessionSnapshot first = snapshot("session-cas", "first");

        // Act
        VersionedSnapshot<AgentSessionSnapshot> created = store.saveAsync(key, first, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        VersionedSnapshot<AgentSessionSnapshot> replaced = store.saveAsync(
                        key, snapshot("session-cas", "second"), created.revision())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(replaced.revision()).isGreaterThan(created.revision());
        assertThatThrownBy(() -> store.saveAsync(key, snapshot("session-cas", "stale"), created.revision())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
        assertThat(store.loadAsync(key)
                        .toCompletableFuture()
                        .join()
                        .orElseThrow()
                        .snapshot()
                        .state()
                        .get("value"))
                .contains(StateValue.string("second"));
    }

    @Test
    void saveAsync_shouldEnforceCreateOnlyMinusOne() {
        // Arrange
        SessionKey key = new SessionKey("session-create");
        store.saveAsync(key, snapshot("session-create", "first"), SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Act and assert
        assertThatThrownBy(() -> store.saveAsync(key, snapshot("session-create", "duplicate"), SessionStore.CREATE_ONLY)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
    }

    @Test
    void deleteAsync_shouldUseCasAndPreserveValueOnConflict() {
        // Arrange
        SessionKey key = new SessionKey("session-delete");
        VersionedSnapshot<AgentSessionSnapshot> created = store.saveAsync(
                        key, snapshot("session-delete", "value"), SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Act and assert
        assertThatThrownBy(() -> store.deleteAsync(key, created.revision() + 1)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
        assertThat(store.loadAsync(key).toCompletableFuture().join()).isPresent();
        store.deleteAsync(key, created.revision()).toCompletableFuture().join();
        assertThat(store.loadAsync(key).toCompletableFuture().join()).isEmpty();

        VersionedSnapshot<AgentSessionSnapshot> recreated = store.saveAsync(
                        key, snapshot("session-delete", "recreated"), SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        assertThat(recreated.revision()).isGreaterThan(created.revision());
    }

    @Test
    void loadAndSave_shouldReturnDetachedSnapshots() {
        // Arrange
        SessionKey key = new SessionKey("session-detached");
        AgentSessionSnapshot caller = new AgentSessionSnapshot(
                "session-detached",
                List.of(Message.text(Role.USER, "original")),
                new AgentSessionStateBag(Map.of("values", StateValue.array(List.of(StateValue.string("original"))))));
        store.saveAsync(key, caller, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Act
        AgentSessionSnapshot first =
                store.loadAsync(key).toCompletableFuture().join().orElseThrow().snapshot();
        AgentSessionSnapshot second =
                store.loadAsync(key).toCompletableFuture().join().orElseThrow().snapshot();

        // Assert
        assertThat(first).isNotSameAs(caller).isNotSameAs(second);
        assertThat(first.messages()).isNotSameAs(second.messages());
        assertThat(first.state()).isNotSameAs(second.state());
        assertThat(store.durability()).isEqualTo(SessionStoreDurability.PROCESS_MEMORY);
    }

    @Test
    void asyncOperations_shouldReportValidationFailuresThroughReturnedStages() {
        // Act
        CompletionStage<?> invalidLoad = store.loadAsync(null);
        CompletionStage<?> invalidSaveKey =
                store.saveAsync(null, snapshot("session-invalid", "value"), SessionStore.CREATE_ONLY);
        CompletionStage<?> invalidSaveRevision =
                store.saveAsync(new SessionKey("session-invalid"), snapshot("session-invalid", "value"), 0);
        CompletionStage<?> invalidDeleteKey = store.deleteAsync(null, 1);
        CompletionStage<?> invalidDeleteRevision = store.deleteAsync(new SessionKey("session-invalid"), 0);

        // Assert
        assertValidationFailure(invalidLoad);
        assertValidationFailure(invalidSaveKey);
        assertValidationFailure(invalidSaveRevision);
        assertValidationFailure(invalidDeleteKey);
        assertValidationFailure(invalidDeleteRevision);
    }

    private static void assertValidationFailure(CompletionStage<?> stage) {
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ValidationException.class);
    }

    private static AgentSessionSnapshot snapshot(String id, String value) {
        return new AgentSessionSnapshot(
                id, List.of(), new AgentSessionStateBag(Map.of("value", StateValue.string(value))));
    }
}
