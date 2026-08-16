// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HardenedExternalCopilotCliLauncherTest {
    @Test
    void childJvmTcpLauncher_shouldUseArgvClearEnvironmentBoundAndRedactStderrAndClose() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        GitHubCopilotLimits defaults = GitHubCopilotLimits.defaults();
        GitHubCopilotLimits limits = new GitHubCopilotLimits(
                defaults.maxProcessOutputLineBytes(),
                defaults.maxDocumentBytes(),
                defaults.maxNestingDepth(),
                defaults.maxStringLength(),
                defaults.maxCollectionEntries(),
                defaults.maxEventBytes(),
                defaults.maxBufferedEvents(),
                128,
                defaults.maxConcurrentRequests());
        GitHubCopilotClientOptions processOptions = GitHubCopilotClientOptions.builder()
                .cliExecutable(java)
                .workingDirectory(Path.of("."))
                .workingDirectoryRoots(Set.of(Path.of(".")))
                .allowedEnvironmentVariables(Set.of("LANG"))
                .environment(Map.of("LANG", "C.UTF-8"))
                .credential(GitHubCopilotCredential.of("gho_process_secret"))
                .cliLaunchMode(GitHubCopilotCliLaunchMode.HARDENED_EXTERNAL)
                .startupTimeout(Duration.ofSeconds(10))
                .closeTimeout(Duration.ofSeconds(5))
                .limits(limits)
                .build();
        HardenedExternalCopilotCliLauncher process = new HardenedExternalCopilotCliLauncher(
                processOptions,
                List.of("-cp", System.getProperty("java.class.path"), FakeCopilotTcpCliMain.class.getName(), "3"));
        GitHubCopilotExternalServer server = process.start();
        Thread.sleep(100);
        long descendantPid = process.sanitizedStderr()
                .lines()
                .filter(line -> line.startsWith("descendantPid="))
                .mapToLong(line -> Long.parseLong(line.substring("descendantPid=".length())))
                .findFirst()
                .orElseThrow();
        GitHubCopilotClientOptions clientOptions = GitHubCopilotClientOptions.builder()
                .externalServer(server)
                .clientMode(GitHubCopilotClientMode.EMPTY)
                .workingDirectory(Path.of("."))
                .workingDirectoryRoots(Set.of(Path.of(".")))
                .startupTimeout(Duration.ofSeconds(10))
                .closeTimeout(Duration.ofSeconds(5))
                .build();
        try (GitHubCopilotClient client = new GitHubCopilotClient(clientOptions)) {
            client.startAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(client.state()).isEqualTo(GitHubCopilotClientState.RUNNING);
            assertThat(process.sanitizedStderr())
                    .doesNotContain("gho_process_secret")
                    .hasSizeLessThanOrEqualTo(128);
        } finally {
            process.close();
        }
        assertThat(process.isAliveForTesting()).isFalse();
        assertThat(ProcessHandle.of(descendantPid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }
}
