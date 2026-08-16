// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.Tool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Contributes session-persisted todo tools and current-work context. */
public final class TodoProvider implements ContextProvider {
    /** Default context-provider identifier. */
    public static final String DEFAULT_SOURCE_ID = "todo";

    /** Batch todo creation tool. */
    public static final String ADD_TOOL_NAME = "todos_add";

    /** Batch todo completion tool. */
    public static final String COMPLETE_TOOL_NAME = "todos_complete";

    /** Batch todo removal tool. */
    public static final String REMOVE_TOOL_NAME = "todos_remove";

    /** Remaining-todo query tool. */
    public static final String GET_REMAINING_TOOL_NAME = "todos_get_remaining";

    /** All-todo query tool. */
    public static final String GET_ALL_TOOL_NAME = "todos_get_all";

    /** Built-in todo guidance. */
    public static final String DEFAULT_INSTRUCTIONS = """
            Use the todo tools to track multi-step work.
            Add concrete tasks before lengthy execution, complete them with a reason when finished,
            and keep the list accurate by removing obsolete tasks.
            Do not claim the overall task is complete while required todos remain open.""";

    private static final String STATE_PREFIX = "harness.todo.";

    private final TodoProviderOptions options;

    /** Creates a provider with default options. */
    public TodoProvider() {
        this(TodoProviderOptions.defaults());
    }

    /**
     * Creates a configured provider.
     *
     * @param options provider options
     */
    public TodoProvider(TodoProviderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return options.sourceId();
    }

