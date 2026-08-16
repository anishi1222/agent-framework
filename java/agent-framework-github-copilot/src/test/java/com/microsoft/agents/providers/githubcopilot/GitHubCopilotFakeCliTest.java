// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.SdkProtocolVersion;
import com.github.copilot.generated.rpc.ModelBillingTokenPrices;
import com.github.copilot.generated.rpc.ModelBillingTokenPricesLongContext;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.ModelBilling;
import com.github.copilot.rpc.ModelCapabilities;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.ModelLimits;
import com.github.copilot.rpc.ModelSupports;
import com.github.copilot.rpc.ModelVisionLimits;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitHubCopilotFakeCliTest {
    @Test
    void actualProtocolV3ChildJvm_shouldCoverLifecycleFiniteToolsPermissionInputResumeAndModels() throws Exception {
        try (Harness harness = Harness.start(3)) {
            CopyOnWriteArrayList<GitHubCopilotSessionLifecycleEvent> lifecycle = new CopyOnWriteArrayList<>();
            AutoCloseable lifecycleSubscription = harness.client.onSessionLifecycle(lifecycle::add);
            GitHubCopilotSessionConfig config = GitHubCopilotSessionConfig.builder()
                    .model("fake-model")
                    .permissionHandler(request -> java.util.concurrent.CompletableFuture.completedStage(
                            GitHubCopilotPermissionResponse.deny()))
                    .userInputHandler(request -> java.util.concurrent.CompletableFuture.completedStage(
                            new GitHubCopilotUserInputResponse("yes", false, false)))
                    .tool(new GitHubCopilotTool(
                            "echo",
                            "Echoes a value.",
                            StateValue.object(Map.of(
                                    "type",
                                    StateValue.string("object"),
                                    "properties",
                                    StateValue.object(Map.of(
                                            "value", StateValue.object(Map.of("type", StateValue.string("string"))))))),
                            call -> {
                                assertThat(call.callId()).isEqualTo("tool-call-1");
                                return java.util.concurrent.CompletableFuture.completedStage(
                                        StateValue.string("tool-result"));
                            }))
                    .mcpServer(
                            "local",
                            new GitHubCopilotMCPHttpServerConfig(
                                    java.net.URI.create("http://127.0.0.1:4321"),
                                    Map.of(),
                                    List.of("ping"),
                                    Duration.ofSeconds(1),
                                    true))
                    .customAgent(new GitHubCopilotCustomAgent(
                            "reviewer",
                            "Reviewer",
                            "Reviews changes.",
                            "Review carefully.",
                            List.of("echo"),
                            List.of(),
                            "fake-model"))
                    .skillDirectory(Path.of("."))
                    .infiniteSession(new GitHubCopilotInfiniteSessionConfig(true, 0.8, 0.95))
                    .provider(new GitHubCopilotProviderConfig(
                            "openai",
                            "responses",
                            java.net.URI.create("https://api.example.test/v1"),
                            GitHubCopilotSecret.of("test-provider-key"),
                            null,
                            Map.of(),
                            "fake-model",
                            "fake-model",
                            4096,
                            512))
                    .hook(GitHubCopilotHookType.PRE_TOOL_USE, request -> {
                        assertThat(request.toolName()).isEqualTo("shell");
                        return java.util.concurrent.CompletableFuture.completedStage(
                                new GitHubCopilotHookResult.PreToolUse(
                                        GitHubCopilotHookResult.ToolPermission.DENY,
                                        "blocked by test",
                                        null,
                                        null,
                                        false));
                    })
                    .build();

            harness.client.startAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            try (GitHubCopilotSession session = harness.client
                    .createSessionAsync(config)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)) {
                assertThat(session.sendAndWaitAsync("hello")
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .text())
                        .isEqualTo("answer:hello");
                assertThat(session.sendAndWaitAsync("permission")
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .text())
                        .contains("permission:reject");
                assertThat(session.sendAndWaitAsync("input")
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .text())
                        .isEqualTo("input:yes");
                assertThat(session.sendAndWaitAsync("tool")
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .text())
                        .contains("tool-result");
                assertThat(session.sendAndWaitAsync("hook")
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .text())
                        .isEqualTo("hook:deny");
                session.setModelAsync("fake-model", "medium")
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                session.logAsync("test timeline", GitHubCopilotLogLevel.INFO, true)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                session.compactAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThat(session.workspacePath()).isEqualTo("/tmp/fake-copilot");
                assertThat(session.getMessagesAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                        .anyMatch(event -> event.type() == GitHubCopilotEventType.TOOL_EXECUTION_START);
            }

            assertThat(harness.client.listModelsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .singleElement()
                    .satisfies(model -> {
                        assertThat(model.id()).isEqualTo("fake-model");
                        assertThat(model.supportsVision()).isTrue();
                        assertThat(model.visionLimits().supportedMediaTypes()).containsExactly("image/png");
                        assertThat(model.billing().multiplier()).isEqualTo(1.5);
                        assertThat(model.billing().tokenPrices().inputPrice()).isEqualTo(0.1);
                    });
            assertThat(harness.client
                            .getSessionMetadataAsync("fake-session")
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .gitRoot())
                    .isEqualTo("/tmp");
            assertThat(harness.client.listSessionsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .singleElement()
                    .extracting(GitHubCopilotSessionMetadata::sessionId)
                    .isEqualTo("fake-session");
            assertThat(harness.client
                            .listSessionsAsync(new GitHubCopilotSessionFilter(null, null, "other/repo", null))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS))
                    .isEmpty();
            try (GitHubCopilotSession resumed = harness.client
                    .resumeSessionAsync("fake-session", config)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)) {
                assertThat(resumed.sessionId()).isEqualTo("fake-session");
                resumed.abortAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            harness.client
                    .deleteSessionAsync("fake-session")
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(lifecycle)
                    .extracting(GitHubCopilotSessionLifecycleEvent::type)
                    .contains(
                            GitHubCopilotSessionLifecycleEventType.CREATED,
                            GitHubCopilotSessionLifecycleEventType.DELETED);
            assertThat(harness.client.sdkProtocolVersion()).isEqualTo(SdkProtocolVersion.get());
            lifecycleSubscription.close();
        }
    }

    @Test
    void officialSdkTypes_shouldCreateClientSessionOptionsAndModelsDirectly() throws Exception {
        try (Harness harness = Harness.start(3)) {
            harness.sdkClient.start().get(10, TimeUnit.SECONDS);
            SessionConfig config = new SessionConfig()
                    .setModel("fake-model")
                    .setAvailableTools(List.of())
                    .setOnPermissionRequest(PermissionHandler.APPROVE_ALL);

            try (CopilotSession session =
                    harness.sdkClient.createSession(config).get(10, TimeUnit.SECONDS)) {
                assertThat(session).isInstanceOf(CopilotSession.class);
                assertThat(session.sendAndWait("official-sdk")
                                .get(10, TimeUnit.SECONDS)
                                .getData()
                                .content())
                        .isEqualTo("answer:official-sdk");
            }
        }

        ModelBillingTokenPricesLongContext longContext =
                new ModelBillingTokenPricesLongContext(0.3, 0.4, null, null, null, 16_384L, 12_000L);
        ModelBillingTokenPrices prices =
                new ModelBillingTokenPrices(0.1, 0.2, null, null, null, 1L, 8_192L, 4_096L, longContext);
        ModelInfo officialModel = new ModelInfo()
                .setId("official-model")
                .setName("Official Model")
                .setBilling(new ModelBilling().setMultiplier(2.0).setTokenPrices(prices))
                .setCapabilities(new ModelCapabilities()
                        .setSupports(new ModelSupports().setVision(true).setReasoningEffort(true))
                        .setLimits(new ModelLimits()
                                .setMaxPromptTokens(4096)
                                .setMaxContextWindowTokens(8192)
                                .setVision(new ModelVisionLimits()
                                        .setSupportedMediaTypes(List.of("image/png"))
                                        .setMaxPromptImages(2)
                                        .setMaxPromptImageSize(1024))));

        GitHubCopilotModel mapped = GitHubCopilotClient.model(officialModel);

        assertThat(mapped.billing().tokenPrices().longContext().inputPrice()).isEqualTo(0.3);
        assertThat(mapped.visionLimits().maxPromptImages()).isEqualTo(2);
    }

    @Test
    void protocolVersionMismatch_shouldFailClosed() throws Exception {
        try (Harness harness = Harness.start(99)) {
            assertThatThrownBy(() ->
                            harness.client.startAsync().toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .hasRootCauseMessage("SDK protocol version mismatch: SDK supports versions 2-3, "
                            + "but server reports version 99. "
                            + "Please update your SDK or server to ensure compatibility.");
            assertThat(harness.client.state()).isEqualTo(GitHubCopilotClientState.FAILED);
        }
    }

    @Test
    void defaultPermissionAndUserInputHandlers_shouldDenyAndDeclineThroughOfficialSdk() throws Exception {
        try (Harness harness = Harness.start(3);
                GitHubCopilotSession session = harness.client
                        .createSessionAsync(GitHubCopilotSessionConfig.builder()
                                .model("fake-model")
                                .build())
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)) {
            assertThat(session.sendAndWaitAsync("permission")
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .text())
                    .isEqualTo("permission:reject");
            assertThat(session.sendAndWaitAsync("input")
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .text())
                    .isEqualTo("input:");
        }
    }

    @Test
    void chatClient_shouldMapFiniteResponseOnceWithoutResendingExternalHistory() throws Exception {
        try (Harness harness = Harness.start(3)) {
            GitHubCopilotSessionConfig config =
                    GitHubCopilotSessionConfig.builder().model("fake-model").build();
            GitHubCopilotChatClient chat = new GitHubCopilotChatClient(harness.client, config);

            assertThatThrownBy(() -> chat.completeAsync(new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "structured")),
                                    ChatOptions.builder()
                                            .structuredOutput(StructuredOutputOptions.jsonSchema(
                                                    "answer", Map.of("type", StateValue.string("object"))))
                                            .build()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("GitHub Copilot sessions do not support ChatOptions.structuredOutput.");

            var response = chat.completeAsync(new ChatClientRequest(
                            List.of(
                                    Message.text(Role.USER, "old user"),
                                    Message.text(Role.ASSISTANT, "old assistant"),
                                    Message.text(Role.USER, "new user")),
                            ChatOptions.builder().conversationId("fake-session").build()))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            assertThat(response.text()).isEqualTo("answer:new user");
            assertThat(response.conversationId()).isEqualTo("fake-session");
            assertThat(response.usage().inputTokens()).hasValue(java.math.BigInteger.valueOf(4));
        }
    }

    @Test
    void chatClientStreaming_shouldEmitDeltaTextExactlyOnceAndFinish() throws Exception {
        try (Harness harness = Harness.start(3)) {
            GitHubCopilotChatClient chat = new GitHubCopilotChatClient(
                    harness.client,
                    GitHubCopilotSessionConfig.builder().model("fake-model").build());
            CopyOnWriteArrayList<ChatResponseUpdate> updates = new CopyOnWriteArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch terminal = new CountDownLatch(1);

            chat.completeStreaming(
                            new ChatClientRequest(
                                    List.of(Message.text(Role.USER, "stream")),
                                    ChatOptions.builder()
                                            .conversationId("fake-session")
                                            .build()),
                            new DefaultRunCancellation())
                    .subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(ChatResponseUpdate item) {
                            updates.add(item);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            failure.set(throwable);
                            terminal.countDown();
                        }

                        @Override
                        public void onComplete() {
                            terminal.countDown();
                        }
                    });

            assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(updates.stream().map(ChatResponseUpdate::text).reduce("", String::concat))
                    .isEqualTo("answer:stream");
            assertThat(updates.getLast().finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.STOP);
        }
    }

    @Test
    void publicEventOverflow_shouldTerminateSlowSubscriberWithoutStoppingSdkCallbacks() throws Exception {
        GitHubCopilotLimits defaults = GitHubCopilotLimits.defaults();
        GitHubCopilotLimits limits = new GitHubCopilotLimits(
                defaults.maxProcessOutputLineBytes(),
                defaults.maxDocumentBytes(),
                defaults.maxNestingDepth(),
                defaults.maxStringLength(),
                defaults.maxCollectionEntries(),
                defaults.maxEventBytes(),
                1,
                defaults.maxStderrBytes(),
                defaults.maxConcurrentRequests());
        try (Harness harness = Harness.start(3, limits);
                GitHubCopilotSession session = harness.client
                        .createSessionAsync(GitHubCopilotSessionConfig.builder()
                                .model("fake-model")
                                .build())
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)) {
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> overflow = new AtomicReference<>();
            session.events().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(1);
                }

                @Override
                public void onNext(GitHubCopilotEvent item) {}

                @Override
                public void onError(Throwable throwable) {
                    overflow.set(throwable);
                    failed.countDown();
                }

                @Override
                public void onComplete() {
                    failed.countDown();
                }
            });

            assertThat(session.sendAndWaitAsync("overflow")
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                            .text())
                    .isEqualTo("overflow-complete");
            assertThat(failed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(overflow.get()).hasMessageContaining("buffer");
        }
    }

    @Test
    void oversizedSdkEvent_shouldFailMappedTurnAndLateEventsShouldBeIgnored() throws Exception {
        GitHubCopilotLimits defaults = GitHubCopilotLimits.defaults();
        GitHubCopilotLimits limits = new GitHubCopilotLimits(
                defaults.maxProcessOutputLineBytes(),
                defaults.maxDocumentBytes(),
                defaults.maxNestingDepth(),
                64,
                defaults.maxCollectionEntries(),
                defaults.maxEventBytes(),
                defaults.maxBufferedEvents(),
                defaults.maxStderrBytes(),
                defaults.maxConcurrentRequests());
        try (Harness harness = Harness.start(3, limits)) {
            GitHubCopilotSession malformed = harness.client
                    .createSessionAsync(GitHubCopilotSessionConfig.builder()
                            .model("fake-model")
                            .build())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> malformed
                            .sendAndWaitAsync("malformed")
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Copilot assistant delta exceeds the configured string limit.");
            malformed.close();

            GitHubCopilotSession late = harness.client
                    .createSessionAsync(GitHubCopilotSessionConfig.builder()
                            .model("fake-model")
                            .build())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            CopyOnWriteArrayList<GitHubCopilotEvent> events = new CopyOnWriteArrayList<>();
            late.addListener(events::add);
            late.sendAndWaitAsync("hello").toCompletableFuture().get(10, TimeUnit.SECONDS);
            int beforeClose = events.size();
            late.close();
            Thread.sleep(300);
            assertThat(events).hasSize(beforeClose);
        }
    }

    @Test
    void chatCancellation_shouldBridgeToOfficialSessionAbort() throws Exception {
        try (Harness harness = Harness.start(3)) {
            GitHubCopilotChatClient chat = new GitHubCopilotChatClient(
                    harness.client,
                    GitHubCopilotSessionConfig.builder().model("fake-model").build());
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            var response = chat.completeAsync(
                    new ChatClientRequest(List.of(Message.text(Role.USER, "block")), ChatOptions.empty()),
                    cancellation);

            Thread.sleep(100);
            cancellation.cancel();

            assertThatThrownBy(() -> response.toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService executor;

        private final GitHubCopilotClient client;

        private final CopilotClient sdkClient;

        private Harness(ExecutorService executor, GitHubCopilotClient client, CopilotClient sdkClient) {
            this.executor = executor;
            this.client = client;
            this.sdkClient = sdkClient;
        }

        private static Harness start(int protocol) {
            return start(protocol, GitHubCopilotLimits.defaults());
        }

        private static Harness start(int protocol, GitHubCopilotLimits limits) {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            String classpath = System.getProperty("java.class.path");
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CopilotClientOptions sdkOptions = new CopilotClientOptions()
                    .setAutoStart(false)
                    .setMode(CopilotClientMode.EMPTY)
                    .setCopilotHome(Path.of(".").toAbsolutePath().normalize().toString())
                    .setCliPath(java.toString())
                    .setCliArgs(new String[] {
                        "-cp", classpath, FakeCopilotCliMain.class.getName(), Integer.toString(protocol)
                    })
                    .setEnvironment(Map.of("LANG", "C.UTF-8"))
                    .setUseLoggedInUser(false)
                    .setUseStdio(true)
                    .setExecutor(executor)
                    .setLogLevel("error");
            GitHubCopilotClientOptions frameworkOptions = GitHubCopilotClientOptions.builder()
                    .cliExecutable(java)
                    .workingDirectory(Path.of("."))
                    .workingDirectoryRoots(Set.of(Path.of(".")))
                    .startupTimeout(Duration.ofSeconds(10))
                    .requestTimeout(Duration.ofSeconds(10))
                    .closeTimeout(Duration.ofSeconds(5))
                    .limits(limits)
                    .executor(executor)
                    .build();
            CopilotClient sdkClient = new CopilotClient(sdkOptions);
            return new Harness(executor, new GitHubCopilotClient(frameworkOptions, sdkClient, executor), sdkClient);
        }

        @Override
        public void close() {
            try {
                client.stopAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // A protocol-mismatch harness has no graceful connection to stop.
            }
            executor.shutdownNow();
        }
    }
}
