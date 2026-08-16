// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import com.microsoft.agents.workflows.Executor;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Stores caller-owned typed executors and conditional predicates under stable logical names. */
public final class WorkflowComponentRegistry {
    private final Map<String, Executor<?, ?>> executors;

    private final Map<String, WorkflowCondition<?>> conditions;

    /**
     * Creates an immutable component registry.
     *
     * @param executors logical names to caller-owned typed executors
     * @param conditions logical names to caller-owned typed conditions
     */
    public WorkflowComponentRegistry(
            Map<String, ? extends Executor<?, ?>> executors, Map<String, ? extends WorkflowCondition<?>> conditions) {
        this.executors = copy(executors, "executors");
        this.conditions = copy(conditions, "conditions");
    }

    /**
     * Creates a registry containing executors and no conditions.
     *
     * @param executors logical names to caller-owned typed executors
     * @return immutable registry
     */
    public static WorkflowComponentRegistry ofExecutors(Map<String, ? extends Executor<?, ?>> executors) {
        return new WorkflowComponentRegistry(executors, Map.of());
    }

    /**
     * Finds an executor.
     *
     * @param name logical executor name
     * @return caller-owned executor, if registered
     */
    public Optional<Executor<?, ?>> findExecutor(String name) {
        return Optional.ofNullable(executors.get(WorkflowDefinitionValidation.requireNonBlank(name, "executor name")));
    }

    /**
     * Finds a condition.
     *
     * @param name logical condition name
     * @return caller-owned condition, if registered
     */
    public Optional<WorkflowCondition<?>> findCondition(String name) {
        return Optional.ofNullable(
                conditions.get(WorkflowDefinitionValidation.requireNonBlank(name, "condition name")));
    }

    private static <T> Map<String, T> copy(Map<String, ? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, T> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(
                WorkflowDefinitionValidation.requireNonBlank(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }
}
