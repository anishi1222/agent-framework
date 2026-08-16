// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Represents one immutable event from the pinned AG-UI standard event set.
 *
 * <p>Unknown standard discriminators are rejected. Safe unknown additive fields on a recognized
 * event envelope are retained on the decoded immutable event instance and re-emitted by {@link
 * AGUIJsonCodec}; inspect them through {@link #additionalProperties()}. Application extensions use
 * {@link AGUIEvents.Custom}; external passthrough data uses {@link AGUIEvents.Raw}.
 */
public sealed interface AGUIEvent permits AGUIEvents.Event {
    /**
     * Returns the exact wire discriminator.
     *
     * @return event type
     */
    AGUIEventType type();

    /**
     * Returns the optional protocol timestamp number.
     *
     * @return exact timestamp number, or {@code null}
     */
    BigDecimal timestamp();

    /**
     * Returns the optional transformed source event.
     *
     * @return immutable raw event, or {@code null}
     */
    StateValue rawEvent();

    /**
     * Returns safely retained unknown additive envelope fields.
     *
     * <p>The map is immutable and excludes every recognized field for this event. It is empty for
     * programmatically constructed events and for decoded events without additions.
     *
     * @return immutable additional fields
     */
    default Map<String, StateValue> additionalProperties() {
        return AGUIEventMetadata.additionalProperties(this);
    }
}
