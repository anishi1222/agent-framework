// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TodoAndModeProviderTest {
    @Test
    void todoProvider_shouldPersistCrudStateAndSerializeConcurrentIds() {
        TodoProvider provider = new TodoProvider();
        AgentSession session = new AgentSession("todo-session");
        ContextContribution contribution = provider.provideAsync(HarnessTestContexts.request(session, "todo-run"))
                .toCompletableFuture()
                .join();
        Map<String, FunctionTool> tools = tools(contribution);
        ToolInvocationContext invocation = invocation("todo");
        StateValue.ArrayValue firstBatch = (StateValue.ArrayValue) invoke(
                tools.get(TodoProvider.ADD_TOOL_NAME),
                invocation,
                Map.of(
                        "todos",
                        StateValue.array(List.of(
                                StateValue.object(Map.of("title", StateValue.string("first"))),
                                StateValue.object(Map.of(
                                        "title",
                                        StateValue.string("second"),
                                        "description",
                                        StateValue.string("details")))))));

        AtomicInteger call = new AtomicInteger();
        CompletableFuture<?>[] additions = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> CompletableFuture.runAsync(() -> invoke(
                        tools.get(TodoProvider.ADD_TOOL_NAME),
                        invocation("todo-" + call.incrementAndGet()),
                        Map.of(
                                "todos",
                                StateValue.array(List.of(
                                        StateValue.object(Map.of("title", StateValue.string("parallel-" + index)))))))))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(additions).join();
        invoke(
                tools.get(TodoProvider.COMPLETE_TOOL_NAME),
                invocation,
                Map.of(
                        "items",
                        StateValue.array(List.of(StateValue.object(Map.of(
                                "id", StateValue.integer(1),
                                "reason", StateValue.string("done")))))));

        List<TodoItem> all = provider.getAllTodosAsync(session, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        List<TodoItem> remaining = provider.getRemainingTodosAsync(session, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(firstBatch.values()).hasSize(2);
        assertThat(all).hasSize(22);
        assertThat(all).extracting(TodoItem::id).doesNotHaveDuplicates();
        assertThat(all.getFirst().completed()).isTrue();
        assertThat(all.getFirst().completionReason()).isEqualTo("done");
        assertThat(remaining).hasSize(21);
        assertThat(provider.provideAsync(HarnessTestContexts.request(session, "todo-message"))
                        .toCompletableFuture()
                        .join()
                        .messages())
                .singleElement()
                .satisfies(message -> assertThat(message.text()).contains("[x] #1 first"));
    }

    @Test
    void modeProvider_shouldNormalizeModesAndInjectExternalChangeNotificationOnce() {
        AgentModeProvider provider = new AgentModeProvider();
        AgentSession session = new AgentSession("mode-session");

        assertThat(provider.getModeAsync(session, new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo("plan");
        provider.setModeAsync(session, " EXECUTE ", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        ContextContribution first = provider.provideAsync(HarnessTestContexts.request(session, "mode-first"))
                .toCompletableFuture()
                .join();
        ContextContribution second = provider.provideAsync(HarnessTestContexts.request(session, "mode-second"))
                .toCompletableFuture()
                .join();
        FunctionTool setTool = tools(first).get(AgentModeProvider.SET_TOOL_NAME);
        StateValue result = invoke(setTool, invocation("mode"), Map.of("mode", StateValue.string("PLAN")));

        assertThat(first.instructions()).singleElement().asString().contains("execute");
        assertThat(first.messages())
                .singleElement()
                .satisfies(message -> assertThat(message.text())
                        .contains("Mode changed")
                        .contains("\"plan\"")
                        .contains("\"execute\""));
        assertThat(second.messages()).isEmpty();
        assertThat(result).isEqualTo(StateValue.string("plan"));
    }

    private static Map<String, FunctionTool> tools(ContextContribution contribution) {
        return contribution.tools().stream()
                .map(FunctionTool.class::cast)
                .collect(java.util.stream.Collectors.toMap(FunctionTool::name, tool -> tool));
    }

    private static StateValue invoke(
            FunctionTool tool, ToolInvocationContext context, Map<String, StateValue> arguments) {
        return tool.invokeAsync(context, StateValue.object(arguments))
                .toCompletableFuture()
                .join();
    }

    private static ToolInvocationContext invocation(String id) {
        return new ToolInvocationContext(
                "harness-test",
                "call-" + id,
                new InvocationId("harness-test:" + id),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());
    }
}
