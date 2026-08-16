// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.harness.files.AgentFileStore;
import com.microsoft.agents.harness.files.FileSearchResult;
import com.microsoft.agents.harness.files.FileStoreEntry;
import com.microsoft.agents.harness.files.InMemoryAgentFileStore;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FileProvidersTest {
    @Test
    void fileAccessProvider_shouldGateSharedToolsAndApplyEdits() {
        InMemoryAgentFileStore store = new InMemoryAgentFileStore();
        FileAccessProvider provider = new FileAccessProvider(store);
        ContextContribution contribution = provider.provideAsync(
                        HarnessTestContexts.request(new AgentSession("access"), "access-run"))
                .toCompletableFuture()
                .join();
        Map<String, FunctionTool> tools = tools(contribution);

        assertThat(tools.values()).allMatch(tool -> tool.metadata().approvalMode() == ToolApprovalMode.ALWAYS_REQUIRE);

        invoke(
                tools.get(FileAccessProvider.WRITE_TOOL_NAME),
                Map.of(
                        "path",
                        StateValue.string("shared/data.txt"),
                        "content",
                        StateValue.string("alpha\nbeta"),
                        "overwrite",
                        StateValue.bool(false)));
        invoke(
                tools.get(FileAccessProvider.REPLACE_LINES_TOOL_NAME),
                Map.of(
                        "path",
                        StateValue.string("shared/data.txt"),
                        "edits",
                        StateValue.array(List.of(StateValue.object(Map.of(
                                "line_number", StateValue.integer(2), "new_line", StateValue.string("BETA")))))));

        assertThat(invoke(
                        tools.get(FileAccessProvider.READ_TOOL_NAME),
                        Map.of("path", StateValue.string("shared/data.txt"))))
                .isEqualTo(StateValue.string("alpha\nBETA"));
        assertThat((StateValue.ArrayValue) invoke(
                        tools.get(FileAccessProvider.GREP_TOOL_NAME),
                        Map.of("directory", StateValue.string("shared"), "pattern", StateValue.string("BETA"))))
                .extracting(StateValue.ArrayValue::values)
                .satisfies(values -> assertThat(values).hasSize(1));
    }

    @Test
    void fileAccessProvider_shouldSupportReadOnlyModeAndApprovalOptOuts() {
        FileAccessProviderOptions options = FileAccessProviderOptions.builder()
                .disableWriteTools(true)
                .disableReadOnlyToolApproval(true)
                .build();
        FileAccessProvider provider = new FileAccessProvider(new InMemoryAgentFileStore(), options);

        List<FunctionTool> tools = provider
                .provideAsync(HarnessTestContexts.request(new AgentSession("readonly"), "readonly-run"))
                .toCompletableFuture()
                .join()
                .tools()
                .stream()
                .map(FunctionTool.class::cast)
                .toList();

        assertThat(tools)
                .extracting(FunctionTool::name)
                .containsExactlyInAnyOrder(
                        FileAccessProvider.READ_TOOL_NAME,
                        FileAccessProvider.LIST_TOOL_NAME,
                        FileAccessProvider.GREP_TOOL_NAME);
        assertThat(tools).allMatch(tool -> tool.metadata().approvalMode() == ToolApprovalMode.NEVER_REQUIRE);
    }

    @Test
    void fileMemoryProvider_shouldIsolateSessionsHideIndexAndRejectNestedNames() {
        InMemoryAgentFileStore store = new InMemoryAgentFileStore();
        FileMemoryProvider provider = new FileMemoryProvider(store);
        AgentSession firstSession = new AgentSession("first-session");
        AgentSession secondSession = new AgentSession("second-session");
        Map<String, FunctionTool> firstTools =
                tools(provider.provideAsync(HarnessTestContexts.request(firstSession, "memory-first"))
                        .toCompletableFuture()
                        .join());

        invoke(
                firstTools.get(FileMemoryProvider.WRITE_TOOL_NAME),
                Map.of(
                        "file_name",
                        StateValue.string("plan.md"),
                        "content",
                        StateValue.string("step one"),
                        "description",
                        StateValue.string("Execution plan")));
        ContextContribution refreshed = provider.provideAsync(
                        HarnessTestContexts.request(firstSession, "memory-refresh"))
                .toCompletableFuture()
                .join();
        Map<String, FunctionTool> secondTools =
                tools(provider.provideAsync(HarnessTestContexts.request(secondSession, "memory-second"))
                        .toCompletableFuture()
                        .join());

        assertThat(refreshed.messages())
                .singleElement()
                .satisfies(message ->
                        assertThat(message.text()).contains("plan.md").contains("Execution plan"));
        assertThat(invoke(secondTools.get(FileMemoryProvider.LIST_TOOL_NAME), Map.of()))
                .isEqualTo(StateValue.array(List.of()));
        assertThatThrownBy(() -> invoke(
                        firstTools.get(FileMemoryProvider.WRITE_TOOL_NAME),
                        Map.of("file_name", StateValue.string("nested/secret.md"), "content", StateValue.string("no"))))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileMemoryProvider_shouldNotAliasPathOrCaseEquivalentSessionIds() {
        InMemoryAgentFileStore store = new InMemoryAgentFileStore();
        FileMemoryProvider provider = new FileMemoryProvider(store);
        Map<String, FunctionTool> firstTools = tools(
                provider.provideAsync(HarnessTestContexts.request(new AgentSession("tenant/a"), "memory-alias-first"))
                        .toCompletableFuture()
                        .join());
        Map<String, FunctionTool> secondTools = tools(
                provider.provideAsync(HarnessTestContexts.request(new AgentSession("tenant\\a"), "memory-alias-second"))
                        .toCompletableFuture()
                        .join());

        invoke(
                firstTools.get(FileMemoryProvider.WRITE_TOOL_NAME),
                Map.of("file_name", StateValue.string("private.md"), "content", StateValue.string("secret")));

        assertThat(invoke(secondTools.get(FileMemoryProvider.LIST_TOOL_NAME), Map.of()))
                .isEqualTo(StateValue.array(List.of()));
    }

    @Test
    void fileAccessProvider_shouldSerializeConcurrentReadModifyWriteOperations() {
        DelayedReadStore store = new DelayedReadStore();
        store.delegate
                .writeAsync("shared.txt", "alpha beta", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        FileAccessProvider provider = new FileAccessProvider(store);
        Map<String, FunctionTool> tools = tools(
                provider.provideAsync(HarnessTestContexts.request(new AgentSession("serialized"), "serialized-run"))
                        .toCompletableFuture()
                        .join());

        CompletionStage<StateValue> first = invokeAsync(
                tools.get(FileAccessProvider.REPLACE_TOOL_NAME),
                Map.of(
                        "path",
                        StateValue.string("shared.txt"),
                        "old_text",
                        StateValue.string("alpha"),
                        "new_text",
                        StateValue.string("ALPHA")));
        store.firstReadRequested.join();
        CompletionStage<StateValue> second = invokeAsync(
                tools.get(FileAccessProvider.REPLACE_TOOL_NAME),
                Map.of(
                        "path",
                        StateValue.string("shared.txt"),
                        "old_text",
                        StateValue.string("beta"),
                        "new_text",
                        StateValue.string("BETA")));

        assertThat(store.readCalls).hasValue(1);
        assertThat(second.toCompletableFuture()).isNotDone();
        store.firstRead.complete("alpha beta");
        CompletableFuture.allOf(first.toCompletableFuture(), second.toCompletableFuture())
                .join();

        assertThat(store.delegate
                        .readAsync("shared.txt", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isEqualTo("ALPHA BETA");
    }

    private static Map<String, FunctionTool> tools(ContextContribution contribution) {
        return contribution.tools().stream()
                .map(FunctionTool.class::cast)
                .collect(java.util.stream.Collectors.toMap(FunctionTool::name, tool -> tool));
    }

    private static StateValue invoke(FunctionTool tool, Map<String, StateValue> arguments) {
        return invokeAsync(tool, arguments).toCompletableFuture().join();
    }

    private static CompletionStage<StateValue> invokeAsync(FunctionTool tool, Map<String, StateValue> arguments) {
        ToolInvocationContext invocation = new ToolInvocationContext(
                "file-provider-test",
                "call-" + tool.name() + "-" + System.nanoTime(),
                new InvocationId("file-provider-test:" + tool.name() + ":" + System.nanoTime()),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());
        return tool.invokeAsync(invocation, StateValue.object(arguments));
    }

    private static final class DelayedReadStore implements AgentFileStore {
        private final InMemoryAgentFileStore delegate = new InMemoryAgentFileStore();

        private final AtomicInteger readCalls = new AtomicInteger();

        private final CompletableFuture<Void> firstReadRequested = new CompletableFuture<>();

        private final CompletableFuture<String> firstRead = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> writeAsync(
                String path, String content, boolean overwrite, RunCancellation cancellation) {
            return delegate.writeAsync(path, content, overwrite, cancellation);
        }

        @Override
        public CompletionStage<String> readAsync(String path, RunCancellation cancellation) {
            if (readCalls.incrementAndGet() == 1) {
                firstReadRequested.complete(null);
                return firstRead;
            }
            return delegate.readAsync(path, cancellation);
        }

        @Override
        public CompletionStage<Boolean> deleteAsync(String path, RunCancellation cancellation) {
            return delegate.deleteAsync(path, cancellation);
        }

        @Override
        public CompletionStage<List<FileStoreEntry>> listChildrenAsync(String directory, RunCancellation cancellation) {
            return delegate.listChildrenAsync(directory, cancellation);
        }

        @Override
        public CompletionStage<Boolean> fileExistsAsync(String path, RunCancellation cancellation) {
            return delegate.fileExistsAsync(path, cancellation);
        }

        @Override
        public CompletionStage<List<FileSearchResult>> searchAsync(
                String directory,
                String regexPattern,
                String globPattern,
                boolean recursive,
                RunCancellation cancellation) {
            return delegate.searchAsync(directory, regexPattern, globPattern, recursive, cancellation);
        }

        @Override
        public CompletionStage<Void> createDirectoryAsync(String path, RunCancellation cancellation) {
            return delegate.createDirectoryAsync(path, cancellation);
        }
    }
}
