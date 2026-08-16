// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.rpc.HookInvocation;
import com.github.copilot.rpc.McpStdioServerConfig;
import com.github.copilot.rpc.PreMcpToolCallHookInput;
import com.github.copilot.rpc.SessionConfig;
import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubCopilotSdkMapperTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void sessionFeatures_shouldMapOnlyThroughOfficialSdkConfigurationAndHandlers() throws Exception {
        Path javaExecutable =
                Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        GitHubCopilotSessionConfig framework = GitHubCopilotSessionConfig.builder()
                .model("fake-model")
                .reasoningEffort("medium")
                .mcpServer(
                        "local",
                        new GitHubCopilotMCPStdioServerConfig(
                                javaExecutable,
                                List.of("-version"),
                                temporaryDirectory,
                                Map.of("LANG", "C.UTF-8"),
                                List.of("ping"),
                                Duration.ofSeconds(5)))
                .customAgent(new GitHubCopilotCustomAgent(
                        "reviewer",
                        "Reviewer",
                        "Reviews changes.",
                        "Review carefully.",
                        List.of("read_file"),
                        List.of("review-skill"),
                        "fake-model"))
                .skillDirectory(temporaryDirectory)
                .infiniteSession(new GitHubCopilotInfiniteSessionConfig(true, 0.8, 0.95))
                .provider(new GitHubCopilotProviderConfig(
                        "openai",
                        "responses",
                        URI.create("https://api.example.test/v1"),
                        GitHubCopilotSecret.of("provider-key"),
                        null,
                        Map.of(),
                        "fake-model",
                        "wire-model",
                        4096,
                        512))
                .hook(
                        GitHubCopilotHookType.PRE_MCP_TOOL_CALL,
                        request -> java.util.concurrent.CompletableFuture.completedStage(
                                new GitHubCopilotHookResult.McpMetadata(
                                        GitHubCopilotHookResult.McpMetadataAction.REPLACE,
                                        StateValue.object(Map.of("traceId", StateValue.string("framework-trace"))))))
                .build();

        SessionConfig sdk = new GitHubCopilotSdkMapper(GitHubCopilotLimits.defaults()).sessionConfig(framework);

        assertThat(sdk).isInstanceOf(SessionConfig.class);
        assertThat(sdk.getMcpServers().get("local")).isInstanceOf(McpStdioServerConfig.class);
        assertThat(sdk.getCustomAgents()).singleElement().satisfies(agent -> {
            assertThat(agent.getName()).isEqualTo("reviewer");
            assertThat(agent.getModel()).isEqualTo("fake-model");
        });
        assertThat(sdk.getSkillDirectories())
                .containsExactly(temporaryDirectory.toRealPath().toString());
        assertThat(sdk.getInfiniteSessions().getEnabled()).contains(true);
        assertThat(sdk.getProvider().getWireModel()).isEqualTo("wire-model");

        var input = new PreMcpToolCallHookInput()
                .setSessionId("session")
                .setTimestamp(1)
                .setCwd(temporaryDirectory.toString())
                .setServerName("local")
                .setToolName("ping")
                .setToolCallId("call")
                .setArguments(new ObjectMapper().createObjectNode())
                .setMeta(Map.of());
        var output = sdk.getHooks()
                .getOnPreMcpToolCall()
                .handle(input, new HookInvocation().setSessionId("session"))
                .get();

        assertThat(output.getMetaToUse().path("traceId").asText()).isEqualTo("framework-trace");
    }
}