    /**
     * Returns the session-state key owned by this provider.
     *
     * @return stable state key
     */
    public String stateKey() {
        return STATE_PREFIX + options.sourceId();
    }

    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        Objects.requireNonNull(request, "request");
        requireActive(request.runContext().cancellation());
        List<TodoItem> items = readState(request.session()).items();
        List<Message> messages;
        if (options.suppressTodoListMessage() || items.isEmpty()) {
            messages = List.of();
        } else {
            messages = List.of(
                    Message.text(Role.USER, options.todoListMessageBuilder().apply(items)));
        }
        return CompletableFuture.completedFuture(new ContextContribution(
                List.of(options.instructions()), messages, Map.of(), createTools(request.session())));
    }

    /**
     * Reads all todos from one session.
     *
     * @param session active session
     * @param cancellation caller-owned cancellation
     * @return immutable todo list
     */
    public CompletionStage<List<TodoItem>> getAllTodosAsync(AgentSession session, RunCancellation cancellation) {
        requireActive(cancellation);
        return CompletableFuture.completedFuture(
                readState(Objects.requireNonNull(session, "session")).items());
    }

    /**
     * Reads incomplete todos from one session.
     *
     * @param session active session
     * @param cancellation caller-owned cancellation
     * @return immutable remaining list
     */
    public CompletionStage<List<TodoItem>> getRemainingTodosAsync(AgentSession session, RunCancellation cancellation) {
        requireActive(cancellation);
        return CompletableFuture.completedFuture(readState(Objects.requireNonNull(session, "session")).items().stream()
                .filter(item -> !item.completed())
                .toList());
    }

    static String defaultTodoListMessage(List<TodoItem> items) {
        StringBuilder message = new StringBuilder("Current todo list:\n");
        for (TodoItem item : items) {
            message.append(item.completed() ? "[x] " : "[ ] ")
                    .append('#')
                    .append(item.id())
                    .append(' ')
                    .append(item.title());
            if (item.description() != null) {
                message.append(" - ").append(item.description());
            }
            message.append('\n');
        }
        return message.toString().stripTrailing();
    }

    private List<Tool> createTools(AgentSession session) {
        return List.of(
                FunctionTool.create(
                        metadata(
                                ADD_TOOL_NAME,
                                "Adds one or more todo items.",
                                addSchema(),
                                HarnessToolSupport.OPEN_OUTPUT),
                        (context, arguments) -> completed(add(session, parseInputs(arguments)))),
                FunctionTool.create(
                        metadata(
                                COMPLETE_TOOL_NAME,
                                "Marks todo items complete.",
                                completeSchema(),
                                HarnessToolSupport.INTEGER_OUTPUT),
                        (context, arguments) ->
                                completed(StateValue.integer(complete(session, parseCompletions(arguments))))),
                FunctionTool.create(
                        metadata(
                                REMOVE_TOOL_NAME,
                                "Removes todo items by identifier.",
                                removeSchema(),
                                HarnessToolSupport.INTEGER_OUTPUT),
                        (context, arguments) -> completed(StateValue.integer(remove(session, parseIds(arguments))))),
                FunctionTool.create(
                        metadata(
                                GET_REMAINING_TOOL_NAME,
                                "Returns incomplete todo items.",
                                emptySchema(),
                                HarnessToolSupport.OPEN_OUTPUT),
                        (context, arguments) -> completed(items(readState(session).items().stream()
                                .filter(item -> !item.completed())
                                .toList()))),
                FunctionTool.create(
                        metadata(
                                GET_ALL_TOOL_NAME,
                                "Returns every todo item.",
                                emptySchema(),
                                HarnessToolSupport.OPEN_OUTPUT),
                        (context, arguments) ->
                                completed(items(readState(session).items()))));
    }

    private StateValue add(AgentSession session, List<TodoInput> inputs) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("todos must not be empty.");
        }
        Holder<List<TodoItem>> added = new Holder<>();
        session.updateState(stateKey(), current -> {
            TodoState state = decodeState(current);
            ArrayList<TodoItem> all = new ArrayList<>(state.items());
            ArrayList<TodoItem> created = new ArrayList<>();
            int nextId = state.nextId();
            for (TodoInput input : inputs) {
                TodoItem item = new TodoItem(nextId++, input.title(), input.description(), false, null);
                all.add(item);
                created.add(item);
            }
            added.value = List.copyOf(created);
            return encodeState(new TodoState(nextId, all));
        });
        return items(added.value);
    }

    private int complete(AgentSession session, List<TodoCompletion> completions) {
        if (completions.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty.");
        }
        Holder<Integer> count = new Holder<>();
        session.updateState(stateKey(), current -> {
            TodoState state = decodeState(current);
            LinkedHashMap<Integer, TodoCompletion> requested = new LinkedHashMap<>();
            completions.forEach(completion -> requested.put(completion.id(), completion));
            ArrayList<TodoItem> updated = new ArrayList<>(state.items().size());
            int completed = 0;
            for (TodoItem item : state.items()) {
                TodoCompletion completion = requested.get(item.id());
                if (completion != null && !item.completed()) {
                    updated.add(item.complete(completion.reason()));
                    completed++;
                } else {
                    updated.add(item);
                }
            }
            count.value = completed;
            return encodeState(new TodoState(state.nextId(), updated));
        });
        return count.value;
    }

    private int remove(AgentSession session, List<Integer> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty.");
        }
        Holder<Integer> count = new Holder<>();
        session.updateState(stateKey(), current -> {
            TodoState state = decodeState(current);
            java.util.Set<Integer> removedIds = Set.copyOf(ids);
            List<TodoItem> retained = state.items().stream()
                    .filter(item -> !removedIds.contains(item.id()))
                    .toList();
            count.value = state.items().size() - retained.size();
            return encodeState(new TodoState(state.nextId(), retained));
        });
        return count.value;
    }

    private TodoState readState(AgentSession session) {
        return decodeState(session.state().get(stateKey()).orElse(null));
    }

    private static TodoState decodeState(StateValue value) {
        if (value == null) {
            return new TodoState(1, List.of());
        }
        StateValue.ObjectValue object = HarnessToolSupport.object(value, "todo state");
        int nextId = HarnessToolSupport.integer(object.require("nextId"), "nextId");
        StateValue itemsValue = object.require("items");
        if (!(itemsValue instanceof StateValue.ArrayValue array)) {
            throw new IllegalArgumentException("items must be an array.");
        }
        ArrayList<TodoItem> items = new ArrayList<>();
        for (StateValue itemValue : array.values()) {
            StateValue.ObjectValue item = HarnessToolSupport.object(itemValue, "todo item");
            items.add(new TodoItem(
                    HarnessToolSupport.integer(item.require("id"), "id"),
                    requiredString(item, "title"),
                    nullableString(item, "description"),
                    requiredBoolean(item, "completed"),
                    nullableString(item, "completionReason")));
        }
        return new TodoState(nextId, items);
    }

    private static StateValue encodeState(TodoState state) {
        return StateValue.object(Map.of("nextId", StateValue.integer(state.nextId()), "items", items(state.items())));
    }

    private static StateValue.ArrayValue items(List<TodoItem> items) {
        return StateValue.array(items.stream()
                .map(item -> StateValue.object(Map.of(
                        "id",
                        StateValue.integer(item.id()),
                        "title",
                        StateValue.string(item.title()),
                        "description",
                        HarnessToolSupport.nullable(item.description()),
                        "completed",
                        StateValue.bool(item.completed()),
                        "completionReason",
                        HarnessToolSupport.nullable(item.completionReason()))))
                .toList());
    }

    private static List<TodoInput> parseInputs(StateValue.ObjectValue arguments) {
        return HarnessToolSupport.array(arguments, "todos").stream()
                .map(value -> HarnessToolSupport.object(value, "todo"))
                .map(value -> new TodoInput(requiredString(value, "title"), nullableString(value, "description")))
                .toList();
    }

    private static List<TodoCompletion> parseCompletions(StateValue.ObjectValue arguments) {
        return HarnessToolSupport.array(arguments, "items").stream()
                .map(value -> HarnessToolSupport.object(value, "completion"))
                .map(value -> new TodoCompletion(
                        HarnessToolSupport.integer(value.require("id"), "id"), nullableString(value, "reason")))
                .toList();
    }

    private static List<Integer> parseIds(StateValue.ObjectValue arguments) {
        return HarnessToolSupport.array(arguments, "ids").stream()
                .map(value -> HarnessToolSupport.integer(value, "id"))
                .toList();
    }

    private static ToolMetadata metadata(
            String name, String description, StateValue.ObjectValue input, StateValue.ObjectValue output) {
        return new ToolMetadata(
                name, description, Set.of(ToolCapability.FUNCTION), ToolApprovalMode.NEVER_REQUIRE, input, output);
    }

    private static StateValue.ObjectValue addSchema() {
        StateValue.ObjectValue todo = HarnessToolSupport.objectSchema(
                Map.of(
                        "title",
                        HarnessToolSupport.stringProperty("Todo title."),
                        "description",
                        HarnessToolSupport.stringProperty("Optional todo description.")),
                List.of("title"));
        return HarnessToolSupport.objectSchema(
                Map.of("todos", HarnessToolSupport.arrayProperty(todo, "Todo items to add.")), List.of("todos"));
    }

    private static StateValue.ObjectValue completeSchema() {
        StateValue.ObjectValue completion = HarnessToolSupport.objectSchema(
                Map.of(
                        "id",
                        HarnessToolSupport.integerProperty("Todo identifier."),
                        "reason",
                        HarnessToolSupport.stringProperty("Optional completion reason.")),
                List.of("id"));
        return HarnessToolSupport.objectSchema(
                Map.of("items", HarnessToolSupport.arrayProperty(completion, "Todo completions.")), List.of("items"));
    }

    private static StateValue.ObjectValue removeSchema() {
        StateValue.ObjectValue integer = StateValue.object(Map.of("type", StateValue.string("integer")));
        return HarnessToolSupport.objectSchema(
                Map.of("ids", HarnessToolSupport.arrayProperty(integer, "Todo identifiers to remove.")),
                List.of("ids"));
    }

    private static StateValue.ObjectValue emptySchema() {
        return HarnessToolSupport.objectSchema(Map.of(), List.of());
    }

    private static String requiredString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string.");
    }

    private static String nullableString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new IllegalArgumentException(name + " must be a string or null.");
    }

    private static boolean requiredBoolean(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw new IllegalArgumentException(name + " must be a boolean.");
    }

    private static CompletionStage<StateValue> completed(StateValue value) {
        return CompletableFuture.completedFuture(value);
    }

    private static void requireActive(RunCancellation cancellation) {
        if (Objects.requireNonNull(cancellation, "cancellation").isCancellationRequested()) {
            throw new RunCancelledException();
        }
    }

    private record TodoState(int nextId, List<TodoItem> items) {
        private TodoState {
            if (nextId <= 0) {
                throw new IllegalArgumentException("nextId must be greater than zero.");
            }
            items = List.copyOf(items);
        }
    }

    private static final class Holder<T> {
        private T value;
    }
}
