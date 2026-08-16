// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

final class AGUIEventMetadata {
    private static final Object LOCK = new Object();

    private static final ReferenceQueue<AGUIEvent> STALE_EVENTS = new ReferenceQueue<>();

    private static final Map<IdentityWeakReference, Map<String, StateValue>> ADDITIONAL_PROPERTIES = new HashMap<>();

    private AGUIEventMetadata() {}

    static void attach(AGUIEvent event, Map<String, StateValue> additionalProperties) {
        java.util.Objects.requireNonNull(event, "event");
        Map<String, StateValue> copy = AGUIValidation.map(additionalProperties, "additionalProperties");
        if (copy.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            removeStaleEvents();
            ADDITIONAL_PROPERTIES.put(new IdentityWeakReference(event, STALE_EVENTS), copy);
        }
    }

    static Map<String, StateValue> additionalProperties(AGUIEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        synchronized (LOCK) {
            removeStaleEvents();
            Map<String, StateValue> properties = ADDITIONAL_PROPERTIES.get(new IdentityWeakReference(event, null));
            return properties == null ? Map.of() : properties;
        }
    }

    private static void removeStaleEvents() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) STALE_EVENTS.poll()) != null) {
            ADDITIONAL_PROPERTIES.remove(reference);
        }
    }

    private static final class IdentityWeakReference extends WeakReference<AGUIEvent> {
        private final int hashCode;

        private IdentityWeakReference(AGUIEvent event, ReferenceQueue<AGUIEvent> queue) {
            super(event, queue);
            hashCode = System.identityHashCode(event);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference reference)) {
                return false;
            }
            AGUIEvent event = get();
            return event != null && event == reference.get();
        }
    }
}
