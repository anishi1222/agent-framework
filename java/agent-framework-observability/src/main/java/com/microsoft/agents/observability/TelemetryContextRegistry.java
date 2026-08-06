// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.StateValue;
import io.opentelemetry.context.Context;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class TelemetryContextRegistry {
    static final String CORRELATION_METADATA_KEY = "agentFramework.internal.telemetryContextId";

    private final Object lock = new Object();

    private final LinkedHashMap<RegistryKey, Entry> entries = new LinkedHashMap<>();

    private final int maximumEntries;

    private final long abandonedRunTtlMillis;

    private final Clock clock;

    private final Consumer<Throwable> failureHandler;

    TelemetryContextRegistry(TelemetryContextRegistryOptions options, Consumer<Throwable> failureHandler) {
        TelemetryContextRegistryOptions checked = Objects.requireNonNull(options, "options");
        maximumEntries = checked.maximumEntries();
        abandonedRunTtlMillis = checked.abandonedRunTtl().toMillis();
        clock = checked.clock();
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void registerAgent(String correlationId, Context context, Runnable onAbandoned) {
        register(new RegistryKey(RegistryKind.AGENT, correlationId), context, onAbandoned);
    }

    void removeAgent(String correlationId) {
        remove(new RegistryKey(RegistryKind.AGENT, correlationId));
    }

    Context agentParent(Map<String, StateValue> metadata) {
        return parent(RegistryKind.AGENT, metadata);
    }

    void registerWorkflow(String correlationId, Context context, Runnable onAbandoned) {
        register(new RegistryKey(RegistryKind.WORKFLOW, correlationId), context, onAbandoned);
    }

    void removeWorkflow(String correlationId) {
        remove(new RegistryKey(RegistryKind.WORKFLOW, correlationId));
    }

    Context workflowParent(Map<String, StateValue> metadata) {
        return parent(RegistryKind.WORKFLOW, metadata);
    }

    int size() {
        prune();
        synchronized (lock) {
            return entries.size();
        }
    }

    void prune() {
        List<Entry> abandoned;
        try {
            synchronized (lock) {
                abandoned = removeExpired(clock.millis());
            }
        } catch (Throwable failure) {
            handleFailure(failure);
            return;
        }
        abandon(abandoned);
    }

    private void register(RegistryKey key, Context context, Runnable onAbandoned) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(onAbandoned, "onAbandoned");
        ArrayList<Entry> abandoned = new ArrayList<>();
        try {
            synchronized (lock) {
                long nowMillis = clock.millis();
                abandoned.addAll(removeExpired(nowMillis));
                Entry replaced = entries.remove(key);
                if (replaced != null) {
                    abandoned.add(replaced);
                }
                entries.put(key, new Entry(context, onAbandoned, nowMillis));
                Iterator<Entry> oldest = entries.values().iterator();
                while (entries.size() > maximumEntries && oldest.hasNext()) {
                    abandoned.add(oldest.next());
                    oldest.remove();
                }
            }
        } catch (Throwable failure) {
            handleFailure(failure);
        }
        abandon(abandoned);
    }

    private void remove(RegistryKey key) {
        synchronized (lock) {
            entries.remove(key);
        }
    }

    private Context parent(RegistryKind kind, Map<String, StateValue> metadata) {
        prune();
        try {
            StateValue value = metadata.get(CORRELATION_METADATA_KEY);
            if (value instanceof StateValue.StringValue string) {
                synchronized (lock) {
                    Entry entry = entries.get(new RegistryKey(kind, string.value()));
                    if (entry != null) {
                        return entry.context();
                    }
                }
            }
        } catch (Throwable failure) {
            handleFailure(failure);
        }
        return Context.current();
    }

    private List<Entry> removeExpired(long nowMillis) {
        ArrayList<Entry> expired = new ArrayList<>();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            long age = nowMillis - entry.registeredAtMillis();
            if (age >= abandonedRunTtlMillis) {
                expired.add(entry);
                iterator.remove();
            }
        }
        return expired;
    }

    private void abandon(List<Entry> abandoned) {
        for (Entry entry : abandoned) {
            try {
                entry.onAbandoned().run();
            } catch (Throwable failure) {
                handleFailure(failure);
            }
        }
    }

    private void handleFailure(Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (Throwable ignored) {
            // The owning telemetry configuration also guards this callback.
        }
    }

    private enum RegistryKind {
        AGENT,
        WORKFLOW
    }

    private record RegistryKey(RegistryKind kind, String correlationId) {
        private RegistryKey {
            Objects.requireNonNull(kind, "kind");
            if (correlationId == null || correlationId.isBlank()) {
                throw new IllegalArgumentException("correlationId must not be blank.");
            }
        }
    }

    private record Entry(Context context, Runnable onAbandoned, long registeredAtMillis) {}
}
