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
    void beginRun_shouldRejectConcurrentMutableRunDeterministically() {
        // Arrange
        AgentSession session = new AgentSession("session-1");
        session.beginRun();

        // Act and assert
        assertThatThrownBy(session::beginRun)
                .isInstanceOf(SessionBusyException.class)
                .hasMessageContaining("session-1");
        session.endRun();
        session.beginRun();
        session.endRun();
    }
}
