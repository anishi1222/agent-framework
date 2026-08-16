// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class GitHubCopilotClientOptionsTest {
    @Test
    void credential_shouldRejectClassicPatAndRedactSupportedToken() {
        assertThatThrownBy(() -> GitHubCopilotCredential.of("ghp_classic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Classic");

        GitHubCopilotCredential credential = GitHubCopilotCredential.of("gho_example");

        assertThat(credential.toString()).doesNotContain("gho_example").contains("REDACTED");
    }

    @Test
    void options_shouldClearAndAllowlistEnvironmentAndRedactCredential() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path root = Path.of(".").toAbsolutePath().normalize();

        GitHubCopilotClientOptions options = GitHubCopilotClientOptions.builder()
                .cliExecutable(java)
                .workingDirectory(root)
                .workingDirectoryRoots(Set.of(root))
                .allowedCliRoots(Set.of(java.getParent()))
                .allowedEnvironmentVariables(Set.of("LANG", "SAFE"))
                .environment(Map.of("LANG", "C.UTF-8", "SAFE", "value"))
                .credential(GitHubCopilotCredential.of("github_pat_example"))
                .build();

        assertThat(options.environment()).containsOnlyKeys("LANG", "SAFE");
        assertThat(options.toString()).doesNotContain("github_pat_example");
    }

    @Test
    void options_shouldRejectEnvironmentOutsideAllowlistAndRemoteTcp() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        assertThatThrownBy(() -> GitHubCopilotClientOptions.builder()
                        .cliExecutable(java)
                        .allowedEnvironmentVariables(Set.of("LANG"))
                        .environment(Map.of("SECRET", "value"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> new GitHubCopilotExternalServer("example.com", 4321, "connection"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void providerSecrets_shouldNeverRender() {
        GitHubCopilotProviderConfig provider = new GitHubCopilotProviderConfig(
                "openai",
                "responses",
                java.net.URI.create("https://api.example.test/v1"),
                GitHubCopilotSecret.of("provider-secret"),
                null,
                Map.of(),
                "model",
                "wire-model",
                1024,
                256);

        assertThat(provider.toString()).doesNotContain("provider-secret").contains("REDACTED");
        assertThat(new GitHubCopilotSessionMetadata(
                                "session", Instant.EPOCH, Instant.EPOCH, null, null, null, null, null)
                        .sessionId())
                .isEqualTo("session");
    }

    @Test
    void sdkManagedDefaultsAndTelemetry_shouldMapToOfficialClientOptions() {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        GitHubCopilotTelemetryConfig telemetry = new GitHubCopilotTelemetryConfig(
                java.net.URI.create("http://127.0.0.1:4318"),
                "http/protobuf",
                null,
                "otlp-http",
                "agent-framework-tests",
                false);
        GitHubCopilotClientOptions options = GitHubCopilotClientOptions.builder()
                .cliExecutable(javaExecutable)
                .workingDirectory(Path.of("."))
                .workingDirectoryRoots(Set.of(Path.of(".")))
                .useLoggedInUser(false)
                .telemetry(telemetry)
                .build();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            com.github.copilot.rpc.CopilotClientOptions sdk = GitHubCopilotClient.sdkOptions(options, null, executor);

            assertThat(options.cliLaunchMode()).isEqualTo(GitHubCopilotCliLaunchMode.SDK_MANAGED);
            assertThat(sdk.getCliPath()).isEqualTo(javaExecutable.toRealPath().toString());
            assertThat(sdk.isUseStdio()).isTrue();
            assertThat(sdk.getEnvironment()).containsOnlyKeys("LANG");
            assertThat(sdk.getTelemetry().getSourceName()).isEqualTo("agent-framework-tests");
            assertThat(sdk.getTelemetry().getCaptureContent()).contains(false);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void emptyAndHardenedModes_shouldRequireExplicitPersistenceAndExecutable() {
        assertThatThrownBy(() -> GitHubCopilotClientOptions.builder()
                        .clientMode(GitHubCopilotClientMode.EMPTY)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("copilotHome");
        assertThatThrownBy(() -> GitHubCopilotClientOptions.builder()
                        .cliLaunchMode(GitHubCopilotCliLaunchMode.HARDENED_EXTERNAL)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cliExecutable");
    }
}
