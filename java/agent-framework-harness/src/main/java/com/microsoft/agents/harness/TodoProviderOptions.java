// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Configures todo instructions and current-list message injection. */
public final class TodoProviderOptions {
    private final String sourceId;

    private final String instructions;

    private final boolean suppressTodoListMessage;

    private final Function<List<TodoItem>, String> todoListMessageBuilder;

    private TodoProviderOptions(Builder builder) {
        sourceId = requireNonBlank(builder.sourceId, "sourceId");
        instructions = requireNonBlank(builder.instructions, "instructions");
        suppressTodoListMessage = builder.suppressTodoListMessage;
        todoListMessageBuilder = Objects.requireNonNull(builder.todoListMessageBuilder, "todoListMessageBuilder");
    }

    /** Returns default provider options. */
    public static TodoProviderOptions defaults() {
        return builder().build();
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the stable context-provider identifier. */
    public String sourceId() {
        return sourceId;
    }

    /** Returns todo guidance. */
    public String instructions() {
        return instructions;
    }

    /** Returns whether current-list messages are suppressed. */
    public boolean suppressTodoListMessage() {
        return suppressTodoListMessage;
    }

    /** Returns the current-list formatter. */
    public Function<List<TodoItem>, String> todoListMessageBuilder() {
        return todoListMessageBuilder;
    }

    /** Builds immutable todo-provider options. */
    public static final class Builder {
        private String sourceId = TodoProvider.DEFAULT_SOURCE_ID;

        private String instructions = TodoProvider.DEFAULT_INSTRUCTIONS;

        private boolean suppressTodoListMessage;

        private Function<List<TodoItem>, String> todoListMessageBuilder = TodoProvider::defaultTodoListMessage;

        private Builder() {}

        /** Sets the provider identifier. */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /** Sets todo guidance. */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /** Suppresses the current-list user message. */
        public Builder suppressTodoListMessage(boolean suppressTodoListMessage) {
            this.suppressTodoListMessage = suppressTodoListMessage;
            return this;
        }

        /** Sets the current-list message formatter. */
        public Builder todoListMessageBuilder(Function<List<TodoItem>, String> todoListMessageBuilder) {
            this.todoListMessageBuilder = todoListMessageBuilder;
            return this;
        }

        /** Creates immutable options. */
        public TodoProviderOptions build() {
            return new TodoProviderOptions(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
