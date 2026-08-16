// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ShellPolicyAndResultTest {
    @Test
    void evaluate_shouldApplyDenyBeforeAllowAndCustomRules() {
        // Arrange
        AtomicBoolean customCalled = new AtomicBoolean();
        ShellPolicy policy = new ShellPolicy(List.of("git\\s+push"), List.of("^git\\s+"), request -> {
            customCalled.set(true);
            return ShellDecision.allow();
        });

        // Act
        ShellDecision denied = policy.evaluate(new ShellRequest("git push origin main"));

        // Assert
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).contains("deny pattern");
        assertThat(customCalled).isFalse();
    }

    @Test
    void evaluate_shouldTreatEmptyAllowListAsDenyAll() {
        // Arrange
        ShellPolicy policy = new ShellPolicy(List.of(), List.of(), null);

        // Act
        ShellDecision decision = policy.evaluate(new ShellRequest("echo hello"));

        // Assert
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("allow list");
    }

    @Test
    void formatForModel_shouldIncludeOutputStatusAndExitCode() {
        // Arrange
        ShellResult result = new ShellResult("hello", "warning", 124, Duration.ofMillis(20), true, true);

        // Act
        String formatted = result.formatForModel();

        // Assert
        assertThat(formatted)
                .contains("hello")
                .contains("stderr: warning")
                .contains("[output truncated]")
                .contains("[command timed out]")
                .endsWith("exit_code: 124");
    }
}
