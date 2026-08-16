// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import org.junit.jupiter.api.Test;

class AgentSessionTest {
    @Test
    void state_shouldExposeDetachedImmutableSnapshots() {
        // Arrange
        AgentSession session = new AgentSession("session-1");
        session.putState("value", StateValue.string("first"));
        AgentSessionStateBag first = session.state();

        // Act
        session.putState("value", StateValue.string("second"));

        // Assert
        assertThat(first.get("value")).contains(StateValue.string("first"));
        assertThat(session.state().get("value")).contains(StateValue.string("second"));
        assertThatThrownBy(() -> first.values().put("other", StateValue.bool(true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshot_shouldDetachHistoryAndState() {
        // Arrange
        AgentSession session = new AgentSession("session-1");
        session.putState("turn", StateValue.integer(1));
        session.appendMessages(java.util.List.of(Message.text(Role.USER, "first")));

        // Act
        AgentSessionSnapshot snapshot = session.snapshot();
        session.putState("turn", StateValue.integer(2));
        session.appendMessages(java.util.List.of(Message.text(Role.ASSISTANT, "second")));

        // Assert
        assertThat(snapshot.state().get("turn")).contains(StateValue.integer(1));
        assertThat(snapshot.messages()).extracting(Message::text).containsExactly("first");
    }

    @Test
    void restoreSnapshotPreservingState_shouldKeepOnlySelectedCurrentEntries() {
        AgentSession session = new AgentSession("session-preserve");
        session.putState("baseline", StateValue.string("before"));
        session.putState("runtime", StateValue.string("old"));
        AgentSessionSnapshot snapshot = session.snapshot();
        session.putState("baseline", StateValue.string("changed"));
        session.putState("runtime", StateValue.string("current"));
        session.putState("runtime-extra", StateValue.string("current-extra"));

        session.restoreSnapshotPreservingState(snapshot, key -> key.startsWith("runtime"));

        assertThat(session.state().get("baseline")).contains(StateValue.string("before"));
        assertThat(session.state().get("runtime")).contains(StateValue.string("current"));
        assertThat(session.state().get("runtime-extra")).contains(StateValue.string("current-extra"));
    }

    @Test
    void beginRun_shouldRejectConcurrentMutableRunDeterministically() {
        // Arrange
        AgentSession session = new AgentSession("session-1");

        // Act and assert
        try (AgentSession.RunLease lease = session.acquireRunLease()) {
            assertThat(lease).isNotNull();
            assertThatThrownBy(session::acquireRunLease)
                    .isInstanceOf(SessionBusyException.class)
                    .hasMessageContaining("session-1");
        }
        try (AgentSession.RunLease lease = session.acquireRunLease()) {
            assertThat(lease).isNotNull();
        }
    }
}
